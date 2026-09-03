package dev.termish.util

import androidx.compose.runtime.Composable

/** 网络事件类型：完全断开 vs 默认网络实例变化。 */
enum class NetworkChangeKind {
    /** 网络断开（同 IP 的网络抖动；mosh 可自行恢复，无需重建）。 */
    LOST,

    /** 默认网络变化（Wi-Fi/蜂窝切换、同类型网络更换或 VPN 开关）。 */
    DEFAULT_NETWORK_CHANGED,
}

/** 监听网络变化；返回注销函数。桌面/iOS 暂为 no-op。 */
@Composable
expect fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit
