/* Copyright (C) 2026 HyperCeiler Contributions. SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.utils.prefs;

import android.content.SharedPreferences;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** One-time, read-only import of this module's old LSPosed NSP files into private DPS storage. */
public final class LegacyPrefsMigration {
    public static final String STATE_NAME = "hyperceiler_legacy_preferences_migration";
    public static final String COMPLETED_KEY = "completed_v1";
    public static final String SCAN_OK = "HYPERCEILER_NSP_SCAN_OK_V1";
    public static final String READ_OK = "HYPERCEILER_NSP_READ_OK_V1";
    private static final String BASELINE_KEY = "attempt_baseline_xml";
    private static final String TOUCHED_KEY = "user_touched_keys";
    private static final String CLEARED_KEY = "user_cleared";
    private static final String RESET_SNAPSHOT_KEY = "reset_snapshot_xml";
    private static final int MAX_XML_BYTES = 4 * 1024 * 1024;
    private static final String NAME = "com.sevtinge.hyperceiler/hyperceiler_prefs.xml";
    private static final Pattern OWN_PATH = Pattern.compile("/data/misc/[A-Za-z0-9_.-]+/prefs/(?:0/)?" + Pattern.quote(NAME) + "(?:\\.bak)?");

    private LegacyPrefsMigration() {}

    @FunctionalInterface public interface RootCommand {
        String execute(String command) throws Exception;
    }

    public enum Status { IMPORTED, NO_SOURCE, LOCAL_RESET, ALREADY_COMPLETE, FAILED }

    public record Result(Status status, String detail) {
        public boolean isComplete() { return status != Status.FAILED; }
    }

    public record Prepared(Map<String, Object> values, String sourcePath, File backupFile, String failure) {
        private static Prepared failed(String reason) { return new Prepared(null, null, null, reason); }
    }

    /**
     * Retain and register this listener as soon as canonical preferences are opened. It persists user
     * intent separately from hook preferences, including clear() and an explicit edit back to an old
     * value. Writes made by apply() itself are suppressed. Use the same state preferences as apply().
     */
    public static final class Changes implements SharedPreferences.OnSharedPreferenceChangeListener {
        private final SharedPreferences state;
        private final Set<String> touched;
        private boolean cleared;
        private Map<String, Object> resetSnapshot;
        private boolean suppressed;
        private boolean trackingFailed;

        public Changes(SharedPreferences state) {
            this.state = state;
            touched = new LinkedHashSet<>(state.getStringSet(TOUCHED_KEY, Set.of()));
            cleared = state.getBoolean(CLEARED_KEY, false);
            if (cleared) {
                // Older intent-only journals still mean reset, never "reuse stale canonical data".
                resetSnapshot = new LinkedHashMap<>();
                if (state.contains(RESET_SNAPSHOT_KEY)) {
                    try {
                        resetSnapshot = SharedPrefsXml.parse(state.getString(RESET_SNAPSHOT_KEY, ""));
                    } catch (IOException | ClassCastException e) {
                        resetSnapshot = null;
                        trackingFailed = true;
                    }
                }
            }
        }

        @Override public synchronized void onSharedPreferenceChanged(SharedPreferences preferences, String key) {
            if (suppressed || state.getBoolean(COMPLETED_KEY, false)) return;
            if (key == null) {
                cleared = true;
                resetSnapshot = copy(preferences.getAll());
            } else {
                touched.add(key);
                if (cleared && resetSnapshot != null) {
                    // After a restart canonical storage may still contain values whose clear()
                    // failed on disk. Only the newly edited key belongs in the reset result.
                    Map<String, ?> current = preferences.getAll();
                    if (current.containsKey(key)) resetSnapshot.put(key, copyValue(current.get(key)));
                    else resetSnapshot.remove(key);
                }
            }
            trackingFailed = !persist();
        }

