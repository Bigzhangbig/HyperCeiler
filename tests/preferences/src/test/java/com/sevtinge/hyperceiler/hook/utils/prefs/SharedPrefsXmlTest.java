package com.sevtinge.hyperceiler.hook.utils.prefs;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Test;

public class SharedPrefsXmlTest {
    @Test public void readsAndroidTypedValuesAndEmptyCollections() throws Exception {
        Map<String, Object> values = SharedPrefsXml.parse("<?xml version='1.0' encoding='utf-8' standalone='yes' ?><map>"
            + "<string name='text'>a &amp; &lt;b&gt;\n汉字</string><string name='empty'></string>"
            + "<boolean name='enabled' value='true'/><int name='int' value='-23'/>"
            + "<long name='long' value='2147483648'/><float name='float' value='1.25'/>"
            + "<set name='apps'><string>one</string><string>two,[]</string></set><set name='none'/></map>");
        assertEquals("a & <b>\n汉字", values.get("text"));
        assertEquals("", values.get("empty"));
        assertEquals(true, values.get("enabled"));
        assertEquals(-23, values.get("int"));
        assertEquals(2147483648L, values.get("long"));
        assertEquals(1.25f, values.get("float"));
        assertEquals(new LinkedHashSet<>(Set.of("one", "two,[]")), values.get("apps"));
        assertEquals(Set.of(), values.get("none"));
    }

    @Test public void serializesEveryTypeWithoutLosingStringsOrSetMembers() throws Exception {
        Map<String, Object> values = new LinkedHashMap<>();
        values.put("prefs_key_allow_hook", true);
        values.put("string", "\"汉字<&>\n,[]");
        values.put("empty", "");
        values.put("set", new LinkedHashSet<>(Set.of("one,two", "[three]", "")));
        values.put("empty-set", Set.of());
        values.put("int", -7);
        values.put("long", Long.MAX_VALUE);
        values.put("float", Float.NaN);
        assertEquals(values, SharedPrefsXml.parse(SharedPrefsXml.serialize(values)));
    }

    @Test public void refusesCorruptUnknownOrAmbiguousInput() {
        for (String xml : new String[]{"", "<map><boolean name='a' value='yes'/></map>",
            "<map><int name='a' value='2147483648'/></map>", "<map><string name='a'>unterminated",
            "<map><string name='a'>one</string><string name='a'>two</string></map>",
            "<map><set name='a'><int value='1'/></set></map>",
            "<!DOCTYPE map [<!ENTITY secret SYSTEM 'file:///etc/passwd'>]><map><string name='a'>&secret;</string></map>",
            "<map><unknown name='a'/></map>", "<map/><map/>"}) {
            try { SharedPrefsXml.parse(xml); fail("Accepted corrupt input: " + xml); }
            catch (IOException expected) { /* expected */ }
        }
    }
}
