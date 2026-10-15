/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

import android.app.Application;
import java.lang.reflect.Method;

/** Access to Android's current application; contains no framework-specific helper calls. */
public final class AppContext {
    private AppContext() {}
    public static Application currentApplication() {
        try {
            Class<?> activityThread = Class.forName("android.app.ActivityThread");
            Method currentApplication = activityThread.getDeclaredMethod("currentApplication");
            currentApplication.setAccessible(true);
            return (Application) currentApplication.invoke(null);
        } catch (ReflectiveOperationException error) {
            HookBridge.log(error);
            return null;
        }
    }
}