        private boolean persist() {
            try {
                SharedPreferences.Editor edit = state.edit().putStringSet(TOUCHED_KEY, new LinkedHashSet<>(touched))
                    .putBoolean(CLEARED_KEY, cleared);
                if (cleared) {
                    if (resetSnapshot == null) return false;
                    // The intent and its expected result must commit together before replay.
                    edit.putString(RESET_SNAPSHOT_KEY, SharedPrefsXml.serialize(resetSnapshot));
                }
                return edit.commit();
            } catch (IOException e) {
                return false;
            }
        }
    }

    public static boolean isComplete(SharedPreferences state) {
        return state.getBoolean(COMPLETED_KEY, false);
    }

    /** Run only on a worker thread: root may show a prompt or wait for its manager. No source is modified. */
    public static Prepared prepare(RootCommand root, File privateBackupDirectory) {
        try {
            String script = "set -eu; [ \"$(id -u)\" = 0 ]; [ -d /data/misc ]; [ -r /data/misc ]; [ -x /data/misc ]; "
                + "for p in /data/misc/*/prefs/" + NAME + " /data/misc/*/prefs/" + NAME + ".bak"
                + " /data/misc/*/prefs/0/" + NAME + " /data/misc/*/prefs/0/" + NAME + ".bak; do "
                + "[ -e \"$p\" ] || continue; [ -f \"$p\" ]; [ \"$(realpath \"$p\")\" = \"$p\" ]; "
                + "stat -c '%Y\t%n' \"$p\"; done; printf '%s' '" + SCAN_OK + "'";
            List<Candidate> candidates = parseScan(root.execute(shell(script)));
            if (candidates.isEmpty()) return new Prepared(null, null, null, null);
            candidates.sort(Comparator.comparingLong(Candidate::modified).reversed().thenComparing(Candidate::path));
            Candidate selected = candidates.get(0);
            Map<String, Object> selectedValues = null;
            String selectedXml = null;
            for (Candidate candidate : candidates) {
                if (candidate.modified() != selected.modified()) break;
                String readScript = "set -eu; [ \"$(id -u)\" = 0 ]; [ -f " + quote(candidate.path()) + " ]; "
                    + "[ \"$(realpath " + quote(candidate.path()) + ")\" = " + quote(candidate.path()) + " ]; "
                    + "[ \"$(stat -c '%Y' " + quote(candidate.path()) + ")\" = '" + candidate.modified() + "' ]; "
                    + "[ \"$(stat -c '%s' " + quote(candidate.path()) + ")\" -le " + MAX_XML_BYTES + " ]; "
                    + "cat " + quote(candidate.path()) + "; printf '\n%s' '" + READ_OK + "'";
                String xml = stripSuccess(root.execute(shell(readScript)), READ_OK);
                if (xml.getBytes(StandardCharsets.UTF_8).length > MAX_XML_BYTES) throw new IOException("Legacy preferences exceed size limit");
                Map<String, Object> parsed = SharedPrefsXml.parse(xml);
                if (selectedValues != null && !selectedValues.equals(parsed)) {
                    throw new IOException("Newest legacy sources have equal timestamps but different values");
                }
                if (selectedValues == null) { selectedValues = parsed; selectedXml = xml; }
            }
            File backup = backup(privateBackupDirectory, selectedXml);
            return new Prepared(copy(selectedValues), selected.path(), backup, null);
        } catch (Exception e) {
            // Do not include XML or shell output: it can contain private module configuration.
            return Prepared.failed("Legacy preference discovery/read failed: " + e.getClass().getSimpleName());
        }
    }

