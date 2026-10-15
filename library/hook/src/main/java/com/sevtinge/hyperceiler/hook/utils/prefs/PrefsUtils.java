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

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;

import com.sevtinge.hyperceiler.hook.provider.SharedPrefsProvider;
import com.sevtinge.hyperceiler.hook.module.base.tool.AppsTool;
import com.sevtinge.hyperceiler.hook.utils.log.XposedLogUtils;
import com.sevtinge.hyperceiler.hook.utils.prefs.PrefsChangeObserver.PrefToUri;

import java.lang.reflect.Executable;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

import com.sevtinge.hyperceiler.hook.api.HookBridge;

public class PrefsUtils {
    public static SharedPreferences mSharedPreferences = null;
    public static PrefsMap<String, Object> mPrefsMap = new PrefsMap<>();

    public static String mPrefsName = "hyperceiler_prefs";
    private static volatile SharedPreferences hookPreferences;
    private static volatile Predicate<Executable> deoptimizer;
    private static final HookPrefsStore hookStore = new HookPrefsStore(mPrefsMap,
        // Bootstrap errors must not initialize LogManager before the first valid snapshot.
        HookBridge::log);
    private static SharedPreferences.OnSharedPreferenceChangeListener appListener;
    private static Map<String, ?> lastAppSnapshot = new HashMap<>();

    public static SharedPreferences getSharedPrefs(Context context) {
        context = AppsTool.getProtectedContext(context);
        return context.getSharedPreferences(mPrefsName, Context.MODE_PRIVATE);
    }

    public static void attachHookPreferences(SharedPreferences preferences,
                                             Predicate<Executable> methodDeoptimizer) {
        Objects.requireNonNull(preferences, "preferences");
        synchronized (hookStore) {
            if (!hookStore.attach(preferences)) {
                throw new IllegalStateException("Cannot attach framework preferences for hook initialization");
            }
            hookPreferences = preferences;
            deoptimizer = methodDeoptimizer;
        }
    }

    /** Hook observers share the source listener and run after its complete snapshot replacement. */
    public static boolean observeHookPreferences(HookPrefsStore.Listener listener) {
        return hookStore.observe(listener);
    }

    public static SharedPreferences getHookPreferences() {
        SharedPreferences preferences = hookPreferences;
        if (preferences == null) throw new IllegalStateException("Framework preferences have not been attached");
        return preferences;
    }

    public static boolean deoptimizeMethod(Executable method) {
        Predicate<Executable> current = deoptimizer;
        return current != null && current.test(method);
    }

    public static boolean contains(String key) {
        return mSharedPreferences.contains(key);
    }

    public static SharedPreferences.Editor editor() {
        return mSharedPreferences.edit();
    }

    public static void putString(String key, String defValue) {
        mSharedPreferences.edit().putString(key, defValue).apply();
    }

    public static String getSharedStringPrefs(Context context, String name, String defValue) {
        if (hookPreferences != null) return hookPreferences.getString(name, defValue);
        Uri uri = PrefToUri.stringPrefToUri(name, defValue);
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String prefValue = cursor.getString(0);
                cursor.close();
                return prefValue;
            } else XposedLogUtils.logI("ContentResolver", "[" + name + "] Cursor fail: " + cursor);
        } catch (Throwable t) {
            HookBridge.log(t);
        }

        if (mPrefsMap.containsKey(name))
            return (String) mPrefsMap.getObject(name, defValue);
        else return defValue;
    }

    public static Set<String> getSharedStringSetPrefs(Context context, String name) {
        if (hookPreferences != null) return new LinkedHashSet<>(hookPreferences.getStringSet(name, new LinkedHashSet<>()));
        Uri uri = PrefToUri.stringSetPrefToUri(name);
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null) {
                Set<String> prefValue = new LinkedHashSet<>();
                while (cursor.moveToNext()) {
                    prefValue.add(cursor.getString(0));
                }
                cursor.close();
                return prefValue;
            } else {
                XposedLogUtils.logI("ContentResolver", "[" + name + "] Cursor fail: null");
            }
        } catch (Throwable t) {
            HookBridge.log(t);
        }

        LinkedHashSet<String> empty = new LinkedHashSet<>();
        if (mPrefsMap.containsKey(name)) {
            return (Set<String>) mPrefsMap.getObject(name, empty);
        } else {
            return empty;
        }
    }

    public static int getSharedIntPrefs(Context context, String name, int defValue) {
        if (hookPreferences != null) return hookPreferences.getInt(name, defValue);
        Uri uri = PrefToUri.intPrefToUri(name, defValue);
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int prefValue = cursor.getInt(0);
                cursor.close();
                return prefValue;
            } else XposedLogUtils.logI("ContentResolver", "[" + name + "] Cursor fail: " + cursor);
        } catch (Throwable t) {
            HookBridge.log(t);
        }

        if (mPrefsMap.containsKey(name))
            return (int) mPrefsMap.getObject(name, defValue);
        else return defValue;
    }

    public static boolean getSharedBoolPrefs(Context context, String name, boolean defValue) {
        if (hookPreferences != null) return hookPreferences.getBoolean(name, defValue);
        Uri uri = PrefToUri.boolPrefToUri(name, defValue);
        try {
            Cursor cursor = context.getContentResolver().query(uri, null, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                int prefValue = cursor.getInt(0);
                cursor.close();
                return prefValue == 1;
            } else XposedLogUtils.logI("ContentResolver", "[" + name + "] Cursor fail: " + cursor);
        } catch (Throwable t) {
            HookBridge.log(t);
        }

        if (mPrefsMap.containsKey(name))
            return (boolean) mPrefsMap.getObject(name, false);
        else
            return defValue;
    }

    public static synchronized void registerOnSharedPreferenceChangeListener(Context context) {
        if (appListener != null) return;
        Context appContext = context.getApplicationContext();
        lastAppSnapshot = mSharedPreferences.getAll();
        appListener = (sharedPreferences, key) -> {
            Log.i("prefs", "Changed: " + key);
            AppsTool.requestBackup(appContext);
            Map<String, ?> current = sharedPreferences.getAll();
            if (key == null) {
                Set<String> keys = new LinkedHashSet<>(lastAppSnapshot.keySet());
                keys.addAll(current.keySet());
                for (String changed : keys) notifyPreferenceChanged(appContext, changed,
                    current.containsKey(changed) ? current.get(changed) : lastAppSnapshot.get(changed));
            } else {
                notifyPreferenceChanged(appContext, key,
                    current.containsKey(key) ? current.get(key) : lastAppSnapshot.get(key));
            }
            lastAppSnapshot = current;
        };
        mSharedPreferences.registerOnSharedPreferenceChangeListener(appListener);
    }

    private static void notifyPreferenceChanged(Context context, String key, Object value) {
        String type = value instanceof String ? "string" : value instanceof Set<?> ? "stringset"
            : value instanceof Integer ? "integer" : value instanceof Boolean ? "boolean" : "";
        ContentResolver resolver = context.getContentResolver();
        Uri.Builder base = new Uri.Builder().scheme("content").authority(SharedPrefsProvider.AUTHORITY);
        if (!type.isEmpty()) base.appendPath(type);
        resolver.notifyChange(base.appendPath(key).build(), null);
        if (!type.isEmpty()) resolver.notifyChange(new Uri.Builder().scheme("content")
            .authority(SharedPrefsProvider.AUTHORITY).appendPath("pref").appendPath(type).appendPath(key).build(), null);
    }
}
