/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 *
 * Copyright (C) 2023-2025 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.common.utils;

import android.content.Context;
import android.content.SharedPreferences;

import com.sevtinge.hyperceiler.hook.utils.prefs.FrameworkServiceClient;

import java.util.ArrayList;
import java.util.List;

public class LSPosedScopeHelper {

    private static boolean isInitScopeGet = false;
    private static boolean isScopeGetFailed = false;

    public static ArrayList<String> mNoScoped = new ArrayList<>();
    public static ArrayList<String> mUninstallApp = new ArrayList<>();
    public static ArrayList<String> mDisableOrHiddenApp = new ArrayList<>();

    public static List<String> mScope = new ArrayList<>();
    public static List<String> mNotInSelectedScope = new ArrayList<>();

    public static void init(Context context) {
        getScope();
    }

    public static boolean isInSelectedScope(Context context, String lable, String pkg, String key, SharedPreferences sp) {
        String normLabel = lable == null ? "" : lable.trim();
        String normPkg = pkg == null ? null : pkg.trim();
        String normPkgForEntry = normPkg == null ? "" : normPkg.toLowerCase();
        String entry = " - " + normLabel + (normPkg != null ? " (" + normPkgForEntry + ")" : "");

        if (isDisable(context, pkg) || isUninstall(context, pkg) || isHidden(context, pkg)) {
            if (!mUninstallApp.contains(entry)) {
                mUninstallApp.add(entry);
            }
            return false;
        } else if (isHiddenByHyperceiler(key, sp)) {
            if (!mDisableOrHiddenApp.contains(entry)) {
                mDisableOrHiddenApp.add(entry);
            }
            return false;
        }
        if (pkg != null && !mScope.contains(pkg) && isInitScopeGet && !isScopeGetFailed) {
            if (!mNotInSelectedScope.contains(normPkgForEntry)) {
                mNotInSelectedScope.add(normPkgForEntry);
            }
            if (!mDisableOrHiddenApp.contains(entry) && !mUninstallApp.contains(entry) && !mNoScoped.contains(entry)) {
                mNoScoped.add(entry);
            }
            return false;
        }
        return true;
    }

    private static boolean isAndroidPackage(String pkg) {
        return true;
    }

    private static boolean isUninstall(Context context, String pkg) {
        return isAndroidPackage(pkg) && PackagesUtils.isUninstall(context, pkg);
    }

    private static boolean isDisable(Context context, String pkg) {
        return isAndroidPackage(pkg) && PackagesUtils.isDisable(context, pkg);
    }

    private static boolean isHidden(Context context, String pkg) {
        return isAndroidPackage(pkg) && PackagesUtils.isHidden(context, pkg);
    }

    private static boolean isHiddenByHyperceiler(String key, SharedPreferences sp) {
        return !sp.getBoolean(key + "_state", true);
    }

    private static void getScope() {
        List<String> scope = FrameworkServiceClient.getScope();
        if (scope == null) {
            isScopeGetFailed = true;
            return;
        }
        ArrayList<String> packages = new ArrayList<>();
        for (String name : scope) packages.add("system".equals(name) ? "android" : name);
        mScope = packages;
        isScopeGetFailed = false;
        isInitScopeGet = true;
    }
}
