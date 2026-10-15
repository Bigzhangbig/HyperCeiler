/* Copyright (C) 2026 HyperCeiler Contributions. SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.utils.prefs;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;
import org.xmlpull.v1.XmlPullParserFactory;
import org.xmlpull.v1.XmlSerializer;

import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/** The typed XML format written by Android SharedPreferences, without framework-private APIs. */
public final class SharedPrefsXml {
    private SharedPrefsXml() {}

    public static Map<String, Object> parse(String xml) throws IOException {
        if (xml == null || xml.isEmpty() || xml.contains("<!DOCTYPE") || xml.contains("<!ENTITY")) {
            throw new IOException("Missing preferences XML or forbidden document declaration");
        }
        try {
            XmlPullParser parser = XmlPullParserFactory.newInstance().newPullParser();
            parser.setInput(new StringReader(xml));
            if (parser.nextTag() != XmlPullParser.START_TAG || !"map".equals(parser.getName())) {
                throw new IOException("Preferences XML must start with map");
            }
            Map<String, Object> result = new LinkedHashMap<>();
            while (parser.nextTag() == XmlPullParser.START_TAG) {
                String type = parser.getName();
                String key = parser.getAttributeValue(null, "name");
                if (key == null || result.containsKey(key)) {
                    throw new IOException("Missing or duplicate preference key");
                }
                Object value;
                switch (type) {
                    case "string" -> value = parser.nextText();
                    case "set" -> value = readSet(parser);
                    case "boolean" -> {
                        String encoded = requiredValue(parser);
                        if (!"true".equals(encoded) && !"false".equals(encoded)) {
                            throw new IOException("Invalid boolean preference");
                        }
                        value = Boolean.valueOf(encoded);
                        requireValueEnd(parser, type);
                    }
                    case "int" -> {
                        value = Integer.valueOf(requiredValue(parser));
                        requireValueEnd(parser, type);
                    }
                    case "long" -> {
                        value = Long.valueOf(requiredValue(parser));
                        requireValueEnd(parser, type);
                    }
                    case "float" -> {
                        value = Float.valueOf(requiredValue(parser));
                        requireValueEnd(parser, type);
                    }
                    default -> throw new IOException("Unsupported preference type: " + type);
                }
                result.put(key, value);
            }
            if (!"map".equals(parser.getName())) throw new IOException("Unterminated preferences map");
            int event;
            while ((event = parser.next()) != XmlPullParser.END_DOCUMENT) {
                if (event == XmlPullParser.START_TAG || (event == XmlPullParser.TEXT && !parser.isWhitespace())) {
                    throw new IOException("Content after preferences map");
                }
            }
            return result;
        } catch (XmlPullParserException | NumberFormatException e) {
            throw new IOException("Invalid preferences XML", e);
        }
    }

    private static String requiredValue(XmlPullParser parser) throws IOException {
        String value = parser.getAttributeValue(null, "value");
        if (value == null) throw new IOException("Missing typed preference value");
        return value;
    }

    private static void requireValueEnd(XmlPullParser parser, String type) throws IOException, XmlPullParserException {
        if (parser.nextTag() != XmlPullParser.END_TAG || !type.equals(parser.getName())) {
            throw new IOException("Unexpected nested preference value");
        }
    }

    private static Set<String> readSet(XmlPullParser parser) throws IOException, XmlPullParserException {
        Set<String> result = new LinkedHashSet<>();
        while (parser.nextTag() == XmlPullParser.START_TAG) {
            if (!"string".equals(parser.getName())) throw new IOException("Non-string set member");
            result.add(parser.nextText());
        }
        if (!"set".equals(parser.getName())) throw new IOException("Unterminated preference set");
        return result;
    }

    public static String serialize(Map<String, ?> values) throws IOException {
        try {
            StringWriter output = new StringWriter();
            XmlSerializer xml = XmlPullParserFactory.newInstance().newSerializer();
            xml.setOutput(output);
            xml.startDocument("UTF-8", true);
            xml.startTag(null, "map");
            for (Map.Entry<String, ?> entry : values.entrySet()) {
                if (entry.getKey() == null) throw new IOException("Null preference key");
                Object value = entry.getValue();
                String type;
                if (value instanceof String) type = "string";
                else if (value instanceof Set<?>) type = "set";
                else if (value instanceof Boolean) type = "boolean";
                else if (value instanceof Integer) type = "int";
                else if (value instanceof Long) type = "long";
                else if (value instanceof Float) type = "float";
                else throw new IOException("Unsupported preference value");
                xml.startTag(null, type).attribute(null, "name", entry.getKey());
                if (value instanceof String string) xml.text(string);
                else if (value instanceof Set<?> set) {
                    for (Object member : set) {
                        if (!(member instanceof String string)) throw new IOException("Non-string set member");
                        xml.startTag(null, "string").text(string).endTag(null, "string");
                    }
                } else xml.attribute(null, "value", value.toString());
                xml.endTag(null, type);
            }
            xml.endTag(null, "map");
            xml.endDocument();
            return output.toString();
        } catch (XmlPullParserException | IllegalArgumentException e) {
            throw new IOException("Cannot encode preferences XML", e);
        }
    }
}
