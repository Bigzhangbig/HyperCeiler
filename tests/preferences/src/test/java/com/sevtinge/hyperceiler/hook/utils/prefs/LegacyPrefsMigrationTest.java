package com.sevtinge.hyperceiler.hook.utils.prefs;

import static org.junit.Assert.*;

import android.content.SharedPreferences;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Test;

public class LegacyPrefsMigrationTest {
    private static final String OLD = "/data/misc/old/prefs/com.sevtinge.hyperceiler/hyperceiler_prefs.xml";
    private static final String NEW = "/data/misc/new/prefs/com.sevtinge.hyperceiler/hyperceiler_prefs.xml";

    @Test public void latestSourceWinsAndEveryTypedValueIsImported() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("old", true, "prefs_key_allow_hook", false));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("prefs_key_allow_hook", true); source.put("text", "[not,a,set]");
        source.put("apps", Set.of("a", "b")); source.put("empty", Set.of());
        source.put("int", -3); source.put("long", 2147483648L); source.put("float", 1.25f);
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(OLD, 1L, NEW, 2L), Map.of(OLD, "<map/>", NEW, SharedPrefsXml.serialize(source)));
        LegacyPrefsMigration.Result result = LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, new LegacyPrefsMigration.Changes(state));
        assertTrue(result.toString(), result.isComplete());
        assertEquals(source, target.getAll());
        assertTrue(state.getBoolean(LegacyPrefsMigration.COMPLETED_KEY, false));
        assertNotNull(prepared.backupFile());
        assertEquals(source, SharedPrefsXml.parse(Files.readString(prepared.backupFile().toPath())));
    }

    @Test public void editsAndRemovalsWhileScanningTakePrecedenceOverLegacy() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("same", "stale", "removed", true, "kept", 1));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        Map<String, ?> startup = target.getAll();
        LegacyPrefsMigration.Changes changes = new LegacyPrefsMigration.Changes(state);
        target.registerOnSharedPreferenceChangeListener(changes);
        target.edit().putString("same", "new-ui").remove("removed").putString("created", "ui").commit();
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(NEW, 2L), Map.of(NEW, "<map><string name='same'>legacy</string><boolean name='removed' value='true'/><int name='kept' value='8'/></map>"));
        assertTrue(LegacyPrefsMigration.apply(target, state, startup, prepared, changes).isComplete());
        assertEquals(Map.of("same", "new-ui", "kept", 8, "created", "ui"), target.getAll());
    }

    @Test public void clearEvenOfAnEmptyTargetPreventsResurrectingLegacyValues() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of());
        MemoryPreferences state = new MemoryPreferences(Map.of());
        LegacyPrefsMigration.Changes changes = new LegacyPrefsMigration.Changes(state);
        target.registerOnSharedPreferenceChangeListener(changes);
        target.edit().clear().commit();
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(NEW, 1L), Map.of(NEW, "<map><boolean name='old' value='true'/></map>"));
        assertTrue(LegacyPrefsMigration.apply(target, state, Map.of(), prepared, changes).isComplete());
        assertEquals(Map.of(), target.getAll());
    }

    @Test public void rootFailureCorruptionOrUnreadableSourceNeverMarksComplete() throws Exception {
        for (LegacyPrefsMigration.Prepared prepared : List.of(
            LegacyPrefsMigration.prepare(command -> "su: denied", backupDirectory()),
            prepared(Map.of(NEW, 2L), Map.of(NEW, "<map>broken")),
            prepared(Map.of(NEW, 2L), Map.of()))) {
            MemoryPreferences target = new MemoryPreferences(Map.of("existing", true));
            MemoryPreferences state = new MemoryPreferences(Map.of());
            assertFalse(LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, new LegacyPrefsMigration.Changes(state)).isComplete());
            assertEquals(Map.of("existing", true), target.getAll());
            assertFalse(state.getBoolean(LegacyPrefsMigration.COMPLETED_KEY, false));
        }
    }

    @Test public void onlyAnExplicitSuccessfulRootScanCanCompleteWithNoSource() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("existing", true));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(), Map.of());
        assertTrue(LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, new LegacyPrefsMigration.Changes(state)).isComplete());
        assertEquals(Map.of("existing", true), target.getAll());
    }

    @Test public void failedImportCanRetryAndSuccessNeverRunsTwice() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("setting", "private-old"));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        Map<String, ?> startup = target.getAll();
        LegacyPrefsMigration.Changes changes = new LegacyPrefsMigration.Changes(state);
        target.registerOnSharedPreferenceChangeListener(changes);
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(NEW, 2L), Map.of(NEW, "<map><string name='setting'>legacy</string></map>"));
        target.failNextCommit = true;
        assertFalse(LegacyPrefsMigration.apply(target, state, startup, prepared, changes).isComplete());
        assertFalse(state.getBoolean(LegacyPrefsMigration.COMPLETED_KEY, false));
        target.edit().putString("setting", "new-ui").commit();
        assertTrue(LegacyPrefsMigration.apply(target, state, startup, prepared, changes).isComplete());
        assertEquals("new-ui", target.getString("setting", ""));
        target.edit().putString("setting", "after-success").commit();
        assertTrue(LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, changes).isComplete());
        assertEquals("after-success", target.getString("setting", ""));
    }

    @Test public void transientEditJournalFailureCanRetryWithoutLosingUserIntent() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("setting", "private-old"));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        Map<String, ?> startup = target.getAll();
        LegacyPrefsMigration.Changes changes = new LegacyPrefsMigration.Changes(state);
        target.registerOnSharedPreferenceChangeListener(changes);
        state.failNextCommit = true;
        target.edit().putString("setting", "new-ui").commit();
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(NEW, 2L), Map.of(NEW, "<map><string name='setting'>legacy</string></map>"));
        assertTrue(LegacyPrefsMigration.apply(target, state, startup, prepared, changes).isComplete());
        assertEquals("new-ui", target.getString("setting", ""));
    }

    @Test public void retriesDiskWriteAfterFailedCommitThatAlreadyChangedMemory() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("setting", "private-old"));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        Map<String, ?> startup = target.getAll();
        LegacyPrefsMigration.Changes changes = new LegacyPrefsMigration.Changes(state);
        target.registerOnSharedPreferenceChangeListener(changes);
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(NEW, 2L), Map.of(NEW, "<map><string name='setting'>legacy</string></map>"));
        target.mutateOnFailedCommit = true;
        target.failNextCommit = true;
        assertFalse(LegacyPrefsMigration.apply(target, state, startup, prepared, changes).isComplete());
        assertEquals("legacy", target.getString("setting", ""));
        assertEquals("private-old", target.diskValues.get("setting"));
        assertTrue(LegacyPrefsMigration.apply(target, state, startup, prepared, changes).isComplete());
        assertEquals("legacy", target.diskValues.get("setting"));
    }

    @Test public void failedCompletionMarkerRetainsPersistentBaselineAndTouchedKeys() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("setting", "private-old"));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        LegacyPrefsMigration.Changes changes = new LegacyPrefsMigration.Changes(state);
        target.registerOnSharedPreferenceChangeListener(changes);
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(NEW, 2L), Map.of(NEW, "<map><string name='setting'>legacy</string></map>"));
        state.failCompletedCommit = true;
        state.mutateOnFailedCommit = true;
        assertFalse(LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, changes).isComplete());
        assertEquals("legacy", target.getString("setting", ""));
        target.edit().putString("setting", "private-old").commit();
        target.unregisterOnSharedPreferenceChangeListener(changes);
        state.failCompletedCommit = false;
        // A new app process captures the new current value at startup; the persisted journal must still protect it.
        LegacyPrefsMigration.Changes restarted = new LegacyPrefsMigration.Changes(state);
        assertTrue(LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, restarted).isComplete());
        assertEquals("private-old", target.getString("setting", ""));
    }

    @Test public void malformedNewestSourceDoesNotSilentlyFallBackAndMarkSuccess() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("existing", true));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(OLD, 1L, NEW, 2L), Map.of(OLD, "<map/>", NEW, "<map>corrupt"));
        assertFalse(LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, new LegacyPrefsMigration.Changes(state)).isComplete());
        assertEquals(Map.of("existing", true), target.getAll());
    }

    @Test public void interruptedAndroidWriteUsesItsOwnBackupEvenWhenXmlLooksNewer() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of("setting", "private-old"));
        MemoryPreferences state = new MemoryPreferences(Map.of());
        LegacyPrefsMigration.Prepared prepared = prepared(Map.of(NEW, 9L, NEW + ".bak", 3L),
            Map.of(NEW, "<map>partially-written", NEW + ".bak", "<map><string name='setting'>last-complete</string></map>"));
        assertTrue(LegacyPrefsMigration.apply(target, state, target.getAll(), prepared, new LegacyPrefsMigration.Changes(state)).isComplete());
        assertEquals("last-complete", target.getString("setting", ""));
        assertEquals(NEW + ".bak", prepared.sourcePath());
    }

    @Test public void scanRejectsOtherApplicationsAndAmbiguousLatestTimestamps() throws Exception {
        MemoryPreferences target = new MemoryPreferences(Map.of());
        MemoryPreferences state = new MemoryPreferences(Map.of());
        LegacyPrefsMigration.Prepared prepared = LegacyPrefsMigration.prepare(command -> "2\t/data/misc/new/prefs/other.app/hyperceiler_prefs.xml\n" + LegacyPrefsMigration.SCAN_OK, backupDirectory());
        assertFalse(LegacyPrefsMigration.apply(target, state, Map.of(), prepared, new LegacyPrefsMigration.Changes(state)).isComplete());
        prepared = prepared(Map.of(OLD, 2L, NEW, 2L), Map.of(OLD, "<map/>", NEW, "<map><boolean name='different' value='true'/></map>"));
        assertFalse(LegacyPrefsMigration.apply(target, state, Map.of(), prepared, new LegacyPrefsMigration.Changes(state)).isComplete());
    }

    @Test public void generatedRootCommandsHaveValidShellSyntax() throws Exception {
        List<String> commands = new ArrayList<>();
        LegacyPrefsMigration.Prepared prepared = LegacyPrefsMigration.prepare(command -> {
            commands.add(command);
            return command.contains(LegacyPrefsMigration.SCAN_OK)
                ? "2\t" + NEW + "\n" + LegacyPrefsMigration.SCAN_OK
                : "<map/>\n" + LegacyPrefsMigration.READ_OK;
        }, backupDirectory());
        assertNull(prepared.failure());
        assertEquals(2, commands.size());
        for (String command : commands) {
            // Only parse, never execute the root scan on the developer/CI host.
            Process process = new ProcessBuilder("sh", "-c", command.replaceFirst("^sh -c ", "sh -n -c "))
                .redirectErrorStream(true).start();
            String diagnostic = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(diagnostic, 0, process.waitFor());
        }
    }

    private static LegacyPrefsMigration.Prepared prepared(Map<String, Long> candidates, Map<String, String> contents) throws Exception {
        return LegacyPrefsMigration.prepare(command -> {
            if (command.contains(LegacyPrefsMigration.SCAN_OK)) {
                StringBuilder result = new StringBuilder();
                candidates.forEach((path, time) -> result.append(time).append('\t').append(path).append('\n'));
                return result.append(LegacyPrefsMigration.SCAN_OK).toString();
            }
            for (String path : contents.keySet().stream().sorted((a, b) -> Integer.compare(b.length(), a.length())).toList()) {
                if (command.contains(path)) return contents.get(path) + "\n" + LegacyPrefsMigration.READ_OK;
            }
            return "cat: denied";
        }, backupDirectory());
    }

    private static File backupDirectory() throws Exception { return Files.createTempDirectory("hyperceiler-migration-test").toFile(); }

    private static final class MemoryPreferences implements SharedPreferences {
        private final Map<String, Object> values = new LinkedHashMap<>();
        private final List<OnSharedPreferenceChangeListener> listeners = new ArrayList<>();
        final Map<String, Object> diskValues = new LinkedHashMap<>();
        boolean failNextCommit, failCompletedCommit, mutateOnFailedCommit;
        MemoryPreferences(Map<String, ?> initial) { values.putAll(initial); diskValues.putAll(initial); }
        public Map<String, Object> getAll() { return new LinkedHashMap<>(values); }
        public String getString(String key, String fallback) { return (String)values.getOrDefault(key, fallback); }
        @SuppressWarnings("unchecked") public Set<String> getStringSet(String key, Set<String> fallback) { return (Set<String>) values.getOrDefault(key, fallback); }
        public int getInt(String key, int fallback) { return (Integer)values.getOrDefault(key, fallback); }
        public long getLong(String key, long fallback) { return (Long)values.getOrDefault(key, fallback); }
        public float getFloat(String key, float fallback) { return (Float)values.getOrDefault(key, fallback); }
        public boolean getBoolean(String key, boolean fallback) { return (Boolean)values.getOrDefault(key, fallback); }
        public boolean contains(String key) { return values.containsKey(key); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.add(l); }
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) { listeners.remove(l); }
        public Editor edit() { return new Editor() {
            final Map<String, Object> pending = new HashMap<>(); final Set<String> removed = new HashSet<>(); boolean clear;
            public Editor putString(String k, String v) { pending.put(k, v); return this; }
            public Editor putStringSet(String k, Set<String> v) { pending.put(k, new LinkedHashSet<>(v)); return this; }
            public Editor putInt(String k, int v) { pending.put(k, v); return this; }
            public Editor putLong(String k, long v) { pending.put(k, v); return this; }
            public Editor putFloat(String k, float v) { pending.put(k, v); return this; }
            public Editor putBoolean(String k, boolean v) { pending.put(k, v); return this; }
            public Editor remove(String k) { removed.add(k); return this; }
            public Editor clear() { clear = true; return this; }
            public boolean commit() {
                boolean failed = failNextCommit || (failCompletedCommit && Boolean.TRUE.equals(pending.get(LegacyPrefsMigration.COMPLETED_KEY)));
                failNextCommit = false;
                if (failed && !mutateOnFailedCommit) return false;
                if (clear) values.clear(); for (String key : removed) values.remove(key); values.putAll(pending);
                if (!failed) { diskValues.clear(); diskValues.putAll(values); }
                List<OnSharedPreferenceChangeListener> copy = new ArrayList<>(listeners);
                if (clear) for (OnSharedPreferenceChangeListener listener : copy) listener.onSharedPreferenceChanged(MemoryPreferences.this, null);
                Set<String> touched = new HashSet<>(removed); touched.addAll(pending.keySet());
                for (String key : touched) for (OnSharedPreferenceChangeListener listener : copy) listener.onSharedPreferenceChanged(MemoryPreferences.this, key);
                return !failed;
            }
            public void apply() { commit(); }
        }; }
    }
}