    /**
     * Apply on the same thread as UI preference writes (normally main), after background prepare().
     * Capture startupSnapshot before UI edits and retain Changes from that point. This serialization
     * prevents a UI edit between the final getAll() and commit() from being overwritten.
     */
    public static Result apply(SharedPreferences target, SharedPreferences state, Map<String, ?> startupSnapshot,
                               Prepared prepared, Changes changes) {
        if (isComplete(state)) return new Result(Status.ALREADY_COMPLETE, "Legacy preference transition was already completed");
        synchronized (changes) {
            if (changes.state != state) return failed("Migration listener uses a different state store");
            if (changes.trackingFailed || changes.cleared) {
                changes.trackingFailed = !changes.persist();
                if (changes.trackingFailed) return failed("Cannot persist edits made during migration");
            }
            // A user's explicit reset supersedes importing legacy values, even when discovery
            // failed. The listener suppresses our own clear(), so this is never an automatic fallback.
            if (changes.cleared) {
                try {
                    if (!commitSnapshot(target, copy(changes.resetSnapshot), changes)) {
                        return failed("Private reset commit failed; reset intent retained for retry");
                    }
                    return finish(state, Status.LOCAL_RESET, "User reset local preferences; legacy NSP source retained");
                } catch (IllegalArgumentException | ClassCastException e) {
                    return failed("Cannot commit local reset preference types: " + e.getClass().getSimpleName());
                }
            }
            if (prepared == null || prepared.failure() != null) {
                return failed(prepared == null ? "No discovery result" : prepared.failure());
            }
            if (prepared.values() == null) {
                return finish(state, Status.NO_SOURCE, "No legacy NSP file exists");
            }
            try {
                Map<String, Object> baseline;
                if (state.contains(BASELINE_KEY)) baseline = SharedPrefsXml.parse(state.getString(BASELINE_KEY, ""));
                else {
                    baseline = copy(startupSnapshot);
                    // Write ahead of the target: keep this baseline across failed commits and app restarts.
                    if (!state.edit().putString(BASELINE_KEY, SharedPrefsXml.serialize(baseline)).commit()) {
                        return failed("Cannot save migration retry journal");
                    }
                }
                Map<String, Object> current = copy(target.getAll());
                Map<String, Object> merged = merge(prepared.values(), baseline, current, changes.touched, changes.cleared);
                // Always retry the disk commit. Android commit(false) may already have changed
                // getAll() in memory, so equality with current does not prove durable storage.
                if (!commitSnapshot(target, merged, changes)) {
                    return failed("Private preference commit failed; migration can retry");
                }
                return finish(state, Status.IMPORTED, prepared.sourcePath());
            } catch (IOException | IllegalArgumentException | ClassCastException e) {
                return failed("Cannot import legacy preference types or retry journal: " + e.getClass().getSimpleName());
            }
        }
    }

    static Map<String, Object> merge(Map<String, ?> source, Map<String, ?> startup, Map<String, ?> current,
                                      Set<String> touched, boolean cleared) {
        if (cleared) return copy(current);
        Map<String, Object> result = copy(source);
        Set<String> keys = new LinkedHashSet<>(startup.keySet());
        keys.addAll(current.keySet()); keys.addAll(touched);
        for (String key : keys) {
            if (touched.contains(key) || startup.containsKey(key) != current.containsKey(key)
                || !Objects.equals(startup.get(key), current.get(key))) {
                if (current.containsKey(key)) result.put(key, copyValue(current.get(key)));
                else result.remove(key);
            }
        }
        return result;
    }

