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
package com.sevtinge.hyperceiler;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import com.sevtinge.hyperceiler.hook.utils.prefs.FrameworkServiceClient;
import com.sevtinge.hyperceiler.hook.utils.prefs.LegacyPrefsMigration;
import com.sevtinge.hyperceiler.hook.utils.prefs.PrefsUtils;
import com.sevtinge.hyperceiler.hook.utils.shell.ShellUtils;
import com.sevtinge.hyperceiler.model.data.AppInfoCache;
import com.sevtinge.hyperceiler.safemode.ExceptionCrashActivity;
import com.sevtinge.hyperceiler.common.utils.LSPosedScopeHelper;
import com.sevtinge.hyperceiler.utils.XposedActivateHelper;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

public class Application extends android.app.Application {
    private static final String TAG = "HyperCeiler:Application";
    private LegacyPrefsMigration.Changes migrationChanges;
    private String lastSyncFailure;

    @Override
    protected void attachBaseContext(Context base) {
        PrefsUtils.mSharedPreferences = PrefsUtils.getSharedPrefs(base);
        super.attachBaseContext(base);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        initializeFrameworkPreferences();
        // 初始化所有应用信息到缓存
        AppInfoCache.getInstance(this).initAllAppInfos();

        setupCrashHandler();
    }

    private void initializeFrameworkPreferences() {
        SharedPreferences local = PrefsUtils.mSharedPreferences;
        Context storage = createDeviceProtectedStorageContext();
        SharedPreferences migrationState = storage.getSharedPreferences(LegacyPrefsMigration.STATE_NAME, MODE_PRIVATE);
        Map<String, ?> startup = new HashMap<>(local.getAll());
        PrefsUtils.registerOnSharedPreferenceChangeListener(this);
        FrameworkServiceClient.initialize(local, (connected, synchronizedPreferences, error) -> {
            XposedActivateHelper.isModuleActive = connected;
            XposedActivateHelper.XposedVersion = FrameworkServiceClient.getApiVersion();
            LSPosedScopeHelper.init(this);
            if (error != null) {
                Log.w(TAG, "Settings are local; framework synchronization failed", error);
                String failure = error.getClass().getName();
                if (!failure.equals(lastSyncFailure)) {
                    Toast.makeText(this, R.string.prefs_framework_sync_failed, Toast.LENGTH_LONG).show();
                }
                lastSyncFailure = failure;
            } else if (synchronizedPreferences) lastSyncFailure = null;
        });
        if (LegacyPrefsMigration.isComplete(migrationState)) {
            FrameworkServiceClient.setPublishingEnabled(true);
            return;
        }
        migrationChanges = new LegacyPrefsMigration.Changes(migrationState);
        local.registerOnSharedPreferenceChangeListener(migrationChanges);
        File backup = new File(storage.getFilesDir(), "legacy-preferences-backup");
        Thread migration = new Thread(() -> {
            LegacyPrefsMigration.Prepared prepared = LegacyPrefsMigration.prepare(ShellUtils::rootExecCmd, backup);
            new Handler(Looper.getMainLooper()).post(() -> {
                LegacyPrefsMigration.Result result = LegacyPrefsMigration.apply(local, migrationState, startup,
                    prepared, migrationChanges);
                if (result.isComplete()) {
                    local.unregisterOnSharedPreferenceChangeListener(migrationChanges);
                    migrationChanges = null;
                    FrameworkServiceClient.setPublishingEnabled(true);
                    Log.i(TAG, "Legacy preference migration: " + result.status());
                } else {
                    // Never publish empty/default private data over a possibly recoverable legacy source.
                    Log.w(TAG, "Legacy preference migration postponed: " + result.detail());
                    Toast.makeText(this, R.string.prefs_legacy_migration_failed, Toast.LENGTH_LONG).show();
                }
            });
        }, "HyperCeiler-prefs-migration");
        migration.setDaemon(true);
        migration.start();
    }

    private void setupCrashHandler() {
        final Thread.UncaughtExceptionHandler defaultHandler = Thread.getDefaultUncaughtExceptionHandler();

        Thread.setDefaultUncaughtExceptionHandler((thread, ex) -> {
            Intent intent = new Intent(getApplicationContext(), ExceptionCrashActivity.class);
            intent.putExtra("crashInfo", ex);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
            getApplicationContext().startActivity(intent);

            try {
                Thread.sleep(500);
            } catch (InterruptedException ignored) {
            }

            android.os.Process.killProcess(android.os.Process.myPid());
            System.exit(1);
        });
    }
}
