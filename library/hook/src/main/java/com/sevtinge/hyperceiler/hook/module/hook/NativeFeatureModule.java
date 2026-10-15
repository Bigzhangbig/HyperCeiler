/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.module.hook;

import android.app.Application;
import android.content.Context;

import com.hchen.hooktool.AbsModule;
import com.hchen.hooktool.core.CoreTool;
import com.sevtinge.hyperceiler.hook.api.PackageContext;

import java.lang.reflect.Field;

import io.github.libxposed.api.XposedModuleInterface;

/**
 * Hosts the Android 14 feature modules on HookTool's native lifecycle and reflection tools.
 * The supplied lifecycle parameters are the real framework objects, never fabricated callbacks.
 */
public abstract class NativeFeatureModule extends AbsModule {
    protected static PackageContext.LoadPackageParam loadPackageParam;
    protected static final String TAG = "HyperCeiler";

    public final void onCreate(PackageContext.LoadPackageParam context) {
        loadPackageParam = context;
        // HookTool's onApplicationCreated is dispatched before attach. These existing features
        // require the original after-attach timing, so retain an explicit native after hook.
        if (hasApplicationAfterCallback()) {
            CoreTool.hookMethod(Application.class, "attach", Context.class, new NativeFeatureHook() {
                @Override
                public void after() {
                    try {
                        NativeFeatureModule.this.initApplicationAfter((Context) getArg(0));
                    } finally {
                        unhookSelf();
                    }
                }
            });
        }
        if (context.packageReadyParam != null) {
            handlePackageReady(context.packageReadyParam);
        } else if (context.systemServerStartingParam != null) {
            handleSystemServerStarting(context.systemServerStartingParam);
        } else {
            throw new IllegalArgumentException("A real framework lifecycle parameter is required");
        }
    }

    @Override
    protected final void onPackageReady(XposedModuleInterface.PackageReadyParam param) {
        init();
    }

    @Override
    protected final void onSystemServerStarting(XposedModuleInterface.SystemServerStartingParam param) {
        init();
    }

    protected abstract void init();

    protected void initApplicationAfter(Context context) {
    }

    private boolean hasApplicationAfterCallback() {
        for (Class<?> type = getClass(); type != NativeFeatureModule.class; type = type.getSuperclass()) {
            try {
                type.getDeclaredMethod("initApplicationAfter", Context.class);
                return true;
            } catch (NoSuchMethodException ignored) {
                // A feature may inherit its callback from a feature-specific intermediate class.
            }
        }
        return false;
    }

    public static Object getField(Object receiver, Field field) {
        return CoreTool.getField(field, receiver);
    }
}
