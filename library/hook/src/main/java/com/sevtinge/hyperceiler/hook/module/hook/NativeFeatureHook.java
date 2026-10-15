/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.module.hook;

import com.hchen.hooktool.core.CoreTool;
import com.hchen.hooktool.hook.AbsHook;

/** Small receiver conveniences over HookTool's native API 102 interception context. */
public abstract class NativeFeatureHook extends AbsHook {
    public final void returnNull() {
        setResult(null);
    }

    public final Object thisObject() {
        return getThisObject();
    }

    public final Object getThisField(String name) {
        return CoreTool.getField(getThisObject(), name);
    }

    public final void setThisField(String name, Object value) {
        CoreTool.setField(getThisObject(), name, value);
    }

    public final Object callThisMethod(String name, Object... args) {
        return CoreTool.callMethod(getThisObject(), name, args);
    }
}
