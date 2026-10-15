/*
 * This file is part of HyperCeiler.

 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.

 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.

 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.

 * Copyright (C) 2023-2025 HyperCeiler Contributions
 */
package com.sevtinge.hyperceiler.hook.utils.prefs;

import java.util.AbstractMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.Map;

public class PrefsMap<K, V> extends AbstractMap<K, V> {
    private volatile Map<K, V> values = Collections.emptyMap();

    /** Replace a complete framework snapshot without exposing an intermediate empty map. */
    public synchronized void replace(Map<? extends K, ? extends V> snapshot) {
        values = Collections.unmodifiableMap(new HashMap<>(snapshot));
    }

    @Override
    public Set<Entry<K, V>> entrySet() {
        return values.entrySet();
    }

    @Override
    public V get(Object key) {
        return values.get(key);
    }

    @Override
    public boolean containsKey(Object key) {
        return values.containsKey(key);
    }

    @Override
    public synchronized V put(K key, V value) {
        Map<K, V> next = new HashMap<>(values);
        V previous = next.put(key, value);
        values = Collections.unmodifiableMap(next);
        return previous;
    }

    @Override
    public synchronized void putAll(Map<? extends K, ? extends V> additions) {
        Map<K, V> next = new HashMap<>(values);
        next.putAll(additions);
        values = Collections.unmodifiableMap(next);
    }

    @Override
    public synchronized V remove(Object key) {
        Map<K, V> next = new HashMap<>(values);
        V previous = next.remove(key);
        values = Collections.unmodifiableMap(next);
        return previous;
    }

    @Override
    public synchronized void clear() {
        values = Collections.emptyMap();
    }

    public Object getObject(String key, Object defValue) {
        Object value = get(key);
        return value == null ? defValue : value;
    }

    public int getInt(String key, int defValue) {
        key = "prefs_key_" + key;
        Object value = get(key);
        return value == null ? defValue : (Integer) value;
    }

    public String getString(String key, String defValue) {
        key = "prefs_key_" + key;
        Object value = get(key);
        return value == null ? defValue : (String) value;
    }

    public int getStringAsInt(String key, int defValue) {
        key = "prefs_key_" + key;
        Object value = get(key);
        return value == null ? defValue : Integer.parseInt((String) value);
    }

    @SuppressWarnings("unchecked")
    public Set<String> getStringSet(String key) {
        key = "prefs_key_" + key;
        Object value = get(key);
        return value == null ? new LinkedHashSet<>() : (Set<String>) value;
    }

    public boolean getBoolean(String key) {
        key = "prefs_key_" + key;
        Object value = get(key);
        return value != null && (Boolean) value;
    }

}
