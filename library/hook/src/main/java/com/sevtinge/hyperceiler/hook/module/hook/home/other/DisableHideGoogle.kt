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
package com.sevtinge.hyperceiler.hook.module.hook.home.other

import android.content.*
import com.sevtinge.hyperceiler.hook.module.base.BaseHook
import com.sevtinge.hyperceiler.hook.utils.InvokeUtils
import com.sevtinge.hyperceiler.hook.api.*

@Suppress("UNCHECKED_CAST")
object DisableHideGoogle : BaseHook() {
    override fun init() {
        if (InvokeUtils.getStaticField("miui.os.Build", "IS_INTERNATIONAL_BUILD"))
            return

        ReflectionHelper.findAndHookConstructor(
            "com.miui.home.launcher.AppFilter",
            lpparam.classLoader,
            object : HookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val skippedItem = ReflectionHelper.getObjectField(
                        param.thisObject,
                        "mSkippedItems"
                    ) as HashSet<ComponentName>

                    skippedItem.removeIf {
                        it.packageName == "com.google.android.googlequicksearchbox"
                            || it.packageName == "com.google.android.gms"
                    }
                }
            }
        )
    }

}
