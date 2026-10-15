/*
 * This file is part of HyperCeiler.
 *
 * HyperCeiler is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License.
 *
 * HyperCeiler is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 */
package com.sevtinge.hyperceiler.hook.utils.prefs;

import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

import io.github.libxposed.service.XposedService;
import io.github.libxposed.service.XposedServiceHelper;

/** App-process bridge to the official framework service and its remote preferences. */
public final class FrameworkServiceClient {
    public interface StatusListener {
        /** Runs on the main thread; connection alone does not prove any target was injected. */
        void onStatusChanged(boolean connected, boolean synchronizedPreferences, Throwable error);
    }

    private static volatile FrameworkServiceClient instance;
    private static boolean requestedPublishingEnabled;

    private final SharedPreferences local;
    private final StatusListener listener;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private volatile XposedService service;
    private volatile SharedPreferences remote;
    private volatile Status status = new Status(false, false, null);
    private RemotePrefsMirror mirror;
    private boolean publishingEnabled;

    private FrameworkServiceClient(SharedPreferences local, StatusListener listener, boolean publishingEnabled) {
        this.local = Objects.requireNonNull(local, "local");
        this.listener = listener;
        this.publishingEnabled = publishingEnabled;
    }

    /** First initialization owns the canonical preferences and listener for the app process. */
    public static synchronized void initialize(SharedPreferences local, StatusListener listener) {
        if (instance != null) return;
        FrameworkServiceClient client = new FrameworkServiceClient(local, listener, requestedPublishingEnabled);
        instance = client;
        client.mainHandler.post(client::start);
    }

    /**
     * Remains disabled by default until legacy preference migration has completed.
     * Enabling publishes current canonical values, including edits made while disconnected.
     */
    public static synchronized void setPublishingEnabled(boolean enabled) {
        requestedPublishingEnabled = enabled;
        FrameworkServiceClient client = instance;
        if (client != null) client.onMain(() -> client.setPublishingEnabledOnMain(enabled));
    }

    public static boolean isConnected() {
        FrameworkServiceClient client = instance;
        return client != null && client.status.connected;
    }

    public static boolean isSynchronized() {
        FrameworkServiceClient client = instance;
        return client != null && client.status.synchronizedPreferences;
    }

    /** Returns zero while unavailable; never infers an API version from a legacy self-hook. */
    public static int getApiVersion() {
        FrameworkServiceClient client = instance;
        XposedService current = client == null ? null : client.service;
        if (current == null) return 0;
        try {
            int apiVersion = current.getApiVersion();
            return client.service == current ? apiVersion : 0;
        } catch (RuntimeException error) {
            return 0;
        }
    }

    /** Returns null for unknown scope, preserving a successful empty scope as an empty list. */
    public static List<String> getScope() {
        FrameworkServiceClient client = instance;
        XposedService current = client == null ? null : client.service;
        if (current == null) return null;
        try {
            List<String> scope = current.getScope();
            if (scope == null || client.service != current) return null;
            return Collections.unmodifiableList(new ArrayList<>(scope));
        } catch (RuntimeException error) {
            return null;
        }
    }

    /** Requests one publication attempt without adding any timer or background retry loop. */
    public static void refresh() {
        FrameworkServiceClient client = instance;
        if (client != null) client.onMain(client::publish);
    }

    private void start() {
        try {
            mirror = new RemotePrefsMirror(local, new RemotePrefsMirror.Listener() {
                @Override
                public void onSyncSuccess() {
                    onMirrorResult(null);
                }

                @Override
                public void onSyncFailure(Throwable error) {
                    onMirrorResult(error);
                }
            });
            XposedServiceHelper.registerListener(new XposedServiceHelper.OnServiceListener() {
                @Override
                public void onServiceBind(XposedService bound) {
                    // Always leave the helper's binder/cache lock before handling state.
                    mainHandler.post(() -> bind(bound));
                }

                @Override
                public void onServiceDied(XposedService dead) {
                    mainHandler.post(() -> died(dead));
                }
            });
            updateStatus(false, false, null);
        } catch (RuntimeException error) {
            updateStatus(false, false, error);
        }
    }

    private void bind(XposedService bound) {
        if (remote != null) mirror.disconnect(remote);
        remote = null;
        service = bound;
        updateStatus(true, false, null);
        try {
            remote = bound.getRemotePreferences(PrefsUtils.mPrefsName);
        } catch (RuntimeException error) {
            updateStatus(true, false, error);
            return;
        }
        if (publishingEnabled) publish();
    }

    private void died(XposedService dead) {
        if (service != dead) return;
        if (remote != null) mirror.disconnect(remote);
        remote = null;
        service = null;
        updateStatus(false, false, null);
    }

    private void setPublishingEnabledOnMain(boolean enabled) {
        publishingEnabled = enabled;
        if (!enabled) {
            if (remote != null && mirror != null) mirror.disconnect(remote);
            updateStatus(service != null, false, status.error);
        } else {
            publish();
        }
    }

    private void publish() {
        if (!publishingEnabled || service == null || mirror == null) return;
        if (remote == null) {
            try {
                remote = service.getRemotePreferences(PrefsUtils.mPrefsName);
            } catch (RuntimeException error) {
                updateStatus(true, false, error);
                return;
            }
        }
        // The mirror reports success only after the entire snapshot's commit succeeds.
        mirror.connect(remote);
    }

    private void onMirrorResult(Throwable error) {
        XposedService publishedService = service;
        SharedPreferences publishedRemote = remote;
        onMain(() -> {
            if (!publishingEnabled || publishedService == null || service != publishedService
                || publishedRemote == null || remote != publishedRemote) return;
            updateStatus(true, error == null, error);
        });
    }

    private void updateStatus(boolean connected, boolean synchronizedPreferences, Throwable error) {
        status = new Status(connected, synchronizedPreferences, error);
        if (listener != null) {
            try {
                listener.onStatusChanged(connected, synchronizedPreferences, error);
            } catch (RuntimeException ignored) {
                // Logging/UI callbacks must not prevent future service or preference updates.
            }
        }
    }

    private void onMain(Runnable operation) {
        if (Looper.myLooper() == mainHandler.getLooper()) operation.run();
        else mainHandler.post(operation);
    }

    private static final class Status {
        final boolean connected;
        final boolean synchronizedPreferences;
        final Throwable error;

        Status(boolean connected, boolean synchronizedPreferences, Throwable error) {
            this.connected = connected;
            this.synchronizedPreferences = synchronizedPreferences;
            this.error = error;
        }
    }
}
