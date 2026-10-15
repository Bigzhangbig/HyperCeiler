package com.sevtinge.hyperceiler.hook.utils.prefs;

import android.content.SharedPreferences;
import org.junit.Test;

import java.lang.reflect.Proxy;
import java.util.Map;

import static org.junit.Assert.*;

public class PrefsBootstrapTest {
    @Test public void failedAttachmentCannotReuseEnabledSnapshotAsSuccessfulBootstrap() {
        SharedPreferences valid = source(false);
        PrefsUtils.attachHookPreferences(valid, method -> true);
        assertTrue(PrefsUtils.mPrefsMap.getBoolean("allow_hook"));
        try {
            PrefsUtils.attachHookPreferences(source(true), method -> true);
            fail("A failed source must stop bootstrap even when the previous snapshot enabled hooks");
        } catch (IllegalStateException expected) {
            assertSame(valid, PrefsUtils.getHookPreferences());
            assertTrue("Last valid settings are retained for already running hooks",
                PrefsUtils.mPrefsMap.getBoolean("allow_hook"));
        }
    }

    @Test public void coldBootstrapFailureDoesNotTouchPreferenceDependentLoggerBeforeRetry() {
        PrefsUtils.mPrefsMap.replace(Map.of());
        com.sevtinge.hyperceiler.hook.utils.log.XposedLogUtils.errorCalls = 0;
        try {
            PrefsUtils.attachHookPreferences(source(true), method -> true);
            fail("The unavailable remote store must fail bootstrap");
        } catch (IllegalStateException expected) {
            assertEquals("Cold failure must use the independent framework logger", 0,
                com.sevtinge.hyperceiler.hook.utils.log.XposedLogUtils.errorCalls);
        }
        PrefsUtils.attachHookPreferences(source(false), method -> true);
        assertTrue(PrefsUtils.mPrefsMap.getBoolean("allow_hook"));
    }

    private static SharedPreferences source(boolean failReads) {
        return (SharedPreferences) Proxy.newProxyInstance(SharedPreferences.class.getClassLoader(),
            new Class<?>[]{SharedPreferences.class}, (proxy, method, args) -> switch (method.getName()) {
                case "getAll" -> {
                    if (failReads) throw new IllegalStateException("Framework cache unavailable");
                    yield Map.of("prefs_key_allow_hook", true);
                }
                case "registerOnSharedPreferenceChangeListener", "unregisterOnSharedPreferenceChangeListener" -> null;
                default -> throw new AssertionError("Unexpected preference operation: " + method.getName());
            });
    }
}
