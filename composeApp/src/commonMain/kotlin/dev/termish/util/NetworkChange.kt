package dev.termish.util

import androidx.compose.runtime.Composable

/** 网络事件类型：断开 vs 传输类型切换（Wi-Fi ↔ 流量，IP 必然变化）。 */
enum class NetworkChangeKind {
    /** 网络断开（同 IP 的网络抖动；mosh 可自行恢复，无需重建）。 */
    LOST,
    /** 传输类型切换（IP 变化；SSH 需主动重连，mosh 靠 UDP 漫游自愈、也不重建）。 */
    TRANSPORT_CHANGED,
}

/** 监听网络变化；返回注销函数。桌面/iOS 暂为 no-op。 */
@Composable
expect fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit
