package com.sevtinge.hyperceiler.hook.api;

import org.junit.Test;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import static org.junit.Assert.*;

public class ReflectionHelperTest {
    private static Class<?> isolatedFixture() throws Exception {
        String name = ReflectionFixture.class.getName();
        byte[] bytes;
        try (InputStream stream = ReflectionFixture.class.getResourceAsStream("/" + name.replace('.', '/') + ".class")) {
            assertNotNull(stream);
            bytes = stream.readAllBytes();
        }
        return new ClassLoader(ReflectionFixture.class.getClassLoader()) {
            @Override protected synchronized Class<?> loadClass(String requested, boolean resolve) throws ClassNotFoundException {
                if (!requested.equals(name)) return super.loadClass(requested, resolve);
                Class<?> loaded = findLoadedClass(requested);
                if (loaded == null) loaded = defineClass(requested, bytes, 0, bytes.length);
                if (resolve) resolveClass(loaded);
                return loaded;
            }
        }.loadClass(name);
    }

    @Test public void sameNamedFieldsFromDifferentPluginLoadersDoNotShareCacheEntries() throws Exception {
        Class<?> first = isolatedFixture(), second = isolatedFixture();
        assertSame(first, ReflectionHelper.findField(first, "value").getDeclaringClass());
        assertSame(second, ReflectionHelper.findField(second, "value").getDeclaringClass());
    }

    @Test public void sameNamedMethodsFromDifferentPluginLoadersDoNotShareCacheEntries() throws Exception {
        Class<?> first = isolatedFixture(), second = isolatedFixture();
        assertSame(first, ReflectionHelper.findMethodExact(first, "value").getDeclaringClass());
        assertSame(second, ReflectionHelper.findMethodExact(second, "value").getDeclaringClass());
    }

    @Test public void sameNamedConstructorsFromDifferentPluginLoadersDoNotShareCacheEntries() throws Exception {
        Class<?> first = isolatedFixture(), second = isolatedFixture();
        assertSame(first, ReflectionHelper.findConstructorExact(first).getDeclaringClass());
        assertSame(second, ReflectionHelper.findConstructorExact(second).getDeclaringClass());
    }

    @Test public void failedLookupWithSameNamedForeignParameterCannotPoisonCorrectLookup() throws Exception {
        Class<?> owner = isolatedFixture(), foreign = isolatedFixture();
        assertNull(ReflectionHelper.findMethodExactIfExists(owner, "self", foreign));
        assertNotNull(ReflectionHelper.findMethodExactIfExists(owner, "self", owner));
    }

    @Test public void bestMatchPreservesBoxingSpecificityPrivateAccessAndFieldMutation() {
        ReflectionFixture fixture = new ReflectionFixture();
        assertEquals("integer", ReflectionHelper.callMethod(fixture, "choose", 3));
        assertEquals("number", ReflectionHelper.callMethod(fixture, "choose", 3.5));
        ReflectionHelper.setObjectField(fixture, "value", "changed");
        assertEquals("changed", ReflectionHelper.callMethod(fixture, "value"));
        assertEquals("7", ReflectionHelper.callMethod(ReflectionHelper.newInstance(ReflectionFixture.class, 7), "value"));
        assertEquals(String[][].class, ReflectionHelper.findClass("java.lang.String[][]", getClass().getClassLoader()));
    }

    @Test public void additionalFieldsAreIsolatedByInstanceAndCanBeRemoved() {
        ReflectionFixture first = new ReflectionFixture(), second = new ReflectionFixture();
        assertNull(ReflectionHelper.setAdditionalInstanceField(first, "key", "one"));
        assertEquals("one", ReflectionHelper.getAdditionalInstanceField(first, "key"));
        assertNull(ReflectionHelper.getAdditionalInstanceField(second, "key"));
        assertEquals("one", ReflectionHelper.removeAdditionalInstanceField(first, "key"));
        assertNull(ReflectionHelper.getAdditionalInstanceField(first, "key"));
    }
}
