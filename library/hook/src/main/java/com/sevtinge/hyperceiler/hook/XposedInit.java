/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2025 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.hook;

import static com.sevtinge.hyperceiler.hook.module.base.tool.AppsTool.getPackageVersionCode;
import static com.sevtinge.hyperceiler.hook.module.base.tool.AppsTool.getPackageVersionName;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.MiDeviceAppUtilsKt.isPad;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.getAndroidVersion;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.getHyperOSVersion;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.isAndroidVersion;
import static com.sevtinge.hyperceiler.hook.utils.devicesdk.SystemSDKKt.isHyperOSVersion;
import static com.sevtinge.hyperceiler.hook.utils.log.LogManager.logLevelDesc;
import static com.sevtinge.hyperceiler.hook.utils.log.XposedLogUtils.logE;
import static com.sevtinge.hyperceiler.hook.utils.log.XposedLogUtils.logI;
import static com.sevtinge.hyperceiler.hook.utils.prefs.PrefsUtils.mPrefsMap;

import com.hchen.hooktool.ModuleData;
import com.hchen.hooktool.utils.ResInjectTool;
import com.sevtinge.hyperceiler.hook.api.HookBridge;
import com.sevtinge.hyperceiler.hook.api.PackageContext;
import com.sevtinge.hyperceiler.hook.api.PackageHook;
import com.sevtinge.hyperceiler.hook.module.app.VariousThirdApps;
import com.sevtinge.hyperceiler.hook.module.base.BaseModule;
import com.sevtinge.hyperceiler.hook.module.base.tool.ResourcesTool;
import com.sevtinge.hyperceiler.hook.module.skip.SystemFrameworkForCorePatch;
import com.sevtinge.hyperceiler.hook.safe.CrashHook;
import com.sevtinge.hyperceiler.hook.safe.RescuePartyPlus;
import com.sevtinge.hyperceiler.hook.safe.SafeMode;
import com.sevtinge.hyperceiler.module.base.DataBase;

import java.lang.reflect.InvocationTargetException;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.BiConsumer;

import io.github.kyuubiran.ezxhelper.android.logging.Logger;
import io.github.kyuubiran.ezxhelper.core.EzXReflection;
import io.github.kyuubiran.ezxhelper.xposed.EzXposed;

public class XposedInit implements PackageHook {
    private static final String TAG = "HyperCeiler";
    public static String mModulePath = null;
    public static ResourcesTool mResHook;

    // public static XmlTool mXmlTool;
    public final VariousThirdApps mVariousThirdApps = new VariousThirdApps();

    public void initializeBackend(String modulePath, boolean systemServer) {
        // The modern entry has already attached preferences and initialized both native helpers.
        if (!mPrefsMap.getBoolean("allow_hook")) return;

        EzXposed.initModuleResources();
        Logger.INSTANCE.setTag(TAG);

        mModulePath = modulePath;
        if (!mPrefsMap.getBoolean("module_settings_reshook_new")) {
            mResHook = ResourcesTool.getInstance(modulePath);
        }
    }

    @Override
    public void handleLoadPackage(PackageContext.LoadPackageParam lpparam) throws Throwable {
        String packageName = lpparam.packageName;

        if (SafeMode.isInSafeModeOnHook(packageName) || SafeMode.isInSafeModeByProp(packageName)) {
            logI(packageName, "Entry safe mode");
            return;
        }

        if (mPrefsMap.getBoolean("allow_hook")) {

            // Shared data for native reflection helpers follows the current target's loader.
            EzXReflection.init(lpparam.classLoader);
            ModuleData.setClassLoader(lpparam.classLoader);

            // load CorePatch
            if (mPrefsMap.getBoolean("system_framework_core_patch_enable")) {
                new SystemFrameworkForCorePatch().handleLoadPackage(lpparam);
            }

            // load Module hook apps
            if (Objects.equals(packageName, "android"))
                logI(packageName, "androidVersion = " + getAndroidVersion() + ", hyperosVersion = " + getHyperOSVersion());
            else
                logI(packageName, "versionName = " + getPackageVersionName(lpparam) + ", versionCode = " + getPackageVersionCode(lpparam));

            // load ResourcesTool
            if (mPrefsMap.getBoolean("module_settings_reshook_new")) {
                ResInjectTool.injectModuleRes();
            }

            invokeInit(lpparam);
            androidCrashEventHook(lpparam);
        }
    }

    private void invokeInit(PackageContext.LoadPackageParam lpparam) {
        String mPkgName = lpparam.packageName;
        if (mPkgName == null) return;

        HashMap<String, DataBase> dataMap = DataBase.get();
        if (dataMap.values().stream().noneMatch(dataBase -> dataBase.mTargetPackage.equals(mPkgName))) {
            mVariousThirdApps.init(lpparam);
            return;
        }

        dataMap.forEach(new BiConsumer<>() {
            @Override
            public void accept(String s, DataBase dataBase) {
                if (!mPkgName.equals(dataBase.mTargetPackage))
                    return;
                if (!(dataBase.mTargetSdk == -1) && !isAndroidVersion(dataBase.mTargetSdk))
                    return;
                if (!(dataBase.mTargetOSVersion == -1F) && !(isHyperOSVersion(dataBase.mTargetOSVersion)))
                    return;
                if ((dataBase.isPad == 1 && !isPad()) || (dataBase.isPad == 2 && isPad()))
                    return;

                try {
                    Class<?> clazz = Objects.requireNonNull(getClass().getClassLoader()).loadClass(s);
                    BaseModule module = (BaseModule) clazz.getDeclaredConstructor().newInstance();
                    module.init(lpparam);
                } catch (ClassNotFoundException | NoSuchMethodException | IllegalAccessException |
                         InstantiationException | InvocationTargetException e) {
                    logE(TAG, e);
                }
            }
        });
    }

    private void androidCrashEventHook(PackageContext.LoadPackageParam lpparam) {
        if ("android".equals(lpparam.packageName)) {
            HookBridge.log("[HyperCeiler][I]: Log level is " + logLevelDesc());
            try {
                new CrashHook(lpparam);
                RescuePartyPlus rescuePartyPlus = RescuePartyPlus.INSTANCE;
                rescuePartyPlus.setHandler(SafeMode.INSTANCE);
                rescuePartyPlus.onCreate(lpparam);
            } catch (Exception e) {
                logE(TAG, e);
            }
        }
    }

}
