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

import android.content.Context;
import android.util.ArrayMap;
import android.widget.Switch;

import com.sevtinge.hyperceiler.hook.R;
import com.sevtinge.hyperceiler.hook.utils.TileUtils;

import com.sevtinge.hyperceiler.hook.api.HookCallback;
import com.sevtinge.hyperceiler.hook.api.ReflectionHelper;

public class ReduceBrightColorsTile extends TileUtils {
    @Override
    public void init() {
        super.init();
    }

    @Override
    public Class<?> customClass() {
        return findClassIfExists("com.android.systemui.qs.tiles.ReduceBrightColorsTile");
    }

    @Override
    public String customName() {
        return super.customName();
    }

    @Override
    public ArrayMap<String, Integer> tileUpdateState(HookCallback.MethodHookParam param, Class<?> mResourceIcon, String tileName) {
        Context mContext = (Context) ReflectionHelper.getObjectField(param.thisObject, "mContext");
        int i;
        int i2;
        Object booleanState = param.args[0];
        /*boolean isReduceBrightColorsActivated = (boolean) ReflectionHelper.callMethod(
            ReflectionHelper.getObjectField(param.thisObject, "mReduceBrightColorsController")
            , "isReduceBrightColorsActivated"
        );*/
        boolean isReduceBrightColorsActivated = (boolean) ReflectionHelper.callMethod(
            ReflectionHelper.getObjectField(
                ReflectionHelper.getObjectField(
                    param.thisObject,
                    "mReduceBrightColorsController"),
                "mManager"
            ), "isReduceBrightColorsActivated");
        ReflectionHelper.setObjectField(booleanState, "value", isReduceBrightColorsActivated);
        if (isReduceBrightColorsActivated) {
            i = 2;
        } else i = 1;
        ReflectionHelper.setObjectField(booleanState, "state", i);
        ReflectionHelper.setObjectField(booleanState, "label", mContext.getString(

            R.string.system_control_center_reduce_bright_colors_tile));
        ReflectionHelper.setObjectField(booleanState, "expandedAccessibilityClassName", Switch.class.getName());
        ReflectionHelper.setObjectField(booleanState, "contentDescription", ReflectionHelper.getObjectField(booleanState, "label"));
        if ((boolean) ReflectionHelper.getObjectField(booleanState, "value")) {
            i2 = R.drawable.ic_reduce_bright_colors;
        } else
            i2 = R.drawable.ic_reduce_bright_colors;
        ReflectionHelper.setObjectField(booleanState, "icon", ReflectionHelper.callStaticMethod(mResourceIcon, "get", i2));
        param.setResult(null);
        return null;
    }
}
