/* Actual upstream helpers + official API102 interfaces; framework execution is simulated. */
package com.hchen.hooktool;
import io.github.libxposed.api.*;
import io.github.kyuubiran.ezxhelper.xposed.EzXposed;
import io.github.kyuubiran.ezxhelper.xposed.dsl.HookFactory;
import io.github.kyuubiran.ezxhelper.xposed.common.HookParam;
import com.hchen.hooktool.hook.AbsHook;
import com.hchen.hooktool.core.CoreTool;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

public class NativeHelpersTest {
    static XposedInterface.Hooker installed;
    static Method target;
    static XposedInterface.ExceptionMode mode;
    static final IllegalStateException ORIGIN = new IllegalStateException("origin");
    static int originalCalls;
    static int priority;
    static int assertions;
    public static int target(int value) { originalCalls++; return value + 1; }
    public static int origin(int value) { originalCalls++; throw ORIGIN; }
    @SuppressWarnings("unchecked")
    static <T> T proxy(Class<T> kind, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(kind.getClassLoader(),new Class<?>[]{kind},handler);
    }
    static Object defaultValue(Method method) {
        if(method.getReturnType()==boolean.class) return true;
        if(method.getReturnType()==int.class) return 102;
        if(method.getReturnType()==long.class) return 0L;
        return null;
    }
    static XposedInterface.HookBuilder builder() {
        mode = null;
        return proxy(XposedInterface.HookBuilder.class,(p,m,a)-> {
            if(m.getName().equals("setPriority")) priority=(Integer)a[0];
            if(m.getName().equals("setExceptionMode")) mode=(XposedInterface.ExceptionMode)a[0];
            if(m.getName().equals("intercept")) {
                installed=(XposedInterface.Hooker)a[0];
                return proxy(XposedInterface.HookHandle.class,(hp,hm,ha)-> {
                    if(hm.getName().equals("getExecutable")) return target;
                    if(hm.getName().equals("getId")) return "probe";
                    return defaultValue(hm);
                });
            }
            return p;
        });
    }
    static Object invoke(int argument) throws Throwable {
        boolean[] proceeded={false};
        Object[] proceededResult={null};
        Throwable[] proceededThrowable={null};
        XposedInterface.Chain chain=proxy(XposedInterface.Chain.class,(p,m,a)-> {
            return switch(m.getName()) {
                case "getExecutable" -> target;
                case "getThisObject" -> null;
                case "getArgs" -> List.of(argument);
                case "getArg" -> argument;
                case "proceed", "proceedWith" -> {
                    Object[] arguments=a==null||a.length==0?new Object[]{argument}:
                        (Object[])a[a.length-1];
                    proceeded[0]=true;
                    try { proceededResult[0]=target.invoke(null,arguments); yield proceededResult[0]; }
                    catch(InvocationTargetException e){proceededThrowable[0]=e.getCause();throw e.getCause();}
                }
                default -> throw new AssertionError(m);
            };
        });
        try { return installed.intercept(chain); }
        catch(Throwable hookerError) {
            // Simulate API102's documented framework boundary, not a direct interceptor call.
            if(mode==XposedInterface.ExceptionMode.PASSTHROUGH) throw hookerError;
            if(!proceeded[0]) return chain.proceed();
            if(proceededThrowable[0]!=null) throw proceededThrowable[0];
            return proceededResult[0];
        }
    }
    static void check(boolean valid,String message) {
        assertions++; if(!valid)throw new AssertionError(message);
    }
    static void expectThrow(Throwable expected) throws Throwable {
        try { invoke(1); throw new AssertionError("Expected exception: "+expected); }
        catch(Throwable actual) { check(actual==expected,"exact intentional/original throwable must survive"); }
    }
    static void install(boolean ez,XposedInterface.ExceptionMode requested,Throwable before,Throwable after) {
        if(ez) {
            HookFactory.createMethodHook(target,(Consumer<HookFactory>)factory->{
                if(requested!=null) factory.exceptionMode(requested);
                factory.before((Consumer<HookParam>)param->{if(before!=null)param.setThrowable(before);});
                factory.after((Consumer<HookParam>)param->{if(after!=null)param.setThrowable(after);});
            });
        } else {
            CoreTool.hook(target,new AbsHook(requested){
                @Override public void before(){if(before!=null)setThrowable(before);}
                @Override public void after(){if(after!=null)setThrowable(after);}
                @Override public boolean onThrow(StageEnum stage,Throwable error){return false;}
            });
        }
    }
    /** Real upstream AAR code with a simulated official ExceptionMode boundary; no device hooking. */
    @org.junit.Test public void actualNativeHelpersPreserveArgumentsResultsAndExplicitExceptionModes() throws Throwable {
        installed = null; mode = null; priority = 0; assertions = 0; originalCalls = 0;
        target=NativeHelpersTest.class.getDeclaredMethod("target",int.class);
        XposedInterface base=proxy(XposedInterface.class,(p,m,a)-> {
            if(m.getName().equals("hook")) return builder();
            if(m.getName().equals("getFrameworkName")) return "probe";
            return defaultValue(m);
        });
        var loaded=proxy(XposedModuleInterface.ModuleLoadedParam.class,(p,m,a)->defaultValue(m));
        EzXposed.initOnModuleLoaded(base,loaded);
        HookFactory.createMethodHook(target,(Consumer<HookFactory>)factory->{
            factory.before((Consumer<HookParam>)param->param.getArgs()[0]=4);
            factory.after((Consumer<HookParam>)param->param.setResult((Integer)param.getResult()*3));
        });
        check(Objects.equals(15,invoke(1)),"Ez native before args and after result");
        check(originalCalls==1,"Ez native proceeds once");
        HookFactory.createMethodHook(target,(Consumer<HookFactory>)factory->factory.before(
            (Consumer<HookParam>)param->param.setResult(27)));
        check(Objects.equals(27,invoke(1)),"Ez native skip result");
        check(originalCalls==1,"Ez native skip original");
        XposedInterfaceWrapper wrapper=new XposedInterfaceWrapper();
        wrapper.attachFramework(base,()->{});
        ModuleData.setWrapper(wrapper);
        ModuleConfig.setLogLevel(0);
        ModuleConfig.setShowHookSuccessLog(false);
        CoreTool.hook(target,new AbsHook(){
            @Override public void before(){setArg(0,4);}
            @Override public void after(){setResult((Integer)getResult()*3);}
        });
        check(Objects.equals(15,invoke(1)),"HookTool native before args and after result");
        check(originalCalls==2,"HookTool native proceeds once");
        CoreTool.hook(target,new AbsHook(){@Override public void before(){setResult(39);}});
        check(Objects.equals(39,invoke(1)),"HookTool native skip result");
        check(originalCalls==2,"HookTool native skip original");
        for(boolean ez:new boolean[]{true,false}) {
            String name=ez?"Ez":"HookTool";
            Throwable active=new IllegalArgumentException("intentional replacement");
            target=NativeHelpersTest.class.getDeclaredMethod("target",int.class);
            int before=originalCalls;
            install(ez,null,active,null);
            check(Objects.equals(2,invoke(1)),name+" DEFAULT/PROTECTIVE restores original before replacement");
            check(originalCalls==before+1,name+" protective before proceeds once");
            before=originalCalls;
            install(ez,XposedInterface.ExceptionMode.PASSTHROUGH,active,null);
            expectThrow(active);
            check(originalCalls==before,name+" passthrough before skips original");
            before=originalCalls;
            install(ez,null,null,active);
            check(Objects.equals(2,invoke(1)),name+" DEFAULT/PROTECTIVE restores original after replacement");
            check(originalCalls==before+1,name+" protective after proceeds once");
            before=originalCalls;
            install(ez,XposedInterface.ExceptionMode.PASSTHROUGH,null,active);
            expectThrow(active);
            check(originalCalls==before+1,name+" passthrough after proceeds once");
            target=NativeHelpersTest.class.getDeclaredMethod("origin",int.class);
            for(var requested:new XposedInterface.ExceptionMode[]{null,XposedInterface.ExceptionMode.PASSTHROUGH}) {
                before=originalCalls;
                install(ez,requested,null,null);
                expectThrow(ORIGIN);
                check(originalCalls==before+1,name+" original exception propagates after one proceed");
            }
        }
        System.out.println("Native helper API102 probe passed: "+assertions+" assertions; actual native AARs + simulated API102 ExceptionMode boundary");
    }
}
