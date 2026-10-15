/* SPDX-License-Identifier: AGPL-3.0-or-later */
package com.sevtinge.hyperceiler.hook.api;

/** Internal dispatcher implemented by feature groups. */
public interface PackageHook {
    void handleLoadPackage(PackageContext.LoadPackageParam param) throws Throwable;
}
