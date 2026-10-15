package com.sevtinge.hyperceiler.hook.module.base.tool;

import android.content.Context;

/** UI/storage helpers are outside these JVM bootstrap tests; unexpected calls must fail. */
public final class AppsTool {
    public static Context getProtectedContext(Context context) { throw new AssertionError("Unexpected UI storage call"); }
    public static void requestBackup(Context context) { throw new AssertionError("Unexpected backup request"); }
}
