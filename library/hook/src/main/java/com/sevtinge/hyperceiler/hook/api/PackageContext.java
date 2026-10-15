/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

import android.content.pm.ApplicationInfo;
import io.github.libxposed.api.XposedModuleInterface;

/** Internal feature context. This is module data, not a framework lifecycle callback. */
public final class PackageContext {
    private PackageContext() {}
    public static final class LoadPackageParam {
        public String packageName;
        public String processName;
        public ClassLoader classLoader;
        public ApplicationInfo appInfo;
        public boolean isFirstApplication;
        public XposedModuleInterface.PackageReadyParam packageReadyParam;
        public XposedModuleInterface.SystemServerStartingParam systemServerStartingParam;
    }
}
