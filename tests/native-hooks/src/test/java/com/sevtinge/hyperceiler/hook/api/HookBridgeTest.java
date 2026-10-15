package com.sevtinge.hyperceiler.hook.api;

import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.ParcelFileDescriptor;
import io.github.libxposed.api.XposedInterface;
import io.github.libxposed.api.error.HookFailedError;
import org.junit.Before;
import org.junit.Test;

import java.io.FileNotFoundException;
import java.lang.reflect.Constructor;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;

import static org.junit.Assert.*;

public class HookBridgeTest {
    static {
        // SDK android.jar log methods throw on the JVM; the application-process fallback must survive.
        HookBridge.log("pre-initialization logging");
        HookBridge.log(new IllegalStateException("pre-initialization throwable"));
    }

    private FakeFramework api;
    private Method method;
    @Before public void setUp() throws Exception {
        api = new FakeFramework();
        HookBridge.initialize(api);
        method = Sample.class.getDeclaredMethod("plus", int.class);
    }

    @Test public void usesModernPassthroughAndForwardsArgumentsAndPerCallExtras() throws Throwable {
        Object[] callerArgs = {1};
        HookBridge.hookMethod(method, new HookCallback(123) {
            protected void beforeHookedMethod(MethodHookParam p) {
                p.args[0] = (int) p.args[0] + 2;
                p.setObjectExtra("request", p.args[0]);
            }
            protected void afterHookedMethod(MethodHookParam p) {
                assertEquals(3, p.getObjectExtra("request"));
                p.setResult((int) p.getResult() + 3);
            }
        });
        assertEquals(123, api.entries.get(0).priority);
        assertEquals(XposedInterface.ExceptionMode.PASSTHROUGH, api.entries.get(0).mode);
        assertEquals(33, api.call(method, new Sample(), callerArgs, (receiver, args) -> (int) args[0] * 10));
        assertArrayEquals(new Object[]{1}, callerArgs);
    }

