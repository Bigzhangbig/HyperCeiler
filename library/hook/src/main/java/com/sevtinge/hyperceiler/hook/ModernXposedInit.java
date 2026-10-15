/*
 * This file is part of HyperCeiler, licensed under the GNU AGPL version 3 or later.
 */
package com.sevtinge.hyperceiler.hook;

import android.content.pm.ApplicationInfo;
import android.util.Log;

import com.sevtinge.hyperceiler.hook.utils.prefs.PrefsUtils;

import java.lang.reflect.Constructor;
import java.util.concurrent.CopyOnWriteArraySet;

import de.robv.android.xposed.IXposedHookZygoteInit.StartupParam;
import de.robv.android.xposed.callbacks.XC_LoadPackage;
import io.github.libxposed.api.XposedModule;

/**
 * API101 entry and official preference transport for the Android 14 hook backend.
 * Target 101 intentionally retains LSPosed's documented legacy-hook compatibility.
 * No module code is loaded into zygote and no NSP files are read.
 */
public final class ModernXposedInit extends XposedModule {
    private static final String TAG = "HyperCeiler";
    private XposedInit backend;
    private ModuleLoadedParam process;
    private boolean initialized;

    @Override
    public void onModuleLoaded(ModuleLoadedParam param) {
        process = param;
        initializeBackend();
    }

    private synchronized boolean initializeBackend() {
        if (initialized) return true;
        try {
            PrefsUtils.attachHookPreferences(getRemotePreferences(PrefsUtils.mPrefsName), this::deoptimize);
            if (!PrefsUtils.mPrefsMap.getBoolean("allow_hook")) return false;
            backend = new XposedInit();
            // These are legacy helper data objects, not framework lifecycle entry points.
            Constructor<StartupParam> constructor = StartupParam.class.getDeclaredConstructor();
            constructor.setAccessible(true);
            StartupParam startup = constructor.newInstance();
            startup.modulePath = getModuleApplicationInfo().sourceDir;
            startup.startsSystemServer = process.isSystemServer();
            backend.initZygote(startup);
            initialized = true;
            log(Log.INFO, TAG, "Android 14 hooks initialized with framework remote preferences in "
                + process.getProcessName());
            return true;
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot initialize the Android 14 hook backend", error);
            return false;
        }
    }

    @Override
    public void onSystemServerStarting(SystemServerStartingParam param) {
        dispatch("android", "android", param.getClassLoader(), null, true);
    }

    @Override
    public void onPackageReady(PackageReadyParam param) {
        dispatch(param.getPackageName(), process.getProcessName(), param.getClassLoader(),
            param.getApplicationInfo(), param.isFirstPackage());
    }

    private void dispatch(String packageName, String processName, ClassLoader loader,
                          ApplicationInfo appInfo, boolean firstPackage) {
        if (!initializeBackend()) return;
        try {
            XC_LoadPackage.LoadPackageParam legacy = newLoadPackageParam();
            legacy.packageName = packageName;
            legacy.processName = processName;
            legacy.classLoader = loader;
            legacy.appInfo = appInfo;
            legacy.isFirstApplication = firstPackage;
            backend.handleLoadPackage(legacy);
        } catch (Throwable error) {
            log(Log.ERROR, TAG, "Cannot initialize hooks for " + packageName, error);
        }
    }

    private static XC_LoadPackage.LoadPackageParam newLoadPackageParam() throws ReflectiveOperationException {
        // The API82 compile-time stubs omit the runtime callback-set constructor.
        // LSPosed 2.2 uses CopyOnWriteArraySet; other API101 implementations use a sorted set.
        for (Constructor<?> constructor : XC_LoadPackage.LoadPackageParam.class.getDeclaredConstructors()) {
            Class<?>[] types = constructor.getParameterTypes();
            constructor.setAccessible(true);
            if (types.length == 0) return (XC_LoadPackage.LoadPackageParam) constructor.newInstance();
            if (types.length == 1) {
                Object callbacks;
                if (types[0].isAssignableFrom(CopyOnWriteArraySet.class)) {
                    callbacks = new CopyOnWriteArraySet<>();
                } else if (types[0].getSimpleName().equals("CopyOnWriteSortedSet")) {
                    Constructor<?> emptySet = types[0].getDeclaredConstructor();
                    emptySet.setAccessible(true);
                    callbacks = emptySet.newInstance();
                } else continue;
                return (XC_LoadPackage.LoadPackageParam) constructor.newInstance(callbacks);
            }
        }
        throw new NoSuchMethodException("Unsupported legacy LoadPackageParam constructor");
    }
}
