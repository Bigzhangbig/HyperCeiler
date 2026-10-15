/* Copyright (C) 2026 HyperCeiler Contributions. SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

import io.github.libxposed.api.error.XposedFrameworkError;

/** A deliberate replacement result or throwable, distinct from a broken before callback. */
public abstract class HookReplacement extends HookCallback {
    public HookReplacement() {}
    public HookReplacement(int priority) { super(priority); }

    protected abstract Object replaceHookedMethod(MethodHookParam param) throws Throwable;

    @Override protected final void beforeHookedMethod(MethodHookParam param) throws Throwable {
        try {
            param.setResult(replaceHookedMethod(param));
        } catch (XposedFrameworkError error) {
            throw error;
        } catch (Throwable error) {
            param.setThrowable(error);
        }
    }

    @Override protected final void afterHookedMethod(MethodHookParam param) {}

    public static final HookReplacement DO_NOTHING = returnConstant(PRIORITY_HIGHEST, null);
    public static HookReplacement returnConstant(Object value) { return returnConstant(PRIORITY_DEFAULT, value); }
    public static HookReplacement returnConstant(int priority, Object value) {
        return new HookReplacement(priority) {
            @Override protected Object replaceHookedMethod(MethodHookParam param) { return value; }
        };
    }
}
