/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook;

import android.util.Log;
import com.hchen.hooktool.ModuleConfig;
import com.hchen.hooktool.ModuleEntrance;
import com.sevtinge.hyperceiler.hook.api.HookBridge;
import com.sevtinge.hyperceiler.hook.api.PackageContext;
import com.sevtinge.hyperceiler.hook.utils.log.LogManager;
import com.sevtinge.hyperceiler.hook.utils.prefs.PrefsUtils;
import io.github.kyuubiran.ezxhelper.xposed.EzXposed;

/** API102 entry. Every feature hook executes through libxposed's native interceptor API. */
public final class ModernXposedInit extends ModuleEntrance {
    private static final String TAG = "HyperCeiler";
    private XposedInit backend;
    private ModuleLoadedParam process;
    private boolean initialized;

    @Override public void initModuleConfig() {
        ModuleConfig.setLogTag(TAG);
        ModuleConfig.setPrefsName(PrefsUtils.mPrefsName);
        ModuleConfig.setShowHookSuccessLog(false);
    }

    /** Feature static state and hook handles are not yet transferable across module reloads. */
    @Override public boolean isHotReloadingAllowed(String packageName) { return false; }

    @Override public void handleModuleLoaded(ModuleLoadedParam param) {
        process = param;
        HookBridge.initialize(this);
        EzXposed.initOnModuleLoaded(this, param);
        initializeBackend();
    }

    private synchronized boolean initializeBackend() {
        if (initialized) return true;
        try {
            PrefsUtils.attachHookPreferences(getRemotePreferences(PrefsUtils.mPrefsName), this::deoptimize);
            // Reading this class earlier would freeze its static log level before preferences exist.
            ModuleConfig.setLogLevel(LogManager.getLogLevel());
            if (!PrefsUtils.mPrefsMap.getBoolean("allow_hook")) return false;
            backend = new XposedInit();
            backend.initializeBackend(getModuleApplicationInfo().sourceDir, process.isSystemServer());
            initialized = true;
            log(Log.INFO, TAG, "Android 14 native API102 hooks initialized with framework remote preferences in "
                + process.getProcessName());
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot initialize the Android 14 native hook backend", error);
            return false;
        }
    }

    @Override public void handleSystemServerStarting(SystemServerStartingParam param) {
        EzXposed.initOnSystemServerStarting(param);
        PackageContext.LoadPackageParam context = new PackageContext.LoadPackageParam();
        context.packageName = "android";
        context.processName = "android";
        context.classLoader = param.getClassLoader();
        context.isFirstApplication = true;
        context.systemServerStartingParam = param;
        dispatch(context);
    }

    @Override public void handlePackageReady(PackageReadyParam param) {
        EzXposed.initOnPackageReady(param);
        PackageContext.LoadPackageParam context = new PackageContext.LoadPackageParam();
        context.packageName = param.getPackageName();
        context.processName = process.getProcessName();
        context.classLoader = param.getClassLoader();
        context.appInfo = param.getApplicationInfo();
        context.isFirstApplication = param.isFirstPackage();
        context.packageReadyParam = param;
        dispatch(context);
    }

    private void dispatch(PackageContext.LoadPackageParam context) {
        if (!initializeBackend()) return;
        try {
            backend.handleLoadPackage(context);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot initialize hooks for " + context.packageName, error);
        }
    }
}
