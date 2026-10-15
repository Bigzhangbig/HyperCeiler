package com.sevtinge.hyperceiler.hook.utils.log;

/** Error logging is isolated; source reads and bootstrap decisions use actual production code. */
public final class XposedLogUtils {
    public static void logE(String tag, Throwable error) { }
    public static void logI(String tag, String message) { }
}
