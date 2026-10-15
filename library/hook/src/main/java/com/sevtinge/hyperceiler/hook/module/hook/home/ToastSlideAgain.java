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
package com.sevtinge.hyperceiler.hook.module.hook.home;

import android.view.MotionEvent;

import com.sevtinge.hyperceiler.hook.module.base.BaseHook;

import com.sevtinge.hyperceiler.hook.api.HookCallback;
import com.sevtinge.hyperceiler.hook.api.ReflectionHelper;

public class ToastSlideAgain extends BaseHook {
    public HookCallback.Unhook unhook = null;

    @Override
    public void init() {
        findAndHookMethod("com.miui.home.recents.NavStubView",
            "onPointerEvent", MotionEvent.class,
            new MethodHook() {
                @Override
                protected void before(MethodHookParam param) {
                    unhook = hookToast();
                    // logI("im hook onPointerEvent");
                }

                @Override
                protected void after(MethodHookParam param) {
                    unHook(unhook);
                }
            }
        );

        findAndHookMethod("com.miui.home.recents.GestureModeApp",
            lpparam.classLoader, "onStartGesture", new MethodHook() {
                @Override
                protected void before(MethodHookParam param) {
                    unhook = hookToast();
                    // logI("im hook onStartGesture");
                }

                @Override
                protected void after(MethodHookParam param) {
                    unHook(unhook);
                }
            }
        );
    }

    public HookCallback.Unhook hookToast() {
        return ReflectionHelper.findAndHookMethod(findClassIfExists("android.widget.Toast"),
            "show", new HookCallback() {
                @Override
                protected void beforeHookedMethod(MethodHookParam param) {
                    param.setResult(null);
                    // logI("im hook Toast show");
                }
            }
        );
    }

    public void unHook(HookCallback.Unhook unhook) {
        if (unhook != null) {
            unhook.unhook();
            // logI("the unhook is: " + unhook);
        }  // logE("the unhook is: null");

    }
}
