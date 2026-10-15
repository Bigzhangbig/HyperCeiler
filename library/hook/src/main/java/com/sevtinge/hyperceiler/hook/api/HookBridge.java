/* Copyright (C) 2026 HyperCeiler Contributions. SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

import android.util.Log;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Member;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.XposedFrameworkError;

/** Native API102 hook backend. No framework legacy types, entry points, or reflection helper dependency. */
public final class HookBridge {
    public static final ClassLoader BOOTCLASSLOADER = ClassLoader.getSystemClassLoader();
    private static final String TAG = "HyperCeiler";
    private static final Object LOCK = new Object();
    private static final Map<Executable, IdentityHashMap<HookCallback, XposedInterface.HookHandle>> HOOKS = new HashMap<>();
    private static volatile XposedInterface framework;

    private HookBridge() {}

    /** Call from onModuleLoaded, after the framework has attached its interface. */
    public static void initialize(XposedInterface api) {
        Objects.requireNonNull(api, "framework");
        synchronized (LOCK) {
            if (framework == api) return;
            // A new owner must never retain registrations belonging to a previous interface.
            for (Map<HookCallback, XposedInterface.HookHandle> callbacks : HOOKS.values()) {
                for (XposedInterface.HookHandle handle : callbacks.values()) handle.unhook();
            }
            HOOKS.clear();
            framework = api;
        }
    }

    private static XposedInterface requireFramework() {
        XposedInterface api = framework;
        if (api == null) throw new IllegalStateException("Native hook framework is not initialized");
        return api;
    }

    public static HookCallback.Unhook hookMethod(Member member, HookCallback callback) {
        Executable executable = executable(member);
        Objects.requireNonNull(callback, "callback");
        if (executable instanceof Method && Modifier.isAbstract(executable.getModifiers())) {
            throw new IllegalArgumentException("Cannot hook an abstract method");
        }
        synchronized (LOCK) {
            IdentityHashMap<HookCallback, XposedInterface.HookHandle> callbacks = HOOKS.get(executable);
            if (callbacks != null && callbacks.containsKey(callback)) return callback.new Unhook(member);
            XposedInterface api = requireFramework();
            XposedInterface.HookHandle handle = api.hook(executable)
                .setPriority(callback.priority)
                // Intentional result/throwable replacement must not be treated as hooker failure.
                .setExceptionMode(XposedInterface.ExceptionMode.PASSTHROUGH)
                .intercept(chain -> intercept(api, executable, callback, chain));
            if (callbacks == null) {
                callbacks = new IdentityHashMap<>();
                HOOKS.put(executable, callbacks);
            }
            callbacks.put(callback, handle);
            return callback.new Unhook(member);
        }
    }

    private static Object intercept(XposedInterface api, Executable executable, HookCallback callback,
                                    XposedInterface.Chain chain) throws Throwable {
        HookCallback.MethodHookParam param = new HookCallback.MethodHookParam();
        param.method = executable;
        Object originalReceiver = chain.getThisObject();
        param.thisObject = originalReceiver;
        // Each native callback owns its Chain input snapshot. Its after callback sees its
        // own args/extras, not downstream callbacks' replacement args or receiver.
        param.args = chain.getArgs().toArray();
        try {
            callback.beforeHookedMethod(param);
        } catch (XposedFrameworkError error) {
            throw error;
        } catch (Throwable error) {
            report(api, error);
            // Keep argument/receiver edits, but a broken callback cannot force an early outcome.
            param.result = null; param.throwable = null; param.returnEarly = false;
        }
        if (!param.returnEarly) {
            try {
                Object[] args = Objects.requireNonNull(param.args, "Callback arguments");
                if (!Modifier.isStatic(executable.getModifiers()) && param.thisObject != originalReceiver) {
                    param.result = chain.proceedWith(param.thisObject, args);
                } else {
                    param.result = chain.proceed(args);
                }
                param.throwable = null;
            } catch (XposedFrameworkError error) {
                throw error;
            } catch (Throwable error) {
                param.result = null; param.throwable = error;
            }
        }
        Object previousResult = param.result;
        Throwable previousThrowable = param.throwable;
        boolean previousEarly = param.returnEarly;
        try {
            callback.afterHookedMethod(param);
        } catch (XposedFrameworkError error) {
            throw error;
        } catch (Throwable error) {
            report(api, error);
            param.result = previousResult; param.throwable = previousThrowable; param.returnEarly = previousEarly;
        }
        return param.getResultOrThrowable();
    }

