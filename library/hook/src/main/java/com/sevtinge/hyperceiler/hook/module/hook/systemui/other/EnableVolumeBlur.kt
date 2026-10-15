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
package com.sevtinge.hyperceiler.hook.module.hook.systemui.other

import com.sevtinge.hyperceiler.hook.module.base.BaseHook
import com.sevtinge.hyperceiler.hook.utils.getValueByField
import com.sevtinge.hyperceiler.hook.api.HookCallback
import com.sevtinge.hyperceiler.hook.api.HookBridge
import com.sevtinge.hyperceiler.hook.api.ReflectionHelper

class EnableVolumeBlur : BaseHook() {
    override fun init() {
        hookClassInPlugin { classLoader ->
            try {
                val volumeUtilClass = ReflectionHelper.callMethod(
                    classLoader,
                    "loadClass",
                    "com.android.systemui.miui.volume.Util"
                ) ?: return@hookClassInPlugin
                volumeUtilClass as Class<*>
                val allVolumeUtilMethods = volumeUtilClass.methods
                if (allVolumeUtilMethods.isEmpty()) {
                    return@hookClassInPlugin
                }
                allVolumeUtilMethods.forEach { method ->
                    if (method.name == "isSupportBlurS") {
                        HookBridge.hookAllMethods(
                            volumeUtilClass,
                            "isSupportBlurS",
                            object : HookCallback() {
                                override fun afterHookedMethod(param: MethodHookParam) {
                                    try {
                                        if (param.result is Boolean) {
                                            param.result = true
                                        }
                                    } catch (e: Throwable) {
                                        // Do Nothings.
                                        logW(TAG, this@EnableVolumeBlur.lpparam.packageName, e)
                                    }
                                }
                            })
                        return@hookClassInPlugin
                    }
                }
            } catch (e: Throwable) {
                // Do Nothings.
                logW(TAG, this.lpparam.packageName, e)
            }
        }
    }

    private fun hookClassInPlugin(afterGetClassLoader: (classLoader: ClassLoader) -> Unit) {
        val pluginHandlerClass = findClassIfExists(
            "com.android.systemui.shared.plugins.PluginInstanceManager\$PluginHandler"
        )
        if (pluginHandlerClass != null) {
            HookBridge.hookAllMethods(pluginHandlerClass, "handleLoadPlugin",
                object : HookCallback() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val componentName = param.args[0]
                        val className =
                            ReflectionHelper.callMethod(componentName, "getClassName") as String
                        if (className != "miui.systemui.volume.VolumeDialogPlugin") {
                            return
                        }
                        try {
                            val pluginContextWrapper =
                                getValueByField(param.result ?: return, "mPluginContext") ?: return
                            val classLoader = ReflectionHelper.callMethod(
                                pluginContextWrapper,
                                "getClassLoader"
                            ) as ClassLoader
                            afterGetClassLoader(classLoader)
                        } catch (e: Throwable) {
                            // Do Nothings.
                            logW(TAG, this@EnableVolumeBlur.lpparam.packageName, "hookClassInPlugin failed", e)
                        }
                    }
                })
            return
        }

        val pluginActionManagerClass = findClassIfExists(
            "com.android.systemui.shared.plugins.PluginActionManager"
        )
        if (pluginActionManagerClass != null) {
            HookBridge.hookAllMethods(pluginActionManagerClass, "loadPluginComponent",
                object : HookCallback() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val componentName = param.args[0]
                        val className =
                            ReflectionHelper.callMethod(componentName, "getClassName") as String
                        if (className != "miui.systemui.volume.VolumeDialogPlugin") {
                            return
                        }
                        try {
                            val pluginContextWrapper =
                                getValueByField(param.result ?: return, "mPluginContext")
                                    ?: return
                            val classLoader = ReflectionHelper.callMethod(
                                pluginContextWrapper,
                                "getClassLoader"
                            ) as ClassLoader
                            afterGetClassLoader(classLoader)
                        } catch (e: Throwable) {
                            // Do Nothings.
                            logW(TAG, this@EnableVolumeBlur.lpparam.packageName, e)
                        }
                    }
                })
            return
        }
    }
}
