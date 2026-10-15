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
package com.sevtinge.hyperceiler.hook.module.hook.systemframework.freeform;

import android.util.Log;
import android.view.SurfaceControl;

import com.sevtinge.hyperceiler.hook.module.base.BaseHook;

import com.sevtinge.hyperceiler.hook.api.HookCallback;
import com.sevtinge.hyperceiler.hook.api.ReflectionHelper;

public class UnForegroundPin extends BaseHook {
    @Override
    public void init() {
        try {
            /*Hyper*/
            getDeclaredMethod("com.android.server.wm.MiuiFreeFormGestureController",
                "needForegroundPin",
                "com.android.server.wm.MiuiFreeFormActivityStack");
            findAndHookMethod("com.android.server.wm.MiuiFreeFormGestureController",
                "needForegroundPin",
                "com.android.server.wm.MiuiFreeFormActivityStack",
                new MethodHook() {
                    @Override
                    protected void before(HookCallback.MethodHookParam param) {
                        param.setResult(true);
                    }
                }
            );
        } catch (Throwable throwable) {
            logE(TAG, "Hyper UnForegroundPin E, if you is Miui don't worry : " + throwable);
            /*Miui*/
            findAndHookMethod("com.android.server.wm.MiuiFreeFormGestureController",
                "moveTaskToBack",
                "com.android.server.wm.MiuiFreeFormActivityStack",
                new MethodHook() {
                    @Override
                    protected void before(HookCallback.MethodHookParam param) {
                        Object stack = param.args[0];
                        if (stack != null) {
                            Object mTask = ReflectionHelper.getObjectField(stack, "mTask");
                            if (mTask != null) {
                                SurfaceControl surfaceControl;
                                if (mTask != null && (surfaceControl =
                                    (SurfaceControl) ReflectionHelper.getObjectField(mTask,
                                        "mSurfaceControl")) != null
                                    && surfaceControl.isValid()) {
                                    SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
                                    ReflectionHelper.callMethod(transaction, "hide", surfaceControl);
                                    ReflectionHelper.callMethod(transaction, "apply");
                                    // logE(tag, "moveTaskToBack: s: " + surfaceControl);
                                    param.setResult(null);
                                }
                            }
                        }
                    }
                }
            );

            hookAllConstructors("com.android.server.wm.Task",
                new MethodHook() {
                    @Override
                    protected void after(HookCallback.MethodHookParam param) {
                        ReflectionHelper.setAdditionalInstanceField(param.thisObject, "mLastSurfaceVisibility", true);
                        // logE(tag, "Task add mLastSurfaceVisibility");
                    }
                }
            );

            findAndHookMethod("com.android.server.wm.Task",
                "prepareSurfaces", new MethodHook() {
                    @Override
                    protected void after(HookCallback.MethodHookParam param) {
                        SurfaceControl.Transaction transaction = (SurfaceControl.Transaction) ReflectionHelper.callMethod(param.thisObject, "getSyncTransaction");
                        SurfaceControl mSurfaceControl = (SurfaceControl) ReflectionHelper.getObjectField(param.thisObject, "mSurfaceControl");
                        // String pkg = (String) ReflectionHelper.callMethod(param.thisObject, "getPackageName");
                        // String mCallingPackage = (String) ReflectionHelper.getObjectField(param.thisObject, "mCallingPackage");
                        // Object getTopNonFinishingActivity = ReflectionHelper.callMethod(param.thisObject, "getTopNonFinishingActivity");
                        // String pkg = null;
                            /*if (getTopNonFinishingActivity != null) {
                                ActivityInfo activityInfo = (ActivityInfo) ReflectionHelper.getObjectField(getTopNonFinishingActivity, "info");
                                if (activityInfo != null) {
                                    pkg = activityInfo.applicationInfo.packageName;
                                }
                            }*/
                        int taskId = (int) ReflectionHelper.callMethod(param.thisObject, "getRootTaskId");
                        Object mWmService = ReflectionHelper.getObjectField(param.thisObject, "mWmService");
                        Object mAtmService = ReflectionHelper.getObjectField(mWmService, "mAtmService");
                        Object mMiuiFreeFormManagerService = ReflectionHelper.getObjectField(mAtmService, "mMiuiFreeFormManagerService");
                        Object mffs = ReflectionHelper.callMethod(mMiuiFreeFormManagerService, "getMiuiFreeFormActivityStack", taskId);
                        boolean isVisible = (boolean) ReflectionHelper.callMethod(param.thisObject, "isVisible");
                        boolean isAnimating = (boolean) ReflectionHelper.callMethod(param.thisObject, "isAnimating", 7);
                        boolean inPinMode = false;
                        if (mffs != null) {
                            inPinMode = (boolean) ReflectionHelper.callMethod(mffs, "inPinMode");
                        }
                        boolean mLastSurfaceVisibility = (boolean) ReflectionHelper.getAdditionalInstanceField(param.thisObject, "mLastSurfaceVisibility");
                        if (mSurfaceControl != null && mffs != null && inPinMode) {
                            if (!isAnimating) {
                                ReflectionHelper.callMethod(transaction, "setVisibility", mSurfaceControl, false);
                                ReflectionHelper.setAdditionalInstanceField(param.thisObject, "mLastSurfaceVisibility", false);
                            }
                            // logE(tag, "setVisibility false pkg2: " + pkg + " taskid: " + taskId + " isVisble: " + isVisible
                            // + " an: " + isAnimating + " la: " + mLastSurfaceVisibility);
                        } else if (mSurfaceControl != null && mffs != null && !inPinMode) {
                            if (!mLastSurfaceVisibility) {
                                ReflectionHelper.callMethod(transaction, "setVisibility", mSurfaceControl, true);
                                ReflectionHelper.setAdditionalInstanceField(param.thisObject, "mLastSurfaceVisibility", true);
                            }
                            // logE(tag, "setVisibility true pkg2: " + pkg + " taskid: " + taskId + " isVisble: " + isVisible + " an: " + isAnimating);
                        }
                        // logE(tag, "sur: " + mSurfaceControl + " tra: " + transaction + " pkg: " + pkg + " inpin: " + inPinMode);
                    }
                }
            );

            findAndHookMethod("com.android.server.wm.MiuiFreeFormGestureController",
                "moveTaskToFront",
                "com.android.server.wm.MiuiFreeFormActivityStack",
                new MethodHook() {
                    @Override
                    protected void before(HookCallback.MethodHookParam param) {
                        param.setResult(null);
                    }
                }
            );

            findAndHookMethod("com.android.server.wm.MiuiFreeformPinManagerService",
                "lambda$unPinFloatingWindow$0$com-android-server-wm-MiuiFreeformPinManagerService",
                "com.android.server.wm.MiuiFreeFormActivityStack",
                float.class, float.class, boolean.class, "com.android.server.wm.DisplayContent",
                "com.android.server.wm.MiuiFreeFormFloatIconInfo",
                new MethodHook() {
                    @Override
                    protected void before(HookCallback.MethodHookParam param) {
                        Object mffas = param.args[0];
                        Object activityRecord = ReflectionHelper.callMethod(ReflectionHelper.getObjectField(mffas, "mTask"),
                            "getTopNonFinishingActivity");
                        /*遵循安卓日志*/
                        Log.i("MiuiFreeformPinManagerService",
                            "unPinFloatingWindow mffas: " + mffas + " activityRecord: " + activityRecord);
                        if (activityRecord == null) {
                            param.setResult(null);
                            return;
                        }
                        ReflectionHelper.setObjectField(mffas, "mEnterVelocityX", param.args[1]);
                        ReflectionHelper.setObjectField(mffas, "mEnterVelocityY", param.args[2]);
                        ReflectionHelper.setObjectField(mffas, "mIsEnterClick", param.args[3]);
                        ReflectionHelper.setObjectField(mffas, "mIsPinFloatingWindowPosInit", false);
                        ReflectionHelper.callMethod(param.thisObject, "setUpMiuiFreeWindowFloatIconAnimation",
                            mffas, activityRecord, param.args[4], param.args[5]);
                        ReflectionHelper.callMethod(param.thisObject, "startUnPinAnimation", mffas);
                        param.setResult(null);
                    }
                }
            );

            findAndHookMethod("com.android.server.wm.MiuiFreeFormGestureController",
                "lambda$startFullscreenFromFreeform$2$com-android-server-wm-MiuiFreeFormGestureController",
                "com.android.server.wm.MiuiFreeFormActivityStack",
                new MethodHook() {
                    @Override
                    protected void before(HookCallback.MethodHookParam param) {
                        Object mffas = param.args[0];
                        Object mGestureListener = ReflectionHelper.getObjectField(param.thisObject, "mGestureListener");
                        if ((boolean) ReflectionHelper.callMethod(mffas, "isInFreeFormMode")) {
                            ReflectionHelper.callMethod(mGestureListener, "startFullScreenFromFreeFormAnimation", mffas);
                            Object mTrackManager = ReflectionHelper.getObjectField(param.thisObject, "mTrackManager");
                            if (mTrackManager != null) {
                                ReflectionHelper.callMethod(mTrackManager, "trackSmallWindowPinedQuitEvent",
                                    ReflectionHelper.callMethod(mffas, "getStackPackageName"),
                                    ReflectionHelper.callMethod(mffas, "getApplicationName"),
                                    (long) ReflectionHelper.getObjectField(mffas, "mPinedStartTime") != 0 ?
                                        ((float) (System.currentTimeMillis() -
                                            (long) ReflectionHelper.getObjectField(mffas,
                                                "mPinedStartTime"))) / 1000.0f : 0.0f
                                );
                            }
                        } else if ((boolean) ReflectionHelper.callMethod(mffas, "isInMiniFreeFormMode")) {
                            ReflectionHelper.callMethod(mGestureListener, "startFullScreenFromSmallAnimation", mffas);
                            Object mTrackManager = ReflectionHelper.getObjectField(param.thisObject, "mTrackManager");
                            if (mTrackManager != null) {
                                ReflectionHelper.callMethod(mTrackManager, "trackMiniWindowPinedQuitEvent",
                                    ReflectionHelper.callMethod(mffas, "getStackPackageName"),
                                    ReflectionHelper.callMethod(mffas, "getApplicationName"),
                                    (long) ReflectionHelper.getObjectField(mffas, "mPinedStartTime") != 0 ?
                                        ((float) (System.currentTimeMillis() -
                                            (long) ReflectionHelper.getObjectField(mffas,
                                                "mPinedStartTime"))) / 1000.0f : 0.0f
                                );
                            }
                        }
                        ReflectionHelper.setObjectField(mffas, "mPinedStartTime", 0L);
                        ReflectionHelper.callMethod(mffas, "setInPinMode", false);
                        param.setResult(null);
                    }
                }
            );

        }
    }
}