    @Test public void eachCallbackRetainsGlobalPriorityAroundOtherModernHooks() throws Throwable {
        List<String> order = new ArrayList<>();
        HookBridge.hookMethod(method, ordered(100, "high", order));
        api.hook(method).setPriority(75).intercept(chain -> {
            order.add("foreign-before"); Object result = chain.proceed(); order.add("foreign-after"); return result;
        });
        HookBridge.hookMethod(method, ordered(0, "low", order));
        assertEquals(3, api.entries.size());
        api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { order.add("origin"); return 2; });
        assertEquals(List.of("high-before", "foreign-before", "low-before", "origin", "low-after", "foreign-after", "high-after"), order);
    }

    @Test public void earlyResultSkipsLowerHooksAndOriginButRunsItsAfter() throws Throwable {
        List<String> order = new ArrayList<>();
        HookBridge.hookMethod(method, ordered(0, "low", order));
        HookBridge.hookMethod(method, new HookCallback(100) {
            protected void beforeHookedMethod(MethodHookParam p) { order.add("before"); p.setResult(7); }
            protected void afterHookedMethod(MethodHookParam p) { order.add("after"); p.setResult((int)p.getResult() + 1); }
        });
        assertEquals(8, api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { fail("origin must be skipped"); return null; }));
        assertEquals(List.of("before", "after"), order);
    }

    @Test public void receiverChangesUseProceedWithAndStaticCallsDoNot() throws Throwable {
        Sample original = new Sample(); Sample replacement = new Sample();
        HookBridge.hookMethod(method, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { p.thisObject = replacement; p.args = new Object[]{4}; }
        });
        assertEquals(4, api.call(method, original, new Object[]{1}, (receiver, args) -> { assertSame(replacement, receiver); return args[0]; }));
        assertEquals(1, api.proceedWithCalls);
        Method staticMethod = Sample.class.getDeclaredMethod("staticValue", int.class);
        HookBridge.hookMethod(staticMethod, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { p.thisObject = replacement; p.args[0] = 9; }
        });
        assertEquals(9, api.call(staticMethod, null, new Object[]{1}, (receiver, args) -> { assertNull(receiver); return args[0]; }));
        assertEquals(1, api.proceedWithCalls);
    }

    @Test public void intentionalThrowableIsPropagatedWithoutCallingOrigin() throws Throwable {
        Throwable intended = new IllegalStateException("intentional");
        HookBridge.hookMethod(method, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { p.setThrowable(intended); }
        });
        assertThrownSame(intended, () -> api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { fail("origin"); return null; }));
    }

    @Test public void afterCanReplaceOriginThrowableWithResultOrAResultWithThrowable() throws Throwable {
        Throwable origin = new IllegalArgumentException("origin");
        HookCallback.Unhook handle = HookBridge.hookMethod(method, new HookCallback() {
            protected void afterHookedMethod(MethodHookParam p) { assertSame(origin, p.getThrowable()); p.setResult(7); }
        });
        assertEquals(7, api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { throw origin; }));
        handle.unhook();
        Throwable replacement = new IllegalStateException("replacement");
        HookBridge.hookMethod(method, new HookCallback() {
            protected void afterHookedMethod(MethodHookParam p) { p.setThrowable(replacement); }
        });
        assertThrownSame(replacement, () -> api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> 2));
    }

    @Test public void callbackErrorsAreIsolatedAndAfterOutcomeChangesAreRolledBack() throws Throwable {
        HookBridge.hookMethod(method, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) {
                p.args[0] = 5; p.setResult(99); throw new IllegalStateException("bad before");
            }
            protected void afterHookedMethod(MethodHookParam p) { p.setResult(100); throw new IllegalStateException("bad after"); }
        });
        assertEquals(6, api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> (int)args[0] + 1));
        assertEquals(2, api.loggedErrors.size());
    }

    @Test public void failingAfterPreservesTheExactOriginalThrowable() throws Throwable {
        Throwable origin = new IllegalArgumentException("origin");
        HookBridge.hookMethod(method, new HookCallback() {
            protected void afterHookedMethod(MethodHookParam p) { p.setResult(1); throw new IllegalStateException("bad after"); }
        });
        assertThrownSame(origin, () -> api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { throw origin; }));
    }

    @Test public void fatalFrameworkFailureIsNotConvertedIntoFallback() throws Throwable {
        HookFailedError fatal = new HookFailedError("broken framework");
        HookBridge.hookMethod(method, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { throw fatal; }
        });
        assertThrownSame(fatal, () -> api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { fail("no fallback"); return null; }));
    }

    @Test public void constructorsKeepTheirReceiverAndProceedExactlyOnce() throws Throwable {
        Constructor<Sample> constructor = Sample.class.getDeclaredConstructor(int.class);
        Sample receiver = new Sample(); int[] calls = {0};
        HookBridge.hookMethod(constructor, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { assertSame(receiver, p.thisObject); p.args[0] = 7; }
            protected void afterHookedMethod(MethodHookParam p) { assertSame(receiver, p.thisObject); assertEquals(7, receiver.value); }
        });
        api.call(constructor, receiver, new Object[]{1}, (self, args) -> { assertSame(receiver, self); calls[0]++; receiver.value = (int)args[0]; return null; });
        assertEquals(1, calls[0]);
        assertEquals(7, receiver.value);
    }

    @Test public void originalMethodInvokerExplicitlyBypassesFullHookChain() throws Exception {
        int[] calls = {0};
        HookBridge.hookMethod(method, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { calls[0]++; p.setResult(999); }
        });
        Sample receiver = new Sample();
        assertEquals(3, HookBridge.invokeOriginalMethod(method, receiver, new Object[]{2}));
        assertEquals(0, calls[0]);
        assertTrue(api.lastInvokerType instanceof XposedInterface.Invoker.Type.Origin);
        assertSame(receiver, api.lastInvokerReceiver);
    }

    @Test public void originalInvokerPreservesReflectionExceptionWrappingAndExistingConstructorReceiver() throws Exception {
        Method throwing = Sample.class.getDeclaredMethod("throwing");
        try { HookBridge.invokeOriginalMethod(throwing, new Sample(), new Object[]{}); fail("must throw"); }
        catch (InvocationTargetException expected) { assertSame(Sample.FAILURE, expected.getCause()); }
        Sample receiver = new Sample();
        Constructor<Sample> constructor = Sample.class.getDeclaredConstructor(int.class);
        assertNull(HookBridge.invokeOriginalMethod(constructor, receiver, new Object[]{4}));
        assertSame(receiver, api.lastInvokerReceiver);
        assertEquals(0, api.constructorAllocations);
        assertTrue(api.lastInvokerType instanceof XposedInterface.Invoker.Type.Origin);
    }

    @Test public void duplicateCallbackRegistrationAndUnhookAreIdempotent() {
        HookCallback callback = new HookCallback();
        HookCallback.Unhook first = HookBridge.hookMethod(method, callback);
        HookCallback.Unhook second = HookBridge.hookMethod(method, callback);
        assertEquals(1, api.entries.size());
        assertSame(method, first.getHookedMethod()); assertSame(callback, first.getCallback());
        first.unhook(); second.unhook(); HookBridge.unhookMethod(method, callback);
        assertEquals(1, api.unhookCalls); assertEquals(0, api.activeCount());
    }

    @Test public void hooksOnlyDeclaredOverloadsAndConstructorsAndRoutesDeoptimization() throws Exception {
        assertEquals(2, HookBridge.hookAllMethods(Sample.class, "overloaded", new HookCallback()).size());
        assertEquals(0, HookBridge.hookAllMethods(Sample.class, "inherited", new HookCallback()).size());
        assertEquals(2, HookBridge.hookAllConstructors(Sample.class, new HookCallback()).size());
        assertTrue(HookBridge.deoptimizeMethod(method)); assertSame(method, api.deoptimized);
        assertEquals(102, HookBridge.getXposedVersion());
    }

    @Test public void replacementConstantNothingAndThrownReplacementHaveExpectedBehavior() throws Throwable {
        HookCallback.Unhook first = HookBridge.hookMethod(method, HookReplacement.returnConstant(41));
        assertEquals(41, api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { fail("origin"); return null; }));
        first.unhook();
        HookCallback.Unhook second = HookBridge.hookMethod(method, HookReplacement.DO_NOTHING);
        assertNull(api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { fail("origin"); return null; }));
        second.unhook();
        Throwable failure = new IllegalStateException("replacement");
        HookBridge.hookMethod(method, new HookReplacement() {
            protected Object replaceHookedMethod(MethodHookParam p) throws Throwable { throw failure; }
        });
        assertThrownSame(failure, () -> api.call(method, new Sample(), new Object[]{1}, (receiver, args) -> { fail("origin"); return null; }));
    }

    @Test public void parallelCallsDoNotShareArgsOrExtras() throws Exception {
        HookBridge.hookMethod(method, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { p.setObjectExtra("arg", p.args[0]); }
            protected void afterHookedMethod(MethodHookParam p) { assertEquals(p.args[0], p.getObjectExtra("arg")); }
        });
        try (var executor = Executors.newFixedThreadPool(4)) {
            List<Callable<Integer>> tasks = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                int number = i;
                tasks.add(() -> { try { return (int)api.call(method, new Sample(), new Object[]{number}, (receiver, args) -> (int)args[0] + 1); }
                    catch (Throwable error) { throw new AssertionError(error); } });
            }
            var results = executor.invokeAll(tasks);
            for (int i = 0; i < results.size(); i++) assertEquals(i + 1, (int)results.get(i).get());
        }
    }

    @Test public void nativeInputsRemainLocalSnapshotsRatherThanSharingDownstreamMutation() throws Throwable {
        Sample original = new Sample(); Sample replacement = new Sample();
        List<Object> higherAfter = new ArrayList<>();
        HookBridge.hookMethod(method, new HookCallback(100) {
            protected void beforeHookedMethod(MethodHookParam p) { p.args[0] = 2; p.setObjectExtra("owner", "high"); }
            protected void afterHookedMethod(MethodHookParam p) { higherAfter.add(p.args[0]); higherAfter.add(p.thisObject); higherAfter.add(p.getObjectExtra("owner")); }
        });
        HookBridge.hookMethod(method, new HookCallback(0) {
            protected void beforeHookedMethod(MethodHookParam p) { p.args = new Object[]{7}; p.thisObject = replacement; p.setObjectExtra("owner", "low"); }
        });
        assertEquals(8, api.call(method, original, new Object[]{1}, (self, args) -> {
            assertSame(replacement, self); assertEquals(7, args[0]); return 8;
        }));
        assertEquals(List.of(2, original, "high"), higherAfter);
        assertEquals(List.of(), api.loggedErrors);
    }

    @Test public void recursiveCallsHaveIndependentParametersAndExtras() throws Throwable {
        List<Integer> after = new ArrayList<>();
        HookBridge.hookMethod(method, new HookCallback() {
            protected void beforeHookedMethod(MethodHookParam p) { p.setObjectExtra("argument", p.args[0]); }
            protected void afterHookedMethod(MethodHookParam p) {
                assertEquals(p.args[0], p.getObjectExtra("argument")); after.add((int)p.args[0]);
            }
        });
        FakeFramework.Origin[] origin = new FakeFramework.Origin[1];
        origin[0] = (self, args) -> (int)args[0] == 0 ? 0 : (int)args[0] + (int)api.call(method, self, new Object[]{(int)args[0] - 1}, origin[0]);
        assertEquals(6, api.call(method, new Sample(), new Object[]{3}, origin[0]));
        assertEquals(List.of(0, 1, 2, 3), after);
        assertEquals(List.of(), api.loggedErrors);
    }

    @Test public void originalInvocationUsesTheSpecifiedDeclaringMethodInsteadOfSubclassOverride() throws Exception {
        Method base = VirtualBase.class.getDeclaredMethod("label");
        assertEquals("base", HookBridge.invokeOriginalMethod(base, new VirtualChild(), new Object[0]));
        assertTrue(api.lastInvokerWasSpecial);
        Method staticMethod = Sample.class.getDeclaredMethod("staticValue", int.class);
        api.lastInvokerWasSpecial = false;
        assertEquals(4, HookBridge.invokeOriginalMethod(staticMethod, null, new Object[]{4}));
        assertFalse(api.lastInvokerWasSpecial);
    }

    private static HookCallback ordered(int priority, String name, List<String> order) {
        return new HookCallback(priority) {
            protected void beforeHookedMethod(MethodHookParam p) { order.add(name + "-before"); }
            protected void afterHookedMethod(MethodHookParam p) { order.add(name + "-after"); }
        };
    }
    @FunctionalInterface interface Throwing { Object run() throws Throwable; }
    private static void assertThrownSame(Throwable expected, Throwing call) throws Throwable {
        try { call.run(); fail("Expected throwable"); } catch (Throwable actual) { assertSame(expected, actual); }
    }
    public static class VirtualBase { public String label() { return "base"; } }
    public static class VirtualChild extends VirtualBase { @Override public String label() { return "child"; } }
    public static class Parent { public void inherited() {} }
    public static class Sample extends Parent {
        static final Throwable FAILURE = new IllegalStateException("original");
        int value;
        public Sample() {} public Sample(int value) { this.value = value; }
        public int plus(int number) { return number + 1; }
        public static int staticValue(int number) { return number; }
        public void overloaded() {} public void overloaded(int number) {}
        public void throwing() throws Throwable { throw FAILURE; }
    }

    /** Test double of the official interface; only supplies hook chains, never adapter behavior. */
    private static final class FakeFramework implements XposedInterface {
        final List<Entry> entries = new ArrayList<>();
        final List<Throwable> loggedErrors = Collections.synchronizedList(new ArrayList<>());
        int proceedWithCalls, unhookCalls, constructorAllocations;
        Executable deoptimized; Invoker.Type lastInvokerType; Object lastInvokerReceiver; boolean lastInvokerWasSpecial;
        record Entry(Executable executable, int priority, ExceptionMode mode, Hooker hooker, boolean[] active) {}
        @FunctionalInterface interface Origin { Object call(Object receiver, Object[] args) throws Throwable; }
        Object call(Executable executable, Object receiver, Object[] args, Origin origin) throws Throwable {
            List<Entry> snapshot = entries.stream().filter(e -> e.active()[0] && e.executable().equals(executable))
                .sorted(Comparator.comparingInt(Entry::priority).reversed()).toList();
            return step(snapshot, 0, executable, receiver, args, origin);
        }
        Object step(List<Entry> snapshot, int index, Executable executable, Object receiver, Object[] args, Origin origin) throws Throwable {
            if (index == snapshot.size()) return origin.call(receiver, args);
            return snapshot.get(index).hooker().intercept(new Chain() {
                public Executable getExecutable() { return executable; }
                public Object getThisObject() { return receiver; }
                public List<Object> getArgs() { return Collections.unmodifiableList(Arrays.asList(args.clone())); }
                public Object getArg(int i) { return args[i]; }
                public Object proceed() throws Throwable { return step(snapshot, index + 1, executable, receiver, args, origin); }
                public Object proceed(Object[] next) throws Throwable { return step(snapshot, index + 1, executable, receiver, next, origin); }
                public Object proceedWith(Object next) throws Throwable { proceedWithCalls++; return step(snapshot, index + 1, executable, next, args, origin); }
                public Object proceedWith(Object next, Object[] nextArgs) throws Throwable { proceedWithCalls++; return step(snapshot, index + 1, executable, next, nextArgs, origin); }
            });
        }
        int activeCount() { return (int)entries.stream().filter(e -> e.active()[0]).count(); }
        public HookBuilder hook(Executable executable) {
            return new HookBuilder() {
                int priority = PRIORITY_DEFAULT; ExceptionMode mode = ExceptionMode.DEFAULT; String id;
                public HookBuilder setPriority(int next) { priority = next; return this; }
                public HookBuilder setExceptionMode(ExceptionMode next) { mode = next; return this; }
                public HookBuilder setId(String next) { id = next; return this; }
                public HookHandle intercept(Hooker hooker) {
                    Entry entry = new Entry(executable, priority, mode, hooker, new boolean[]{true}); entries.add(entry);
                    return new HookHandle() {
                        public Executable getExecutable() { return executable; }
                        public String getId() { return id; }
                        public void unhook() { if (entry.active()[0]) { entry.active()[0] = false; unhookCalls++; } }
                        public HookHandle replaceHook(Hooker next) { throw new UnsupportedOperationException(); }
                    };
                }
            };
        }
        public HookBuilder hookClassInitializer(Class<?> type) { throw new UnsupportedOperationException(); }
        public boolean deoptimize(Executable executable) { deoptimized = executable; return true; }
        public Invoker<?, Method> getInvoker(Method method) {
            class MethodInvoker implements Invoker<MethodInvoker, Method> {
                Type type = Type.Chain.FULL;
                public MethodInvoker setType(Type next) { type = next; return this; }
                public Object invoke(Object receiver, Object... args) throws InvocationTargetException, IllegalAccessException {
                    lastInvokerType = type; lastInvokerReceiver = receiver;
                    if (type instanceof Type.Origin) return method.invoke(receiver, args);
                    try { return call(method, receiver, args, (self, actual) -> {
                        try { return method.invoke(self, actual); } catch (InvocationTargetException e) { throw e.getCause(); }
                    }); } catch (IllegalAccessException e) { throw e; } catch (Throwable e) { throw new InvocationTargetException(e); }
                }
                public Object invokeSpecial(Object receiver, Object... args) throws InvocationTargetException, IllegalAccessException {
                    lastInvokerWasSpecial = true; lastInvokerType = type; lastInvokerReceiver = receiver;
                    try { return MethodHandles.privateLookupIn(method.getDeclaringClass(), MethodHandles.lookup())
                        .unreflectSpecial(method, method.getDeclaringClass()).bindTo(receiver).invokeWithArguments(args); }
                    catch (IllegalAccessException e) { throw e; }
                    catch (Throwable error) { throw new InvocationTargetException(error); }
                }
            }
            return new MethodInvoker();
        }
        public <T> CtorInvoker<T> getInvoker(Constructor<T> constructor) {
            return new CtorInvoker<>() {
                Invoker.Type type = Invoker.Type.Chain.FULL;
                public CtorInvoker<T> setType(Invoker.Type next) { type = next; return this; }
                public Object invoke(Object receiver, Object... args) { lastInvokerType = type; lastInvokerReceiver = receiver; return null; }
                public Object invokeSpecial(Object receiver, Object... args) { lastInvokerWasSpecial = true; return invoke(receiver, args); }
                public T newInstance(Object... args) throws InvocationTargetException, IllegalAccessException, InstantiationException { constructorAllocations++; return constructor.newInstance(args); }
                public <U> U newInstanceSpecial(Class<U> type, Object... args) { throw new UnsupportedOperationException(); }
            };
        }
        public String getFrameworkName() { return "test"; }
        public String getFrameworkVersion() { return "test"; }
        public long getFrameworkVersionCode() { return 1; }
        public long getFrameworkProperties() { return 0; }
        public void log(int priority, String tag, String message) {}
        public void log(int priority, String tag, String message, Throwable error) { loggedErrors.add(error); }
        public ApplicationInfo getModuleApplicationInfo() { throw new UnsupportedOperationException(); }
        public SharedPreferences getRemotePreferences(String group) { throw new UnsupportedOperationException(); }
        public String[] listRemoteFiles() { return new String[0]; }
        public ParcelFileDescriptor openRemoteFile(String name) throws FileNotFoundException { throw new FileNotFoundException(); }
    }
}
