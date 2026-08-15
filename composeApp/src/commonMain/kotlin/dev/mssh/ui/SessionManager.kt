package dev.mssh.ui

import androidx.compose.runtime.mutableStateListOf
import dev.mssh.data.Host
import dev.mssh.data.HostRepository
import dev.mssh.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 离开终端页时的会话保留策略。 */
enum class SessionKeepPolicy { KEEP_ALIVE, KEEP_10_MIN, DISCONNECT }

/**
 * 会话管理器：持有所有活跃 [TerminalController]，跨页面存活。
 * 同一主机已有活跃会话时复用（终端缓冲保留，重新进入即"继续上次"）；
 * 离开终端页（返回主页）不断开，连接由前台服务保活。
 */
class SessionManager(private val repository: HostRepository) {

    val sessions = mutableStateListOf<TerminalController>()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher())
    private val autoCloseJobs = HashMap<String, Job>()

    /** 恢复上次运行时留下的会话列表（进程死亡连接必死，恢复为未连接状态，点击重连）。 */
    fun restoreRecent(hosts: List<Host>, autoReconnect: Boolean) {
        if (sessions.isNotEmpty()) return
        val byId = hosts.associateBy { it.id }
        repository.loadRecentSessionHostIds().forEach { id ->
            val host = byId[id] ?: return@forEach
            val (pw, key) = resolveCredentials(host)
            sessions.add(TerminalController(host, pw, key, repository, autoReconnect))
        }
    }

    private fun persist() {
        repository.saveRecentSessionHostIds(sessions.map { it.host.id })
    }

    /** 打开主机：有活跃会话则复用，否则凭据解析后新建。 */
    fun open(host: Host, autoReconnect: Boolean): TerminalController {
        val existing = sessions.firstOrNull {
            it.host.id == host.id && it.status != ConnStatus.CLOSED && it.status != ConnStatus.ERROR
        }
        if (existing != null) {
            val (pw, key) = resolveCredentials(host)
            // 编辑主机后凭据/连接参数可能已变化：旧会话仍持有旧凭据，
            // 直接复用会带着过期认证重连（如改密码后仍用旧私钥）。签名不同则重建。
            if (existing.credentialKey == credentialSignature(host, pw, key)) return existing
            existing.close()
            sessions.remove(existing)
        }
        val (pw, key) = resolveCredentials(host)
        val controller = TerminalController(host, pw, key, repository, autoReconnect)
        sessions.removeAll { it.host.id == host.id }
        sessions.add(controller)
        persist()
        return controller
    }

    /** 定时自动断开（如离开终端页时选择「保留 10 分钟」），断开后保留在列表。 */
    fun scheduleClose(controller: TerminalController, delayMs: Long) {
        autoCloseJobs[controller.host.id]?.cancel()
        autoCloseJobs[controller.host.id] = scope.launch {
            delay(delayMs)
            disconnect(controller)
            autoCloseJobs.remove(controller.host.id)
        }
    }

    /** 重新进入会话时取消定时断开。 */
    fun cancelScheduledClose(controller: TerminalController) {
        autoCloseJobs.remove(controller.host.id)?.cancel()
    }

    /** 断开但保留在列表中（灰点，点击可重连，缓冲保留）。 */
    fun disconnect(controller: TerminalController) {
        controller.close()
    }

    /** 从列表中移除（针对已断开的会话）。 */
    fun remove(controller: TerminalController) {
        controller.close()
        sessions.remove(controller)
        persist()
    }

    /** 主机被删除时，连带断开并移除其会话。 */
    fun closeForHost(hostId: String) {
        sessions.filter { it.host.id == hostId }.forEach { it.close() }
        sessions.removeAll { it.host.id == hostId }
        persist()
    }
}

/** 凭据/连接参数/启动命令签名：任一变化即视为旧会话过期（编辑主机后需重建会话）。 */
internal fun credentialSignature(host: Host, password: String?, privateKeyPem: String?): String =
    "${host.username}@${host.hostname}:${host.port}|${host.authMethod}|$password|$privateKeyPem|${host.startupCommand}"