    private static boolean commitSnapshot(SharedPreferences target, Map<String, Object> snapshot, Changes changes) {
        changes.suppressed = true;
        try {
            SharedPreferences.Editor edit = target.edit().clear();
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) put(edit, entry.getKey(), entry.getValue());
            return edit.commit();
        } finally { changes.suppressed = false; }
    }

    private static Result finish(SharedPreferences state, Status status, String detail) {
        // Keep the retry journal until this separate success marker is durably committed.
        if (!state.edit().putBoolean(COMPLETED_KEY, true).putString("status", status.name()).putString("source", detail).commit()) {
            // Android commit(false) can still update the in-memory map. Do not report that as complete.
            state.edit().remove(COMPLETED_KEY).commit();
            return failed("Cannot persist migration completion marker; retry journal retained");
        }
        return new Result(status, detail);
    }

    private static Result failed(String reason) { return new Result(Status.FAILED, reason); }

    private record Candidate(String path, long modified) {}

    private static List<Candidate> parseScan(String output) throws IOException {
        String entries = stripSuccess(output, SCAN_OK);
        List<Candidate> result = new ArrayList<>();
        Set<String> paths = new LinkedHashSet<>();
        if (entries.isEmpty()) return result;
        for (String line : entries.split("\n", -1)) {
            String[] parts = line.split("\t", -1);
            if (parts.length != 2 || !OWN_PATH.matcher(parts[1]).matches() || !paths.add(parts[1])) {
                throw new IOException("Unexpected legacy scan record");
            }
            try {
                long modified = Long.parseLong(parts[0]);
                if (modified < 0) throw new NumberFormatException();
                result.add(new Candidate(parts[1], modified));
            } catch (NumberFormatException e) { throw new IOException("Invalid legacy timestamp", e); }
        }
        // Android restores .bak before reading XML after an interrupted write. A newer,
        // partially written XML must never outrank that complete backup in the same directory.
        result.removeIf(candidate -> !candidate.path().endsWith(".bak") && paths.contains(candidate.path() + ".bak"));
        return result;
    }

    private static String stripSuccess(String output, String marker) throws IOException {
        if (output == null) throw new IOException("Root command returned no result");
        if (output.equals(marker)) return "";
        String suffix = "\n" + marker;
        if (!output.endsWith(suffix)) throw new IOException("Root command did not finish successfully");
        return output.substring(0, output.length() - suffix.length());
    }

    private static String shell(String script) { return "sh -c " + quote(script); }
    private static String quote(String value) { return "'" + value.replace("'", "'\"'\"'") + "'"; }

    private static File backup(File directory, String xml) throws IOException, NoSuchAlgorithmException {
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create private migration backup directory");
        byte[] data = xml.getBytes(StandardCharsets.UTF_8);
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(data);
        StringBuilder hash = new StringBuilder();
        for (byte value : digest) hash.append(String.format(java.util.Locale.ROOT, "%02x", value & 0xff));
        File destination = new File(directory, "legacy-nsp-" + hash + ".xml");
        File temporary = File.createTempFile("legacy-nsp-", ".tmp", directory);
        try {
            try (FileOutputStream output = new FileOutputStream(temporary)) {
                output.write(data); output.getFD().sync();
            }
            try { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException ignored) { Files.move(temporary.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temporary.toPath()); }
        return destination;
    }

    private static Map<String, Object> copy(Map<String, ?> values) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            if (entry.getKey() == null) throw new IllegalArgumentException("Null preference key");
            result.put(entry.getKey(), copyValue(entry.getValue()));
        }
        return result;
    }

    private static Object copyValue(Object value) {
        if (value instanceof Set<?> set) {
            Set<String> result = new LinkedHashSet<>();
            for (Object entry : set) {
                if (!(entry instanceof String text)) throw new IllegalArgumentException("Non-string preference set");
                result.add(text);
            }
            return result;
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Integer || value instanceof Long || value instanceof Float) return value;
        throw new IllegalArgumentException("Unsupported preference value");
    }

    @SuppressWarnings("unchecked") private static void put(SharedPreferences.Editor edit, String key, Object value) {
        if (value instanceof String string) edit.putString(key, string);
        else if (value instanceof Set<?>) edit.putStringSet(key, (Set<String>) value);
        else if (value instanceof Boolean bool) edit.putBoolean(key, bool);
        else if (value instanceof Integer integer) edit.putInt(key, integer);
        else if (value instanceof Long number) edit.putLong(key, number);
        else if (value instanceof Float number) edit.putFloat(key, number);
        else throw new IllegalArgumentException("Unsupported preference value");
    }
}
