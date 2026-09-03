package dev.termish.util

/** Android 后台电池策略状态；其他平台返回 [NOT_APPLICABLE]。 */
enum class BackgroundProtectionState {
    NOT_APPLICABLE,
    SYSTEM_MANAGED,
    SYSTEM_EXEMPT,
}

/** 厂商电池管理入口不同，仅用于选择对应的用户引导文案。 */
enum class BackgroundProtectionVendor {
    GENERIC,
    OPPO_FAMILY,
    XIAOMI_FAMILY,
    VIVO_FAMILY,
    SAMSUNG,
}

/** 查询系统 Doze 豁免状态。厂商私有的后台开关通常无法由应用读取。 */
expect fun backgroundProtectionState(): BackgroundProtectionState

/** 查询设备厂商，以显示可操作的后台设置路径。 */
expect fun backgroundProtectionVendor(): BackgroundProtectionVendor

/** 打开当前应用的系统详情页；电池、通知和麦克风的永久拒绝均可从这里恢复。 */
expect fun openApplicationSettings()

internal fun shouldShowBackgroundProtectionGuide(
    state: BackgroundProtectionState,
    vendor: BackgroundProtectionVendor,
    alreadyPrompted: Boolean,
    hasConnectedSshSession: Boolean,
): Boolean =
    !alreadyPrompted &&
        hasConnectedSshSession &&
        state != BackgroundProtectionState.NOT_APPLICABLE &&
        (state == BackgroundProtectionState.SYSTEM_MANAGED || vendor != BackgroundProtectionVendor.GENERIC)
