/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * HyperCeiler is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.sevtinge.hyperceiler.hook.utils.prefs;

import android.content.SharedPreferences;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Publishes the app's canonical preferences to a replaceable framework preferences group.
 * The canonical preferences are never modified by this class. Publication is synchronous:
 * all snapshots and commits share one lock, including changes arriving during a reconnect.
 */
public final class RemotePrefsMirror implements AutoCloseable {
    public interface Listener {
        /** The complete snapshot was committed successfully. Called on the publishing thread. */
        void onSyncSuccess();

        /** Publication failed; the canonical values remain available for a later retry. */
        void onSyncFailure(Throwable error);
    }

    private final SharedPreferences local;
    private final Listener listener;
    // Android keeps preference listeners weakly: this reference must live as long as the mirror.
    private final SharedPreferences.OnSharedPreferenceChangeListener localListener;
    private SharedPreferences remote;
    private Map<String, Object> lastSuccessfulSnapshot;
    private boolean closed;

    public RemotePrefsMirror(SharedPreferences local) {
        this(local, null);
    }

    public RemotePrefsMirror(SharedPreferences local, Listener listener) {
        this.local = Objects.requireNonNull(local, "local");
        this.listener = listener;
        localListener = (preferences, key) -> refresh();
        local.registerOnSharedPreferenceChangeListener(localListener);
    }

    /**
     * Binds the current remote group and immediately publishes the latest full snapshot.
     * A failed publication keeps this binding, so the next local change or refresh retries it.
     * Returns false while closed or when the remote commit fails.
     */
    public synchronized boolean connect(SharedPreferences remote) {
        Objects.requireNonNull(remote, "remote");
        if (remote == local) throw new IllegalArgumentException("Remote preferences must differ from canonical preferences");
        if (closed) return false;
        this.remote = remote;
        lastSuccessfulSnapshot = null; // Each explicit bind/reconnect must publish at least once.
        return publish();
    }

    /** An old service's delayed disconnect must not discard a newer connection. */
    public synchronized void disconnect(SharedPreferences remote) {
        if (this.remote == remote) {
            this.remote = null;
            lastSuccessfulSnapshot = null;
        }
    }

    /** Retries once with current local values; does not schedule automatic retries. */
    public synchronized boolean refresh() {
        return !closed && remote != null && publish();
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        remote = null;
        lastSuccessfulSnapshot = null;
        local.unregisterOnSharedPreferenceChangeListener(localListener);
    }

    /** Caller holds this mirror's lock before taking the snapshot and until commit completes. */
    private boolean publish() {
        try {
            Map<String, Object> snapshot = snapshot();
            // Android notifies once per key even when one editor committed a whole import.
            if (snapshot.equals(lastSuccessfulSnapshot)) return true;
            SharedPreferences.Editor editor = remote.edit().clear();
            for (Map.Entry<String, Object> entry : snapshot.entrySet()) {
                String key = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof Boolean) editor.putBoolean(key, (Boolean) value);
                else if (value instanceof Integer) editor.putInt(key, (Integer) value);
                else if (value instanceof Long) editor.putLong(key, (Long) value);
                else if (value instanceof Float) editor.putFloat(key, (Float) value);
                else if (value instanceof String) editor.putString(key, (String) value);
                else {
                    @SuppressWarnings("unchecked")
                    Set<String> strings = (Set<String>) value;
                    // Keep the comparison cache private even if an editor retains its input set.
                    editor.putStringSet(key, new LinkedHashSet<>(strings));
                }
            }
            if (!editor.commit()) throw new IllegalStateException("Remote preferences commit returned false");
            lastSuccessfulSnapshot = snapshot;
        } catch (RuntimeException error) {
            lastSuccessfulSnapshot = null;
            reportFailure(error);
            return false;
        }
        if (listener != null) {
            try {
                listener.onSyncSuccess();
            } catch (RuntimeException ignored) {
                // A status/log callback must not make a successful local edit fail.
            }
        }
        return true;
    }

    private Map<String, Object> snapshot() {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : local.getAll().entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) {
                Set<String> strings = new LinkedHashSet<>();
                for (Object item : (Set<?>) value) {
                    if (!(item instanceof String)) {
                        throw new IllegalArgumentException("Non-string preference set member for " + entry.getKey());
                    }
                    strings.add((String) item);
                }
                value = strings;
            } else if (!(value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Float || value instanceof String)) {
                throw new IllegalArgumentException("Unsupported preference type for " + entry.getKey());
            }
            snapshot.put(entry.getKey(), value);
        }
        return snapshot;
    }

    private void reportFailure(RuntimeException error) {
        if (listener == null) return;
        try {
            listener.onSyncFailure(error);
        } catch (RuntimeException ignored) {
            // Publishing failures and logging failures must not discard canonical local changes.
        }
    }
}
