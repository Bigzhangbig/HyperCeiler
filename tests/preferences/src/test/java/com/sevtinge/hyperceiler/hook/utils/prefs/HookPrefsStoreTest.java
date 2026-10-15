package com.sevtinge.hyperceiler.hook.utils.prefs;

import android.content.SharedPreferences;

import org.junit.Test;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class HookPrefsStoreTest {
    @Test
    public void oldProviderNotificationBeforeRemoteUpdateReproducesStaleRedraw() {
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        remote.edit().putString("prefs_key_title", "old title").commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        map.replace(remote.getAll());
        List<String> redraws = new ArrayList<>();
        SharedPreferences.OnSharedPreferenceChangeListener oldCacheListener =
            (preferences, key) -> map.replace(preferences.getAll());
        remote.registerOnSharedPreferenceChangeListener(oldCacheListener);

        // Controlled old ordering: the app notifies its provider before remote commit/cache delivery.
        redraws.add(map.getString("title", "missing"));
        remote.edit().putString("prefs_key_title", "new title").commit();

        assertEquals("new title", map.getString("title", "missing"));
        assertEquals(List.of("old title"), redraws); // The old listener never requested another redraw.
    }

    @Test
    public void remoteEventReplacesEntireMapBeforeRequestingRedraw() {
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        remote.edit().putString("prefs_key_title", "old title").putInt("prefs_key_size", 10).commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        HookPrefsStore store = new HookPrefsStore(map, null);
        assertTrue(store.attach(remote));
        List<String> redraws = new ArrayList<>();
        assertTrue(store.observe((key, value) -> {
            assertEquals("new title", map.getString("title", "missing"));
            assertEquals(20, map.getInt("size", -1));
            if (key.equals("prefs_key_title")) redraws.add(map.getString("title", "missing"));
        }));

        remote.edit().putString("prefs_key_title", "new title").putInt("prefs_key_size", 20).commit();

        assertEquals(List.of("new title"), redraws);
        assertEquals(1, remote.listenerCount());
    }

    @Test
    public void completeClearAndPutBatchReportsOnlyActualChangesAndDeletedKeyType() {
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        remote.edit().putInt("changed", 1).putString("same", "unchanged")
            .putBoolean("deleted", true).commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        HookPrefsStore store = new HookPrefsStore(map, null);
        assertTrue(store.attach(remote));
        Map<String, Object> events = new LinkedHashMap<>();
        List<String> keys = new ArrayList<>();
        store.observe((key, value) -> {
            keys.add(key);
            events.put(key, value);
            assertFalse(map.containsKey("deleted"));
            assertEquals(2, map.get("changed"));
        });

        remote.edit().clear().putInt("changed", 2).putString("same", "unchanged").commit();

        assertEquals(Set.of("changed", "deleted"), events.keySet());
        assertEquals(2, keys.size()); // clear plus per-key callbacks must not duplicate the diff.
        assertEquals(Boolean.TRUE, events.get("deleted"));
        assertEquals("boolean", HookPrefsStore.typeOf(events.get("deleted")));
    }

    @Test
    public void removeAndClearPreserveOldTypeForEachDeletedKey() {
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        remote.edit().putString("text", "value").putStringSet("set", Set.of("a"))
            .putInt("integer", 2).putLong("long", 3L).putFloat("float", 1.5f)
            .putBoolean("boolean", false).commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        HookPrefsStore store = new HookPrefsStore(map, null);
        store.attach(remote);
        Map<String, String> types = new LinkedHashMap<>();
        store.observe((key, value) -> {
            assertFalse(map.containsKey(key));
            types.put(key, HookPrefsStore.typeOf(value));
        });

        remote.edit().remove("text").commit();
        remote.edit().clear().commit();

        assertTrue(map.isEmpty());
        assertEquals(Map.of("text", "string", "set", "stringset", "integer", "integer",
            "long", "long", "float", "float", "boolean", "boolean"), types);
    }

    @Test
    public void multipleObserversUseOneSourceListenerAndOneFailureDoesNotBlockOthers() {
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        PrefsMap<String, Object> map = new PrefsMap<>();
        List<RuntimeException> errors = new ArrayList<>();
        HookPrefsStore store = new HookPrefsStore(map, errors::add);
        store.attach(remote);
        List<String> events = new ArrayList<>();
        store.observe((key, value) -> { throw new IllegalStateException("observer failed"); });
        store.observe((key, value) -> events.add(key + ":" + map.get(key)));

        remote.edit().putInt("size", 8).commit();

        assertEquals(List.of("size:8"), events);
        assertEquals(1, errors.size());
        assertEquals(1, remote.listenerCount());
    }

    @Test
    public void switchingSourceUnregistersOldListenerAndRejectsDelayedOldEvents() {
        ControlledSource old = new ControlledSource();
        ControlledSource next = new ControlledSource();
        old.data.edit().putInt("value", 1).commit();
        next.data.edit().putInt("value", 2).commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        HookPrefsStore store = new HookPrefsStore(map, null);
        store.attach(old.preferences);
        SharedPreferences.OnSharedPreferenceChangeListener stale = old.lastListener;
        List<Integer> events = new ArrayList<>();
        store.observe((key, value) -> events.add((Integer) map.get("value")));

        assertTrue(store.attach(next.preferences));
        assertEquals(0, old.data.listenerCount());
        assertEquals(1, next.data.listenerCount());
        old.data.edit().putInt("value", 99).commit();
        stale.onSharedPreferenceChanged(old.preferences, "value");
        assertEquals(2, map.get("value"));
        assertEquals(List.of(2), events);
        store.attach(next.preferences);
        assertEquals(1, next.data.listenerCount());
    }

    @Test
    public void failedReadKeepsLastValidSnapshotAndRefreshRetriesWithoutLostNotification() {
        ControlledSource remote = new ControlledSource();
        remote.data.edit().putInt("value", 1).commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        List<RuntimeException> errors = new ArrayList<>();
        HookPrefsStore store = new HookPrefsStore(map, errors::add);
        store.attach(remote.preferences);
        List<Integer> events = new ArrayList<>();
        store.observe((key, value) -> events.add((Integer) map.get(key)));
        remote.failRead = true;

        remote.data.edit().putInt("value", 2).commit();
        assertEquals(1, map.get("value"));
        assertTrue(events.isEmpty());
        assertEquals(1, errors.size());

        remote.failRead = false;
        assertTrue(store.refresh());
        assertEquals(List.of(2), events);
        assertEquals(2, map.get("value"));
    }

    @Test
    public void failedReplacementReadKeepsOldDataAndNewSourceCanRecover() {
        MirrorFakeSharedPreferences first = new MirrorFakeSharedPreferences();
        first.edit().putString("value", "first").commit();
        ControlledSource replacement = new ControlledSource();
        replacement.data.edit().putString("value", "second").commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        HookPrefsStore store = new HookPrefsStore(map, null);
        store.attach(first);
        replacement.failRead = true;

        assertFalse(store.attach(replacement.preferences));
        assertEquals("first", map.get("value"));
        assertEquals(0, first.listenerCount());
        assertEquals(1, replacement.data.listenerCount());
        replacement.failRead = false;
        assertTrue(store.refresh());
        assertEquals("second", map.get("value"));
    }

    @Test
    public void snapshotCopiesMutableSetsAndPreservesAllSupportedTypes() {
        MirrorFakeSharedPreferences remote = new MirrorFakeSharedPreferences();
        Set<String> sourceSet = new LinkedHashSet<>(Set.of("a", "b"));
        remote.edit().putStringSet("set", sourceSet).putBoolean("bool", true)
            .putInt("int", 1).putLong("long", 3_000_000_000L).putFloat("float", 0.5f)
            .putString("string", "中文/a").commit();
        PrefsMap<String, Object> map = new PrefsMap<>();
        HookPrefsStore store = new HookPrefsStore(map, null);
        assertTrue(store.attach(remote));
        assertEquals(remote.getAll(), map);
        sourceSet.add("outside mutation");
        assertEquals(Set.of("a", "b"), map.get("set"));
        assertEquals(Long.class, map.get("long").getClass());
        assertEquals(Float.class, map.get("float").getClass());
    }

    @Test
    public void observeBeforeAttachReturnsFalseAndDoesNotKeepUnregisteredObserver() {
        HookPrefsStore store = new HookPrefsStore(new PrefsMap<>(), null);
        List<String> keys = new ArrayList<>();
        assertFalse(store.observe((key, value) -> keys.add(key)));
        MirrorFakeSharedPreferences source = new MirrorFakeSharedPreferences();
        store.attach(source);
        source.edit().putInt("value", 1).commit();
        assertTrue(keys.isEmpty());
    }

    /** Delegates real preference behavior to the shared fake; controls read failures and late events. */
    private static final class ControlledSource {
        final MirrorFakeSharedPreferences data = new MirrorFakeSharedPreferences();
        final Map<SharedPreferences.OnSharedPreferenceChangeListener,
            SharedPreferences.OnSharedPreferenceChangeListener> wrappers = new IdentityHashMap<>();
        final SharedPreferences preferences;
        SharedPreferences.OnSharedPreferenceChangeListener lastListener;
        boolean failRead;

        ControlledSource() {
            preferences = (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
                new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> {
                    switch (method.getName()) {
                        case "getAll":
                            if (failRead) throw new IllegalStateException("remote cache read failed");
                            break;
                        case "registerOnSharedPreferenceChangeListener":
                            lastListener = (SharedPreferences.OnSharedPreferenceChangeListener) args[0];
                            SharedPreferences.OnSharedPreferenceChangeListener registered = lastListener;
                            SharedPreferences.OnSharedPreferenceChangeListener wrapper =
                                (ignored, key) -> registered.onSharedPreferenceChanged((SharedPreferences) proxy, key);
                            wrappers.put(registered, wrapper);
                            data.registerOnSharedPreferenceChangeListener(wrapper);
                            return null;
                        case "unregisterOnSharedPreferenceChangeListener":
                            data.unregisterOnSharedPreferenceChangeListener(wrappers.remove(args[0]));
                            return null;
                    }
                    try {
                        return method.invoke(data, args);
                    } catch (InvocationTargetException error) {
                        throw error.getCause();
                    }
                });
        }
    }
}
