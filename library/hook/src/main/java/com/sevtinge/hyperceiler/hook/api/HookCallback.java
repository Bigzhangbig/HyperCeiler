/* Copyright (C) 2026 HyperCeiler Contributions. SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

import java.lang.reflect.Member;

import io.github.libxposed.api.XposedInterface;

/** Module-owned callback syntax executed by the native libxposed interceptor backend. */
public class HookCallback {
    public static final int PRIORITY_DEFAULT = XposedInterface.PRIORITY_DEFAULT;
    public static final int PRIORITY_HIGHEST = XposedInterface.PRIORITY_HIGHEST;
    public static final int PRIORITY_LOWEST = XposedInterface.PRIORITY_LOWEST;
    public final int priority;

    public HookCallback() { this(PRIORITY_DEFAULT); }
    public HookCallback(int priority) { this.priority = priority; }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {}
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {}

    public static final class MethodHookParam extends HookExtras {
        public Member method;
        public Object thisObject;
        public Object[] args;
        Object result;
        Throwable throwable;
        boolean returnEarly;

        public Object getResult() { return result; }
        public void setResult(Object value) { result = value; throwable = null; returnEarly = true; }
        public Throwable getThrowable() { return throwable; }
        public boolean hasThrowable() { return throwable != null; }
        public void setThrowable(Throwable error) { throwable = error; result = null; returnEarly = true; }
        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) throw throwable;
            return result;
        }
    }

    /** Unregistering is idempotent and uses the callback's identity, as registration does. */
    public final class Unhook {
        private final Member method;
        Unhook(Member method) { this.method = method; }
        public Member getHookedMethod() { return method; }
        public HookCallback getCallback() { return HookCallback.this; }
        public void unhook() { HookBridge.unhookMethod(method, HookCallback.this); }
    }
}
