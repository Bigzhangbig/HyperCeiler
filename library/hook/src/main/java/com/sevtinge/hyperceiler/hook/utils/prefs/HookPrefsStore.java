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

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

/** One framework listener updates the complete hook snapshot before notifying any observers. */
public final class HookPrefsStore {
    public interface Listener {
        /** valueForType is the new value, or the previous value when the key was removed. */
        void onChanged(String key, Object valueForType);
    }

    private final PrefsMap<String, Object> map;
    private final Consumer<RuntimeException> errors;
    private final Set<Listener> observers = new LinkedHashSet<>();
    private Map<String, Object> snapshot = Collections.emptyMap();
    private SharedPreferences source;
    // The framework may keep listeners weakly. This must remain strongly owned by the store.
    private SharedPreferences.OnSharedPreferenceChangeListener sourceListener;
    private long generation;

    public HookPrefsStore(PrefsMap<String, Object> map, Consumer<RuntimeException> errors) {
        this.map = Objects.requireNonNull(map, "map");
        this.errors = errors;
    }

    /** Registers before reading to avoid missing an update during initial attachment. */
    public synchronized boolean attach(SharedPreferences next) {
        Objects.requireNonNull(next, "source");
        if (source == next) return refresh();
        if (source != null && sourceListener != null) {
            try {
                source.unregisterOnSharedPreferenceChangeListener(sourceListener);
            } catch (RuntimeException error) {
                report(error); // The generation check also rejects a listener that could not detach.
            }
        }
        source = next;
        long attachedGeneration = ++generation;
        sourceListener = (preferences, ignoredKey) -> sourceChanged(next, attachedGeneration);
        try {
            next.registerOnSharedPreferenceChangeListener(sourceListener);
        } catch (RuntimeException error) {
            source = null;
            sourceListener = null;
            report(error);
            return false;
        }
        return refresh();
    }

    /** False means no remote source is attached; callers may then use their provider fallback. */
    public synchronized boolean observe(Listener listener) {
        Objects.requireNonNull(listener, "listener");
        if (source == null) return false;
        observers.add(listener);
        return true;
    }

    /** A failed read never clears the last valid map or advances the diff baseline. */
    public synchronized boolean refresh() {
        if (source == null) return false;
        Map<String, Object> next;
        try {
            next = copySnapshot(source.getAll());
        } catch (RuntimeException error) {
            report(error);
            return false;
        }
        Map<String, Object> previous = snapshot;
        map.replace(next);
        snapshot = next;

        Set<String> keys = new LinkedHashSet<>(previous.keySet());
        keys.addAll(next.keySet());
        List<Listener> listeners = new ArrayList<>(observers);
        for (String key : keys) {
            if (Objects.equals(previous.get(key), next.get(key))) continue;
            Object valueForType = next.containsKey(key) ? next.get(key) : previous.get(key);
            for (Listener listener : listeners) {
                try {
                    listener.onChanged(key, valueForType);
                } catch (RuntimeException error) {
                    report(error);
                }
            }
        }
        return true;
    }

    /** Stable URI type components; callers retain the previous value's type on deletion. */
    public static String typeOf(Object value) {
        if (value instanceof String) return "string";
        if (value instanceof Set<?>) return "stringset";
        if (value instanceof Integer) return "integer";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Long) return "long";
        if (value instanceof Float) return "float";
        return "";
    }

    private synchronized void sourceChanged(SharedPreferences expected, long expectedGeneration) {
        if (source == expected && generation == expectedGeneration) refresh();
    }

    private static Map<String, Object> copySnapshot(Map<String, ?> values) {
        Map<String, Object> copied = new LinkedHashMap<>();
        for (Map.Entry<String, ?> entry : values.entrySet()) {
            Object value = entry.getValue();
            if (value instanceof Set<?>) {
                Set<String> strings = new LinkedHashSet<>();
                for (Object item : (Set<?>) value) {
                    if (!(item instanceof String)) throw new IllegalArgumentException("Non-string preference set member");
                    strings.add((String) item);
                }
                value = Collections.unmodifiableSet(strings);
            } else if (!(value instanceof String || value instanceof Integer || value instanceof Boolean
                || value instanceof Long || value instanceof Float)) {
                throw new IllegalArgumentException("Unsupported preference snapshot value");
            }
            copied.put(entry.getKey(), value);
        }
        return Collections.unmodifiableMap(copied);
    }

    private void report(RuntimeException error) {
        if (errors == null) return;
        try {
            errors.accept(error);
        } catch (RuntimeException ignored) {
            // Logging one failure must not hide another observer or discard a valid snapshot.
        }
    }
}
