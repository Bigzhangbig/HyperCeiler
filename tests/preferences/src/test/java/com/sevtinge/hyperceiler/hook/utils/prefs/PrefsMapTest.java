package com.sevtinge.hyperceiler.hook.utils.prefs;

import org.junit.Test;

import java.util.Map;
import java.util.Set;

import static org.junit.Assert.*;

public class PrefsMapTest {
    @Test public void replacementClearsRemovedSettingsAndPreservesTypedAccess() {
        PrefsMap<String, Object> prefs = new PrefsMap<>();
        prefs.put("prefs_key_obsolete", true);
        prefs.replace(Map.of("prefs_key_allow_hook", true, "prefs_key_count", 4,
            "prefs_key_mode", "7", "prefs_key_label", "示例"));
        assertFalse(prefs.containsKey("prefs_key_obsolete"));
        assertTrue(prefs.getBoolean("allow_hook"));
        assertEquals(4, prefs.getInt("count", 0));
        assertEquals(7, prefs.getStringAsInt("mode", 0));
        assertEquals("示例", prefs.getString("label", ""));
        prefs.replace(Map.of());
        assertFalse(prefs.getBoolean("allow_hook"));
        assertEquals(99, prefs.getInt("count", 99));
    }

    @Test public void previouslyObservedSnapshotRemainsCompleteDuringReplacement() {
        PrefsMap<String, Object> prefs = new PrefsMap<>();
        prefs.replace(Map.of("a", 1, "b", 1));
        Set<Map.Entry<String, Object>> old = prefs.entrySet();
        prefs.replace(Map.of("a", 2, "b", 2));
        assertEquals(Set.of(Map.entry("a", 1), Map.entry("b", 1)), old);
        assertEquals(Map.of("a", 2, "b", 2), prefs);
    }

    @Test public void observerUpdatesKeepOtherKeysAndCanRemoveOneValue() {
        PrefsMap<String, Object> prefs = new PrefsMap<>();
        prefs.replace(Map.of("prefs_key_first", 1, "prefs_key_second", 2));
        assertEquals(1, prefs.put("prefs_key_first", 3));
        assertEquals(3, prefs.getInt("first", 0));
        assertEquals(2, prefs.getInt("second", 0));
        prefs.remove("prefs_key_first");
        assertEquals(0, prefs.getInt("first", 0));
        assertEquals(2, prefs.getInt("second", 0));
    }
}
