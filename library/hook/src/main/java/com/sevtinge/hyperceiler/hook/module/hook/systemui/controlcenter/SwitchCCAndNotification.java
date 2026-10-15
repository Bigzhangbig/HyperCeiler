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
package com.sevtinge.hyperceiler.hook.module.hook.systemui.controlcenter;

import android.view.MotionEvent;
import android.widget.FrameLayout;

import com.sevtinge.hyperceiler.hook.module.base.BaseHook;

import com.sevtinge.hyperceiler.hook.api.ReflectionHelper;

public class SwitchCCAndNotification extends BaseHook {
    @Override
    public void init() {
        findAndHookMethod(
                "com.android.systemui.statusbar.phone.MiuiPhoneStatusBarView", lpparam.classLoader,
                "handleEvent",
                MotionEvent.class, new MethodHook() {
                    @Override
                    protected void before(MethodHookParam param) {
                        boolean useCC = (boolean) ReflectionHelper.callMethod(ReflectionHelper.getObjectField(param.thisObject, "mPanelController"), "isExpandable");
                        if (useCC) {
                            FrameLayout bar = (FrameLayout) param.thisObject;
                            Object mControlPanelWindowManager = ReflectionHelper.getObjectField(param.thisObject, "mControlPanelWindowManager");
                            boolean dispatchToControlPanel = (boolean) ReflectionHelper.callMethod(mControlPanelWindowManager, "dispatchToControlPanel", param.args[0], bar.getWidth());
                            ReflectionHelper.setObjectField(mControlPanelWindowManager, "transToControlPanel", dispatchToControlPanel);
                            param.setResult(dispatchToControlPanel);
                            return;
                        }
                        param.setResult(false);
                    }
                });

        findAndHookMethod(
                "com.android.systemui.controlcenter.phone.ControlPanelWindowManager", lpparam.classLoader,
                "dispatchToControlPanel",
                MotionEvent.class, float.class,
                new MethodHook() {
                    @Override
                    protected void before(MethodHookParam param) {
                        boolean added = ReflectionHelper.getBooleanField(param.thisObject, "added");
                        if (added) {
                            boolean useCC;
                            Object controlCenterWindowView;

                            useCC = ReflectionHelper.getBooleanField(ReflectionHelper.getObjectField(param.thisObject, "controlCenterController"), "useControlCenter");
                            if (useCC) {
                                MotionEvent motionEvent = (MotionEvent) param.args[0];
                                if (motionEvent.getActionMasked() == 0) {
                                    ReflectionHelper.setObjectField(param.thisObject, "downX", motionEvent.getRawX());
                                }

                                controlCenterWindowView = ReflectionHelper.getObjectField(param.thisObject, "windowView");

                                if (controlCenterWindowView == null) {
                                    param.setResult(false);
                                } else {
                                    float mDownX;
                                    mDownX = ReflectionHelper.getFloatField(param.thisObject, "downX");
                                    float width = (float) param.args[1];
                                    if (mDownX < width / 2.0f) {
                                        param.setResult(ReflectionHelper.callMethod(controlCenterWindowView, "handleMotionEvent", motionEvent, true));
                                    } else {
                                        param.setResult(false);
                                    }
                                }
                                return;
                            }
                        }
                        param.setResult(false);
                    }
                });
    }
}
