/* Copyright (C) 2026 HyperCeiler Contributions. SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

import java.util.HashMap;
import java.util.Map;

/** Per-invocation module data; never retains a framework Chain. */
public class HookExtras {
    private Map<String, Object> extras;

    public final Object getObjectExtra(String key) {
        return extras == null ? null : extras.get(key);
    }

    public final void setObjectExtra(String key, Object value) {
        if (extras == null) extras = new HashMap<>();
        extras.put(key, value);
    }
}
