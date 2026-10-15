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
package com.sevtinge.hyperceiler.hook.module.hook.guardprovider;

import com.sevtinge.hyperceiler.hook.module.base.BaseHook;

import org.json.JSONArray;
import org.json.JSONObject;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

import com.sevtinge.hyperceiler.hook.api.HookCallback;
import com.sevtinge.hyperceiler.hook.api.HookBridge;
import com.sevtinge.hyperceiler.hook.api.ReflectionHelper;

public class DisableUploadAppList extends BaseHook {

    @Override
    public void init(/*final PackageContext.LoadPackageParam lpparam*/) {
        if (lpparam.packageName.equals("com.miui.guardprovider")) {
            logI(TAG, this.lpparam.packageName, "Start to hook package " + lpparam.packageName);

            // Debug mode flag process
            final Class<?> guardApplication = ReflectionHelper.findClass("com.miui.guardprovider.GuardApplication", lpparam.classLoader);
            if (guardApplication != null) {
                Field[] guardApplicationFields = guardApplication.getDeclaredFields();
                for (Field field : guardApplicationFields) {
                    if (field.getName().equals("c")) {
                        ReflectionHelper.setStaticBooleanField(guardApplication, "c", true);
                        logI(TAG, this.lpparam.packageName, "GuardProvider will work as debug mode!");
                    }
                    logW(TAG, this.lpparam.packageName, "GuardProvider debug mode flag not found!");
                }
            } else {
                logW(TAG, this.lpparam.packageName, "GuardApplication class not found. GuardProvider will not work as debug mode! ");
            }

            // Prevent miui from uploading app list
            final Class<?> antiDefraudAppManager = ReflectionHelper.findClassIfExists("com.miui.guardprovider.engine.mi.antidefraud.AntiDefraudAppManager", lpparam.classLoader);
            if (antiDefraudAppManager == null) {
                logI(TAG, this.lpparam.packageName, "Skip: AntiDefraudAppManager class not found.");
                return;
            } else {
                logI(TAG, this.lpparam.packageName, "AntiDefraudAppManager class found.");
            }

            final Method[] methods = antiDefraudAppManager.getDeclaredMethods();
            Method getAllUnSystemAppsStatus = null;
            for (Method method : methods) {
                if (method.getName().equals("getAllUnSystemAppsStatus") && method.getParameterTypes().length == 1) {
                    getAllUnSystemAppsStatus = method;
                    break;
                }
            }
            if (getAllUnSystemAppsStatus == null) {
                logI(TAG, this.lpparam.packageName, "Skip: getAllUnSystemAppsStatus method not found.");
                return;
            } else {
                logI(TAG, this.lpparam.packageName, "getAllUnSystemAppsStatus method found.");
            }

            HookBridge.hookMethod(getAllUnSystemAppsStatus, new HookCallback() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    List list = (List) methodHookParam.args[0];

                    String MIUI_VERSION = null;
                    Field[] antiDefraudAppManagerFields = antiDefraudAppManager.getDeclaredFields();
                    for (Field field : antiDefraudAppManagerFields) {
                        if (field.getName().equals("MIUI_VERSION")) {
                            MIUI_VERSION = (String) ReflectionHelper.getStaticObjectField(antiDefraudAppManager, "MIUI_VERSION");
                        }
                    }
                    if (MIUI_VERSION == null) {
                        logW(TAG, DisableUploadAppList.this.lpparam.packageName, "Can't get MIUI_VERSION.");
                    }

                    String uuid = null;
                    final Class<?> uuidHelper = ReflectionHelper.findClassIfExists("i.b", lpparam.classLoader);
                    if (uuidHelper != null) {
                        final Method[] uuidHelperMethods = uuidHelper.getDeclaredMethods();
                        Method getUUID = null;
                        for (Method method : uuidHelperMethods) {
                            if (method.getName().equals("b") && method.getParameterTypes().length == 0) {
                                getUUID = method;
                                break;
                            }
                        }
                        if (getUUID != null) {
                            getUUID.setAccessible(true);
                            uuid = (String) getUUID.invoke(methodHookParam);
                        } else {
                            logW(TAG, DisableUploadAppList.this.lpparam.packageName, "getUUID method not found.");
                        }
                    } else {
                        logW(TAG, DisableUploadAppList.this.lpparam.packageName, "uuidHelper class not found.");
                    }

                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
                    jSONObject.put("os", MIUI_VERSION);
                    jSONObject.put("biz_id", "virus_scan");
                    jSONObject.put("uuid", uuid);

                    JSONArray jSONArray = new JSONArray();
                    for (int i2 = 0; i2 < list.size(); i2++) {
                        JSONObject jSONObject2 = new JSONObject();

                        String pkgName = null;
                        String version = null;
                        String sign = null;
                        String appName = null;

                        Object antiDefraudAppInfo = list.get(i2);

                        Field[] fields = antiDefraudAppInfo.getClass().getDeclaredFields();
                        for (Field filed : fields) {
                            filed.setAccessible(true);
                            if (filed.getName().equals("pkgName")) {
                                pkgName = (String) filed.get(antiDefraudAppInfo);
                            } else if (filed.getName().equals("version")) {
                                version = (String) filed.get(antiDefraudAppInfo);
                            } else if (filed.getName().equals("sign")) {
                                sign = (String) filed.get(antiDefraudAppInfo);
                            } else if (filed.getName().equals("appName")) {
                                appName = (String) filed.get(antiDefraudAppInfo);
                            }
                        }

                        jSONObject2.put("pkg", pkgName);
                        jSONObject2.put("version", version);
                        jSONObject2.put("signature", sign);
                        jSONObject2.put("appname", appName);

                        jSONArray.put(jSONObject2);
                    }
                    jSONObject.put("content", jSONArray);

                    logI(TAG, DisableUploadAppList.this.lpparam.packageName, "Info: Intercept=" + jSONObject.toString());

                    methodHookParam.setResult(null);
                }
            });
        }
    }

   /* @Override
    public void handleLoadPackage(final PackageContext.LoadPackageParam lpparam) {
        if (lpparam.packageName.equals("com.miui.guardprovider")) {
            HookBridge.log("HyperCeiler: Start to hook package " + lpparam.packageName);

            // Debug mode flag process
            final Class<?> guardApplication = ReflectionHelper.findClass("com.miui.guardprovider.GuardApplication", lpparam.classLoader);
            if (guardApplication != null) {
                Field[] guardApplicationFields = guardApplication.getDeclaredFields();
                for (Field field : guardApplicationFields) {
                    if (field.getName().equals("c")) {
                        ReflectionHelper.setStaticBooleanField(guardApplication, "c", true);
                        HookBridge.log("HyperCeiler: Info: GuardProvider will work as debug mode!");
                    }
                    HookBridge.log("HyperCeiler: Warning: GuardProvider debug mode flag not found!");
                }
            } else {
                HookBridge.log("HyperCeiler: Warning: GuardApplication class not found. GuardProvider will not work as debug mode! ");
            }

            // Prevent miui from uploading app list
            final Class<?> antiDefraudAppManager = ReflectionHelper.findClassIfExists("com.miui.guardprovider.engine.mi.antidefraud.AntiDefraudAppManager", lpparam.classLoader);
            if (antiDefraudAppManager == null) {
                HookBridge.log("HyperCeiler: Skip: AntiDefraudAppManager class not found.");
                return;
            } else {
                HookBridge.log("HyperCeiler: Info: AntiDefraudAppManager class found.");
            }

            final Method[] methods = antiDefraudAppManager.getDeclaredMethods();
            Method getAllUnSystemAppsStatus = null;
            for (Method method : methods) {
                if (method.getName().equals("getAllUnSystemAppsStatus") && method.getParameterTypes().length == 1) {
                    getAllUnSystemAppsStatus = method;
                    break;
                }
            }
            if (getAllUnSystemAppsStatus == null) {
                HookBridge.log("HyperCeiler: Skip: getAllUnSystemAppsStatus method not found.");
                return;
            } else {
                HookBridge.log("HyperCeiler: Info: getAllUnSystemAppsStatus method found.");
            }

            HookBridge.hookMethod(getAllUnSystemAppsStatus, new HookCallback() {
                @Override
                protected void beforeHookedMethod(MethodHookParam methodHookParam) throws Throwable {
                    List<Object> list = (List) methodHookParam.args[0];

                    String MIUI_VERSION = null;
                    Field[] antiDefraudAppManagerFields = antiDefraudAppManager.getDeclaredFields();
                    for (Field field : antiDefraudAppManagerFields) {
                        if (field.getName().equals("MIUI_VERSION")) {
                            MIUI_VERSION = (String) ReflectionHelper.getStaticObjectField(antiDefraudAppManager, "MIUI_VERSION");
                        }
                    }
                    if (MIUI_VERSION == null) {
                        HookBridge.log("HyperCeiler: Warning: Can't get MIUI_VERSION.");
                    }

                    String uuid = null;
                    final Class<?> uuidHelper = ReflectionHelper.findClassIfExists("i.b", lpparam.classLoader);
                    if (uuidHelper != null) {
                        final Method[] uuidHelperMethods = uuidHelper.getDeclaredMethods();
                        Method getUUID = null;
                        for (Method method : uuidHelperMethods) {
                            if (method.getName().equals("b") && method.getParameterTypes().length == 0) {
                                getUUID = method;
                                break;
                            }
                        }
                        if (getUUID != null) {
                            getUUID.setAccessible(true);
                            uuid = (String) getUUID.invoke(methodHookParam);
                        } else {
                            HookBridge.log("HyperCeiler: Warning: getUUID method not found.");
                        }
                    } else {
                        HookBridge.log("HyperCeiler: Warning: uuidHelper class not found.");
                    }

                    JSONObject jSONObject = new JSONObject();
                    jSONObject.put("timestamp", String.valueOf(System.currentTimeMillis() / 1000));
                    jSONObject.put("os", MIUI_VERSION);
                    jSONObject.put("biz_id", "virus_scan");
                    jSONObject.put("uuid", uuid);

                    JSONArray jSONArray = new JSONArray();
                    for (int i2 = 0; i2 < list.size(); i2++) {
                        JSONObject jSONObject2 = new JSONObject();

                        String pkgName = null;
                        String version = null;
                        String sign = null;
                        String appName = null;

                        Object antiDefraudAppInfo = (Object) list.get(i2);

                        Field[] fields = antiDefraudAppInfo.getClass().getDeclaredFields();
                        for (Field filed : fields) {
                            filed.setAccessible(true);
                            if (filed.getName().equals("pkgName")) {
                                pkgName = (String) filed.get(antiDefraudAppInfo);
                            } else if (filed.getName().equals("version")) {
                                version = (String) filed.get(antiDefraudAppInfo);
                            } else if (filed.getName().equals("sign")) {
                                sign = (String) filed.get(antiDefraudAppInfo);
                            } else if (filed.getName().equals("appName")) {
                                appName = (String) filed.get(antiDefraudAppInfo);
                            }
                        }

                        jSONObject2.put("pkg", pkgName);
                        jSONObject2.put("version", version);
                        jSONObject2.put("signature", sign);
                        jSONObject2.put("appname", appName);

                        jSONArray.put(jSONObject2);
                    }
                    jSONObject.put("content", jSONArray);

                    HookBridge.log("HyperCeiler: Info: Intercept=" + jSONObject.toString());

                    methodHookParam.setResult(null);
                }
            });
        }
    }*/


}
