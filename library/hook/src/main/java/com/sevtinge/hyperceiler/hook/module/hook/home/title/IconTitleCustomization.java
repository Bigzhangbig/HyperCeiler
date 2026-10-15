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
package com.sevtinge.hyperceiler.hook.module.hook.home.title;

import android.app.Activity;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;

import com.sevtinge.hyperceiler.hook.module.base.BaseHook;
import com.sevtinge.hyperceiler.hook.utils.prefs.PrefType;
import com.sevtinge.hyperceiler.hook.utils.prefs.PrefsChangeObserver;

import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.sevtinge.hyperceiler.hook.api.ReflectionHelper;

public class IconTitleCustomization extends BaseHook {
    @Override
    public void init() {
        findAndHookMethod("com.miui.home.launcher.Launcher",
                "onCreate", Bundle.class,
                new MethodHook() {
                    @Override
                    protected void after(MethodHookParam param) {
                        Activity act = (Activity) param.thisObject;
                        Context context = act.getBaseContext();
                        Handler handler = new Handler(context.getMainLooper());
                        // Handler handler = (Handler) ReflectionHelper.getObjectField(param.thisObject, "mHandler");
                        new PrefsChangeObserver(context, handler, true, "prefs_key_home_title_title_icontitlecustomization") {
                            @Override
                            public void onChange(PrefType type, Uri uri, String name, Object def) {
                                try {
                                    HashSet<?> mAllLoadedApps;
                                    if (ReflectionHelper.findFieldIfExists(param.thisObject.getClass(), "mAllLoadedShortcut") != null)
                                        mAllLoadedApps = (HashSet<?>) ReflectionHelper.getObjectField(param.thisObject, "mAllLoadedShortcut");
                                    else if (ReflectionHelper.findFieldIfExists(param.thisObject.getClass(), "mAllLoadedApps") != null)
                                        mAllLoadedApps = (HashSet<?>) ReflectionHelper.getObjectField(param.thisObject, "mAllLoadedApps");
                                    else
                                        mAllLoadedApps = (HashSet<?>) ReflectionHelper.getObjectField(param.thisObject, "mLoadedAppsAndShortcut");
                                    Activity act = (Activity) param.thisObject;
                                    if (mAllLoadedApps != null) {
                                        for (Object shortcut : mAllLoadedApps) {
                                            boolean isApplicatoin = (boolean) ReflectionHelper.callMethod(shortcut, "isApplicatoin");
                                            if (!isApplicatoin) continue;
                                            String pkgName = (String) ReflectionHelper.callMethod(shortcut, "getPackageName");
                                            // String actName = (String) ReflectionHelper.callMethod(shortcut, "getClassName");
                                            // UserHandle user = (UserHandle) ReflectionHelper.getObjectField(shortcut, "user");
                                            CharSequence getApp = getAppName(pkgName);
                                            if (getApp != null && !getApp.equals("")) {
                                                // CharSequence newStr = TextUtils.isEmpty(newTitle) ? (CharSequence) ReflectionHelper.getAdditionalInstanceField(shortcut, "mLabelOrig") : newTitle;
                                                ReflectionHelper.setObjectField(shortcut, "mLabel", getApp);
                                                act.runOnUiThread(() -> {
                                                    if (lpparam.packageName.equals("com.miui.home")) {
                                                        ReflectionHelper.callMethod(shortcut, "updateBuddyIconView", act);
                                                    } else {
                                                        Object buddyIconView = ReflectionHelper.callMethod(shortcut, "getBuddyIconView");
                                                        if (buddyIconView != null)
                                                            ReflectionHelper.callMethod(buddyIconView, "updateInfo", param.thisObject, shortcut);
                                                    }
                                                });
                                                break;
                                            }
                                        }
                                    }
                                } catch (Throwable throwable) {
                                    logE(TAG, "e: " + throwable);
                                }
                            }
                        };
                    }
                }
        );

        hookAllConstructors("com.miui.home.launcher.ShortcutInfo",
                new MethodHook() {
                    @Override
                    protected void after(MethodHookParam param) {
                        ReflectionHelper.setAdditionalInstanceField(param.thisObject,
                                "mLabelOrig", ReflectionHelper.getObjectField(param.thisObject, "mLabel"));
                        if (param.args != null && param.args.length > 0)
                            modifyTitle(param.thisObject);
                    }
                }
        );

        findAndHookMethodSilently("com.miui.home.launcher.ShortcutInfo",
                "loadToggleInfo", Context.class,
                new MethodHook() {
                    @Override
                    protected void after(MethodHookParam param) {
                        ReflectionHelper.setAdditionalInstanceField(param.thisObject, "mLabelOrig",
                                ReflectionHelper.getObjectField(param.thisObject, "mLabel"));
                        modifyTitle(param.thisObject);
                    }
                }
        );

        findAndHookMethodSilently("com.miui.home.launcher.ShortcutInfo",
                "setLabelAndUpdateDB", CharSequence.class, Context.class,
                new MethodHook() {
                    @Override
                    protected void after(MethodHookParam param) {
                        ReflectionHelper.setAdditionalInstanceField(param.thisObject,
                                "mLabelOrig", param.args[0]);
                        modifyTitle(param.thisObject);
                    }
                }
        );

        findAndHookMethod("com.miui.home.launcher.ShortcutInfo",
                "load", Context.class, Cursor.class,
                new MethodHook() {
                    @Override
                    protected void after(MethodHookParam param) {
                        modifyTitle(param.thisObject);
                    }
                }
        );

        hookAllMethodsSilently("com.miui.home.launcher.BaseAppInfo",
                "resetTitle",
                new MethodHook() {
                    @Override
                    protected void after(MethodHookParam param) {
                        modifyTitle(param.thisObject);
                    }
                }
        );
    }

    public void modifyTitle(Object thisObject) {
        boolean isApplicatoin = (boolean) ReflectionHelper.callMethod(thisObject, "isApplicatoin");
        if (!isApplicatoin) return;
        String pkgName = (String) ReflectionHelper.callMethod(thisObject, "getPackageName");
        // String actName = (String) ReflectionHelper.callMethod(thisObject, "getClassName");
        // UserHandle user = (UserHandle) ReflectionHelper.getObjectField(thisObject, "user");
        String newTitle = (String) getAppName(pkgName);
        if (newTitle != null && !newTitle.isEmpty())
            ReflectionHelper.setObjectField(thisObject, "mLabel", newTitle);
    }

    public CharSequence getAppName(String packageName) {
        String string2 = "";
        Pattern pattern = Pattern.compile(".*฿(.*)฿.*");
        for (String edit : mPrefsMap.getStringSet("home_title_title_icontitlecustomization")) {
            if (edit.contains(packageName + "฿")) {
                Matcher matcher = pattern.matcher(edit);
                if (matcher.find()) {
                    string2 = matcher.group(1);
                }
            }
        }
        return string2;
    }
}