    public static Set<HookCallback.Unhook> hookAllMethods(Class<?> type, String name, HookCallback callback) {
        Set<HookCallback.Unhook> result = new LinkedHashSet<>();
        for (Method method : type.getDeclaredMethods()) {
            if (method.getName().equals(name)) result.add(hookMethod(method, callback));
        }
        return result;
    }

    public static Set<HookCallback.Unhook> hookAllConstructors(Class<?> type, HookCallback callback) {
        Set<HookCallback.Unhook> result = new LinkedHashSet<>();
        for (Constructor<?> constructor : type.getDeclaredConstructors()) result.add(hookMethod(constructor, callback));
        return result;
    }

    public static void unhookMethod(Member member, HookCallback callback) {
        Executable executable = executable(member);
        synchronized (LOCK) {
            IdentityHashMap<HookCallback, XposedInterface.HookHandle> callbacks = HOOKS.get(executable);
            if (callbacks == null) return;
            XposedInterface.HookHandle handle = callbacks.get(callback);
            if (handle == null) return;
            handle.unhook();
            callbacks.remove(callback);
            if (callbacks.isEmpty()) HOOKS.remove(executable);
        }
    }

    public static Object invokeOriginalMethod(Member member, Object receiver, Object[] args)
        throws InvocationTargetException, IllegalAccessException {
        XposedInterface api = requireFramework();
        Executable executable = executable(member);
        Object[] actual = args == null ? new Object[0] : args;
        if (executable instanceof Method method) {
            var invoker = api.getInvoker(method).setType(XposedInterface.Invoker.Type.ORIGIN);
            // invoke() performs virtual dispatch; the requested declaring method is the origin.
            return Modifier.isStatic(method.getModifiers()) ? invoker.invoke(receiver, actual)
                : invoker.invokeSpecial(receiver, actual);
        }
        return api.getInvoker((Constructor<?>) executable).setType(XposedInterface.Invoker.Type.ORIGIN).invokeSpecial(receiver, actual);
    }

    public static boolean deoptimizeMethod(Member member) { return requireFramework().deoptimize(executable(member)); }
    public static int getXposedVersion() { XposedInterface api = framework; return api == null ? 0 : api.getApiVersion(); }

    public static void log(String message) {
        XposedInterface api = framework;
        if (api != null) {
            try { api.log(Log.INFO, TAG, String.valueOf(message)); return; }
            catch (RuntimeException ignored) { /* fall through to application logging */ }
        }
        fallback(Log.INFO, String.valueOf(message));
    }

    public static void log(Throwable error) {
        XposedInterface api = framework;
        if (api != null) { report(api, error); return; }
        fallback(Log.ERROR, stackTrace(error));
    }

    private static void report(XposedInterface api, Throwable error) {
        try { api.log(Log.ERROR, TAG, "Hook callback failed", error); }
        catch (RuntimeException ignored) { fallback(Log.ERROR, stackTrace(error)); }
    }

    private static String stackTrace(Throwable error) {
        if (error == null) return "null";
        StringWriter output = new StringWriter();
        error.printStackTrace(new PrintWriter(output));
        return output.toString();
    }

    private static void fallback(int priority, String message) {
        try { Log.println(priority, TAG, message); }
        catch (RuntimeException | LinkageError ignored) {
            // android.jar is a JVM interface stub in tests; logging must not break preferences/UI.
            System.err.println(TAG + ": " + message);
        }
    }

    private static Executable executable(Member member) {
        if (member instanceof Executable executable) return executable;
        throw new IllegalArgumentException("Hook target must be a method or constructor");
    }
}
