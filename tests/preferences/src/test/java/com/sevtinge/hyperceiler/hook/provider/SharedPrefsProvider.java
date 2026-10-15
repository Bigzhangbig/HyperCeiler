package com.sevtinge.hyperceiler.hook.provider;

/** Authority-only helper; the Android ContentProvider is not run on the JVM. */
public final class SharedPrefsProvider {
    public static final String AUTHORITY = "com.sevtinge.hyperceiler.provider.sharedprefs";
}
