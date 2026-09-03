package dev.termish.util

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString

actual fun backgroundProtectionState(): BackgroundProtectionState = BackgroundProtectionState.NOT_APPLICABLE

actual fun backgroundProtectionVendor(): BackgroundProtectionVendor = BackgroundProtectionVendor.GENERIC

actual fun openApplicationSettings() {
    val url = NSURL.URLWithString(UIApplicationOpenSettingsURLString) ?: return
    UIApplication.sharedApplication.openURL(url, emptyMap<Any?, Any?>(), null)
}
