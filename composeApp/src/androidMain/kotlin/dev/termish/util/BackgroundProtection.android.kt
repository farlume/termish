package dev.termish.util

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.termish.AppContext

actual fun backgroundProtectionState(): BackgroundProtectionState {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) return BackgroundProtectionState.NOT_APPLICABLE
    val context = AppContext.get()
    val power = context.getSystemService(PowerManager::class.java)
    return if (power.isIgnoringBatteryOptimizations(context.packageName)) {
        BackgroundProtectionState.SYSTEM_EXEMPT
    } else {
        BackgroundProtectionState.SYSTEM_MANAGED
    }
}

actual fun backgroundProtectionVendor(): BackgroundProtectionVendor {
    val manufacturer = Build.MANUFACTURER.lowercase()
    val brand = Build.BRAND.lowercase()
    val identity = "$manufacturer $brand"
    return when {
        listOf("oppo", "oneplus", "realme").any(identity::contains) -> BackgroundProtectionVendor.OPPO_FAMILY
        listOf("xiaomi", "redmi", "poco").any(identity::contains) -> BackgroundProtectionVendor.XIAOMI_FAMILY
        listOf("vivo", "iqoo").any(identity::contains) -> BackgroundProtectionVendor.VIVO_FAMILY
        identity.contains("samsung") -> BackgroundProtectionVendor.SAMSUNG
        else -> BackgroundProtectionVendor.GENERIC
    }
}

actual fun openApplicationSettings() {
    val context = AppContext.get()
    val intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}
