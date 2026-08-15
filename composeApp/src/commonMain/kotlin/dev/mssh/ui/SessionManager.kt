package dev.mssh.ui

import androidx.compose.runtime.mutableStateListOf
import dev.mssh.data.Host
import dev.mssh.data.HostRepository

/**
 * 会话管理器：持有所有活跃 [TerminalController]，跨页面存活。
 * 同一主机已有活跃会话时复用（终端缓冲保留，重新进入即"继续上次"）；
 * 离开终端页（返回主页）不断开，连接由前台服务保活。
 */
class SessionManager(private val repository: HostRepository) {

    val sessions = mutableStateListOf<TerminalController>()

    /** 打开主机：有活跃会话则复用，否则凭据解析后新建。 */
    fun open(host: Host, autoReconnect: Boolean): TerminalController {
        val existing = sessions.firstOrNull {
            it.host.id == host.id && it.status != ConnStatus.CLOSED && it.status != ConnStatus.ERROR
        }
        if (existing != null) return existing
        val (pw, key) = resolveCredentials(host)
        val controller = TerminalController(host, pw, key, repository, autoReconnect)
        sessions.removeAll { it.host.id == host.id }
        sessions.add(controller)
        return controller
    }

    fun close(controller: TerminalController) {
        controller.close()
        sessions.remove(controller)
    }

    /** 主机被删除时，连带断开其会话。 */
    fun closeForHost(hostId: String) {
        sessions.filter { it.host.id == hostId }.forEach { it.close() }
        sessions.removeAll { it.host.id == hostId }
    }
}
