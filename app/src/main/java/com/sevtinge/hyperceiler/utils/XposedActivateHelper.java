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
package com.sevtinge.hyperceiler.utils;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import com.sevtinge.hyperceiler.hook.utils.prefs.FrameworkServiceClient;

import com.sevtinge.hyperceiler.common.utils.DialogHelper;

public class XposedActivateHelper {

    public static volatile boolean isModuleActive = false;
    public static volatile int XposedVersion = 0;

    public static void init(Context context) {
        // Modern modules receive an asynchronous service instead of hooking their own app.
        new Handler(Looper.getMainLooper()).postDelayed(() -> checkActivateState(context), 1000);
    }

    private static void checkActivateState(Context context) {
        isModuleActive = FrameworkServiceClient.isConnected();
        if (!isModuleActive && !(context instanceof android.app.Activity activity && activity.isFinishing())) {
            DialogHelper.showXposedActivateDialog(context);
        }
    }
}
