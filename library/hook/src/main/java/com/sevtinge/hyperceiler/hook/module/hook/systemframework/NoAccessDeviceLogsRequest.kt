package com.sevtinge.hyperceiler.hook.module.hook.systemframework

import com.sevtinge.hyperceiler.hook.module.base.BaseHook
import com.sevtinge.hyperceiler.hook.api.HookCallback
import com.sevtinge.hyperceiler.hook.api.ReflectionHelper
import io.github.kyuubiran.ezxhelper.core.util.ClassUtil

object NoAccessDeviceLogsRequest : BaseHook() {
    private lateinit var mActivityManagerInternal: Any
    private lateinit var mLogcatManagerService: Any
    override fun init() {
        val logAccessClient = ClassUtil.loadClass(
            "com.android.server.logcat.LogcatManagerService\$LogAccessClient",
            lpparam.classLoader
        )

        ReflectionHelper.findAndHookMethod(
            "com.android.server.logcat.LogcatManagerService", lpparam.classLoader,
            "onStart",
            object : HookCallback() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        mLogcatManagerService = param.thisObject
                        mActivityManagerInternal = ReflectionHelper.getObjectField(
                            mLogcatManagerService, "mActivityManagerInternal"
                        )
                    } catch (t: Throwable) {
                        logE(TAG, "NoAccessDeviceLogsRequest -> onStart", t)
                    }
                }
            }
        )

        ReflectionHelper.findAndHookMethod(
            "com.android.server.logcat.LogcatManagerService", lpparam.classLoader,
            "processNewLogAccessRequest",
            logAccessClient,
            object : HookCallback() {
                override fun beforeHookedMethod(param: MethodHookParam) {
                    try {
                        val client = param.args[0]
                        if (client == null || mActivityManagerInternal == null) return
                        val uid = ReflectionHelper.getIntField(client, "mUid")
                        val packageName =
                            ReflectionHelper.getObjectField(client, "mPackageName") as String
                        ReflectionHelper.callMethod(
                            mLogcatManagerService,
                            "onAccessApprovedForClient",
                            client
                        )
                        // debug 用，取消禁用详细日志输出可进行调试
                        logD(
                            TAG,
                            this@NoAccessDeviceLogsRequest.lpparam.packageName,
                            "NoAccessDeviceLogsRequest bypass for package=$packageName uid=$uid"
                        )
                        param.result = null
                    } catch (t: Throwable) {
                        // 输出异常日志
                        logE(
                            TAG,
                            this@NoAccessDeviceLogsRequest.lpparam.packageName,
                            "processNewLogAccessRequest failed",
                            t
                        )
                    }
                }
            })
        // 米客原来的取消方法，未知情况封堵失败
        /*try {
            loadClass("com.android.server.logcat.LogcatManagerService").methodFinder().filter {
                name == "onLogAccessRequested"
            }.toList().createHooks {
                before { param ->
                    ReflectionHelper.callMethod(param.thisObject, "declineRequest", param.args[0])
                    param.result = null
                }
            }
        } catch (t: Throwable) {
            logE(TAG, this.lpparam.packageName, t)
        }*/
    }
}
