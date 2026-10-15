package com.sevtinge.hyperceiler.hook.utils.prefs;

import android.content.SharedPreferences;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** In-memory Android preferences fake; listeners are weak as in Android. */
final class MirrorFakeSharedPreferences implements SharedPreferences {
    private final Map<String, Object> values = new LinkedHashMap<>();
    private final List<WeakReference<OnSharedPreferenceChangeListener>> listeners = new ArrayList<>();
    boolean failCommit;
    RuntimeException commitException;
    Runnable beforeCommit;
    int commitCount;
    int activeCommits;
    int maximumConcurrentCommits;

    @Override
    public synchronized Map<String, ?> getAll() {
        return new LinkedHashMap<>(values);
    }

    @Override
    public synchronized String getString(String key, String defValue) {
        return (String) values.getOrDefault(key, defValue);
    }

    @SuppressWarnings("unchecked")
    @Override
    public synchronized Set<String> getStringSet(String key, Set<String> defValues) {
        return (Set<String>) values.getOrDefault(key, defValues);
    }

    @Override
    public synchronized int getInt(String key, int defValue) {
        return (Integer) values.getOrDefault(key, defValue);
    }

    @Override
    public synchronized long getLong(String key, long defValue) {
        return (Long) values.getOrDefault(key, defValue);
    }

    @Override
    public synchronized float getFloat(String key, float defValue) {
        return (Float) values.getOrDefault(key, defValue);
    }

    @Override
    public synchronized boolean getBoolean(String key, boolean defValue) {
        return (Boolean) values.getOrDefault(key, defValue);
    }

    @Override
    public synchronized boolean contains(String key) {
        return values.containsKey(key);
    }

    @Override
    public Editor edit() {
        return new FakeEditor();
    }

    @Override
    public synchronized void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.add(new WeakReference<>(listener));
    }

    @Override
    public synchronized void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener listener) {
        listeners.removeIf(reference -> reference.get() == null || reference.get() == listener);
    }

    synchronized int listenerCount() {
        return listeners.size();
    }

    private final class FakeEditor implements Editor {
        private final Map<String, Object> changes = new LinkedHashMap<>();
        private boolean clear;

        @Override
        public Editor putString(String key, String value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putStringSet(String key, Set<String> value) {
            // Keep the input reference to detect a mirror accidentally sharing a mutable set.
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putInt(String key, int value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putLong(String key, long value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putFloat(String key, float value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor putBoolean(String key, boolean value) {
            changes.put(key, value);
            return this;
        }

        @Override
        public Editor remove(String key) {
            changes.put(key, null);
            return this;
        }

        @Override
        public Editor clear() {
            clear = true;
            return this;
        }

        @Override
        public boolean commit() {
            Runnable hook;
            synchronized (MirrorFakeSharedPreferences.this) {
                commitCount++;
                activeCommits++;
                maximumConcurrentCommits = Math.max(maximumConcurrentCommits, activeCommits);
                hook = beforeCommit;
            }
            try {
                if (hook != null) hook.run();
                List<OnSharedPreferenceChangeListener> callbacks = new ArrayList<>();
                Set<String> changedKeys = new LinkedHashSet<>();
                synchronized (MirrorFakeSharedPreferences.this) {
                    if (commitException != null) throw commitException;
                    if (failCommit) return false;
                    if (clear && !values.isEmpty()) {
                        values.clear();
                        changedKeys.add(null); // Android R+ clear notification.
                    }
                    changes.forEach((key, value) -> {
                        Object old = values.get(key);
                        if (value == null) values.remove(key);
                        else values.put(key, value);
                        if (!Objects.equals(old, value)) changedKeys.add(key);
                    });
                    listeners.removeIf(reference -> reference.get() == null);
                    for (WeakReference<OnSharedPreferenceChangeListener> reference : listeners) {
                        OnSharedPreferenceChangeListener callback = reference.get();
                        if (callback != null) callbacks.add(callback);
                    }
                }
                for (String key : changedKeys) {
                    for (OnSharedPreferenceChangeListener callback : callbacks) {
                        callback.onSharedPreferenceChanged(MirrorFakeSharedPreferences.this, key);
                    }
                }
                return true;
            } finally {
                synchronized (MirrorFakeSharedPreferences.this) {
                    activeCommits--;
                }
            }
        }

        @Override
        public void apply() {
            commit();
        }
    }
}
