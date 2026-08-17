package dev.termish.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.platform.LocalContext

/**
 * Android：监听默认网络变化。网络切换（Wi-Fi ↔ 流量、断线重连）时立即回调，
 * 让上层快速重连 SSH/Mosh，而不是等 TCP 超时才发现。
 */
@Composable
actual fun observeNetworkChange(onChange: (NetworkChangeKind) -> Unit): () -> Unit {
    val context = LocalContext.current
    val cm = remember {
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    }
    val latestOnChange by rememberUpdatedState(onChange)
    val unregister = remember(cm) {
        // 传输类型基线（-1=未知）；注册后系统会立即回调一次 onAvailable，只记录不触发
        var lastTransport = -1
        var lastLostAt = 0L // LOST 节流：与切换节流分开，避免吞掉紧随的 TRANSPORT_CHANGED
        var lastSwitchAt = 0L // TRANSPORT_CHANGED 节流

        fun transportOf(caps: NetworkCapabilities?): Int = when {
            caps == null -> -1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> 0
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> 1
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> 2
            else -> 3
        }

        fun handleTransport(t: Int) {
            if (t == -1) return // capabilities 未就绪：等 onCapabilitiesChanged 再判，避免假"传输切换"
            if (lastTransport == -1) {
                lastTransport = t // 首次注册回调：建立基线，不触发
                return
            }
            if (t != lastTransport) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastSwitchAt >= 3_000) { // 防抖动：3 秒内只触发一次
                    lastSwitchAt = now
                    latestOnChange(NetworkChangeKind.TRANSPORT_CHANGED)
                }
            }
            lastTransport = t
        }

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                // onAvailable 时 capabilities 未必就绪（会拿到 null）；真正就绪后
                // onCapabilitiesChanged 还会回调一次，由它兜底判定，不会丢事件
                handleTransport(transportOf(cm.getNetworkCapabilities(network)))
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                handleTransport(transportOf(caps))
            }

            override fun onLost(network: Network) {
                if (lastTransport != -1) {
                    val now = SystemClock.elapsedRealtime()
                    if (now - lastLostAt >= 3_000) {
                        lastLostAt = now
                        latestOnChange(NetworkChangeKind.LOST)
                    }
                    // 注意：不重置 lastTransport——保留旧值，让随后 onAvailable(新网络)
                    // 能检测到传输类型变化（否则会被误判为"首次注册基线"而吞掉事件，
                    // 导致 Wi-Fi→流量 方向 mosh 永不重建、会话死锁）
                }
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        val unregisterFn: () -> Unit = { cm.unregisterNetworkCallback(callback) }
        unregisterFn
    }
    return unregister
}
