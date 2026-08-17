package dev.termish.ui

import androidx.compose.runtime.mutableStateListOf
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.ssh.SftpSession
import dev.termish.util.ioDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/** SFTP 会话条目（与终端会话平级管理，跨页面存活）。 */
data class SftpSessionEntry(
    val host: Host,
    val session: SftpSession,
    /** 浏览状态（路径/列表/排序等）：随条目存活，切 tab 不重置。 */
    val uiState: SftpUiState = SftpUiState(),
)

/**
 * 会话管理器：持有所有活跃 [TerminalController]，跨页面存活。
 * 同一主机已有活跃会话时复用（终端缓冲保留，重新进入即"继续上次"）；
 * 离开终端页（返回主页）不断开，连接由前台服务保活。
 */
class SessionManager(private val repository: HostRepository) {

    val sessions = mutableStateListOf<TerminalController>()
    /** SFTP 会话（与终端会话同源管理：连接页可见、卡片 Close 可关、删除主机连带释放）。 */
    val sftpSessions = mutableStateListOf<SftpSessionEntry>()

    private val scope = CoroutineScope(SupervisorJob() + ioDispatcher())
    /** 退到后台时仍活跃的会话 id（回前台据此自动重连，iOS 场景）。 */
    private val activeAtBackground = HashSet<String>()

    /** 退到后台：记录当前活跃会话，供回前台恢复。 */
    fun noteBackgrounded() {
        activeAtBackground.clear()
        sessions.forEach { controller ->
            val st = controller.status
            if (st == ConnStatus.CONNECTED || st == ConnStatus.AUTH || st == ConnStatus.CONNECTING) {
                activeAtBackground.add(controller.host.id)
            }
        }
    }

    /** 回前台：把退后台期间掉线的活跃会话自动重连（保留缓冲）。 */
    fun reconnectDroppedSessions() {
        if (activeAtBackground.isEmpty()) return
        val ids = activeAtBackground.toList()
        activeAtBackground.clear()
        sessions
            .filter {
                it.host.id in ids &&
                    it.autoReconnectEnabled &&
                    (it.status == ConnStatus.CLOSED || it.status == ConnStatus.ERROR)
            }
            .forEach { it.reconnect() }
    }

    /** 恢复上次运行时留下的会话列表（进程死亡连接必死，恢复为未连接状态，点击重连）。 */
    fun restoreRecent(
        hosts: List<Host>,
        autoReconnect: Boolean,
        onSystemDetected: ((Host) -> Unit)? = null,
    ) {
        if (sessions.isNotEmpty()) return
        val byId = hosts.associateBy { it.id }
        repository.loadRecentSessionHostIds().forEach { id ->
            val host = byId[id] ?: return@forEach
            val (pw, key) = resolveCredentials(host)
            TerminalController(host, pw, key, repository, autoReconnect).also {
                it.onSystemDetected = onSystemDetected
                sessions.add(it)
            }
        }
    }

    private fun persist() {
        repository.saveRecentSessionHostIds(sessions.map { it.host.id })
    }

    /** 打开主机：有活跃会话则复用，否则凭据解析后新建。 */
    fun open(
        host: Host,
        autoReconnect: Boolean,
        onSystemDetected: ((Host) -> Unit)? = null,
    ): TerminalController {
        // 同一主机支持多个会话（Termius 风格）：每次打开都新建，
        // 旧的保留在列表，可从卡片下拉/连接页重入。
        val (pw, key) = resolveCredentials(host)
        val controller = TerminalController(host, pw, key, repository, autoReconnect)
        controller.onSystemDetected = onSystemDetected
        sessions.add(controller)
        persist()
        return controller
    }

    /** 断开但保留在列表中（灰点，点击可重连，缓冲保留）。 */
    fun disconnect(controller: TerminalController) {
        controller.close()
    }

    /** 从列表中移除（针对已断开的会话）：销毁控制器，回收协程作用域。 */
    fun remove(controller: TerminalController) {
        controller.destroy()
        sessions.remove(controller)
        persist()
    }

    /** 登记 SFTP 会话（连接成功后由调用方加入）。 */
    fun addSftp(host: Host, session: SftpSession) {
        sftpSessions.add(SftpSessionEntry(host, session))
    }

    /** 关闭并移除 SFTP 会话。 */
    fun closeSftp(entry: SftpSessionEntry) {
        try {
            entry.session.close()
        } catch (_: Exception) {
        }
        sftpSessions.remove(entry)
    }

    /** 关闭主机的全部会话：终端断开保留可重入 + SFTP 释放（卡片 Close）。 */
    fun closeAllForHost(hostId: String) {
        sessions.filter { it.host.id == hostId }.forEach { disconnect(it) }
        sftpSessions.filter { it.host.id == hostId }.toList().forEach { closeSftp(it) }
    }

    /** 主机被删除时，连带断开并移除其会话。 */
    fun closeForHost(hostId: String) {
        sessions.filter { it.host.id == hostId }.forEach { it.destroy() }
        sessions.removeAll { it.host.id == hostId }
        sftpSessions.filter { it.host.id == hostId }.toList().forEach { closeSftp(it) }
        persist()
    }
}

/** 凭据/连接参数/启动命令签名：任一变化即视为旧会话过期（编辑主机后需重建会话）。 */
internal fun credentialSignature(host: Host, password: String?, privateKeyPem: String?): String {
    // 敏感字段只保留散列：避免明文密码/私钥以签名串形式常驻内存
    fun hash(s: String?): String =
        if (s == null) "-" else dev.termish.util.base64Encode(dev.termish.crypto.Sha256.digest(s.encodeToByteArray()))
    return "${host.username}@${host.hostname}:${host.port}|${host.authMethod}|" +
        "${hash(password)}|${hash(privateKeyPem)}|" +
        "${host.startupCommand}|${host.connectionMode}|${host.moshUdpPort}|${host.moshThemeSync}"
}
