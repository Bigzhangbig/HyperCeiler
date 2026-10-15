/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

/** Initialization data retained for archived helpers; never registered as a zygote callback. */
public interface StartupHook {
    void initZygote(StartupParam param) throws Throwable;
    final class StartupParam {
        public String modulePath;
        public boolean startsSystemServer;
    }
}
