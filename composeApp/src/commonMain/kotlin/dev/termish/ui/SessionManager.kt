package dev.termish.ui

import androidx.compose.runtime.mutableStateListOf
import dev.termish.crypto.Sha256
import dev.termish.data.Host
import dev.termish.data.HostRepository
import dev.termish.data.HostRepository.RecentSftpEntry
import dev.termish.data.HostRepository.RecentTerminalEntry
import dev.termish.data.resolveCredentials
import dev.termish.ssh.SftpSession
import dev.termish.util.ForegroundSshRecovery
import dev.termish.util.TermLog
import dev.termish.util.base64Encode
import kotlinx.datetime.Clock

/** SFTP 会话条目（与终端会话平级管理，跨页面存活）。
 *  [session] 可空：进程重启后恢复的条目未连接（session=null），
 *  进入 SFTP tab 时触发自动重连（见 SftpContent / AppRoot.onReconnectSftp）。 */
data class SftpSessionEntry(
    val host: Host,
    val session: SftpSession?,
    /** 浏览状态（路径/列表/排序等）：随条目存活，切 tab 不重置。 */
    val uiState: SftpUiState = SftpUiState(),
    /** 当前 [session] 所属连接的身份标识（establishSftp 每次生成）。
     *  close() 会同步触发 onClosed：重连/换新/移除时被关闭的旧连接回调仍在，
     *  必须凭代次区分「本代连接意外断开」与「旧连接被主动关闭」，
     *  否则旧回调会把 uiState.disconnected 误标回 true（断开 banner 永不消失）。 */
    val connectionToken: Any = Any(),
    /** 条目首次创建时间；重连替换底层连接时保持不变。 */
    val createdAt: Long = Clock.System.now().toEpochMilliseconds(),
)

internal enum class BackgroundReconnectAction {
    NONE,
    RECONNECT,
    VERIFY,
    REBUILD,
}

/** 纯决策层，避免生命周期回调里把同主机的其他会话误当成后台活跃会话。 */
internal fun backgroundReconnectAction(
    sessionId: String,
    activeSessionIds: Set<String>,
    autoReconnect: Boolean,
    status: ConnStatus,
    usesMoshTransport: Boolean,
    recovery: ForegroundSshRecovery,
): BackgroundReconnectAction =
    when {
        sessionId !in activeSessionIds || !autoReconnect -> BackgroundReconnectAction.NONE
        status == ConnStatus.CLOSED || status == ConnStatus.ERROR -> BackgroundReconnectAction.RECONNECT
        recovery == ForegroundSshRecovery.REBUILD &&
            !usesMoshTransport &&
            (status == ConnStatus.CONNECTED || status == ConnStatus.AUTH) -> BackgroundReconnectAction.REBUILD
        recovery == ForegroundSshRecovery.VERIFY &&
            !usesMoshTransport &&
            status == ConnStatus.CONNECTED -> BackgroundReconnectAction.VERIFY
        else -> BackgroundReconnectAction.NONE
    }

/**
 * 会话管理器：持有所有活跃 [TerminalController]，跨页面存活。
 * 同一主机已有活跃会话时复用（终端缓冲保留，重新进入即"继续上次"）；
 * 离开终端页（返回主页）不断开，连接由前台服务保活。
 */
class SessionManager(
    private val repository: HostRepository,
    /** 连接错误文案提供器（随语言切换取最新 AppStrings）。 */
    private val strings: () -> AppStrings = { appStringsFor("en") },
) {
    val sessions = mutableStateListOf<TerminalController>()

    /** SFTP 会话（与终端会话同源管理：连接页可见、卡片 Close 可关、删除主机连带释放）。 */
    val sftpSessions = mutableStateListOf<SftpSessionEntry>()

    /** 退到后台时仍活跃的 session id（回前台据此自动重连，iOS 场景）。 */
    private val activeAtBackground = HashSet<String>()

    /** 退到后台：记录当前活跃会话，供回前台恢复。 */
    fun noteBackgrounded() {
        activeAtBackground.clear()
        sessions.forEach { controller ->
            val st = controller.status
            if (st == ConnStatus.CONNECTED || st == ConnStatus.AUTH || st == ConnStatus.CONNECTING) {
                activeAtBackground.add(controller.sessionId)
            }
        }
    }

    /**
     * 回前台：把退后台期间掉线的活跃会话自动重连（保留缓冲）。
     *
     * [recovery] 由平台决定：iOS 挂起后直接重建；Android 保活服务被停止时
     * 只做端到端探测，避免把健康 socket 误杀。
     */
    fun reconnectDroppedSessions(recovery: ForegroundSshRecovery = ForegroundSshRecovery.KEEP) {
        if (activeAtBackground.isEmpty()) return
        val ids = activeAtBackground.toSet()
        activeAtBackground.clear()
        sessions.forEach { controller ->
            when (
                backgroundReconnectAction(
                    sessionId = controller.sessionId,
                    activeSessionIds = ids,
                    autoReconnect = controller.autoReconnectEnabled,
                    status = controller.status,
                    usesMoshTransport = controller.moshSession != null,
                    recovery = recovery,
                )
            ) {
                BackgroundReconnectAction.REBUILD -> {
                    TermLog.i("session") { "foreground rebuild ${controller.host.name} session=${controller.sessionId}" }
                    controller.forceReconnect(SessionReconnectCause.FOREGROUND_RESUME)
                }

                BackgroundReconnectAction.RECONNECT -> controller.reconnect()
                BackgroundReconnectAction.VERIFY -> {
                    TermLog.i("session") { "foreground verify ${controller.host.name} session=${controller.sessionId}" }
                    controller.verifySshConnection(SessionReconnectCause.FOREGROUND_RESUME)
                }
                BackgroundReconnectAction.NONE -> Unit
            }
        }
    }

    /** Android 保活服务重建后重新登记所有仍连接的会话；sessionId 集合保证幂等。 */
    fun restoreKeepAliveRegistrations() {
        sessions.forEach { it.restoreKeepAliveRegistration() }
    }

    /** 恢复上次运行时留下的会话列表（进程死亡连接必死，恢复为未连接状态，点击重连）。
     *  终端与 SFTP 条目都恢复（SFTP 的 session=null，进 tab 自动重连）。 */
    fun restoreRecent(
        hosts: List<Host>,
        autoReconnect: Boolean,
        onSystemDetected: ((Host) -> Unit)? = null,
    ) {
        if (sessions.isNotEmpty()) return
        TermLog.i("session") {
            "restoreRecent terminals=${repository.loadRecentTerminalEntries().size} sftp=${repository.loadRecentSftpEntries().size}"
        }
        val byId = hosts.associateBy { it.id }
        repository.loadRecentTerminalEntries().forEach { entry ->
            val host = byId[entry.hostId] ?: return@forEach
            val (pw, key) = resolveCredentials(host)
            TerminalController(
                host,
                pw,
                key,
                repository,
                autoReconnect,
                launchMode = if (entry.launchHerdr) TerminalLaunchMode.HERDR else TerminalLaunchMode.SHELL,
                strings = strings,
                createdAt = entry.createdAt.takeIf { it > 0L } ?: Clock.System.now().toEpochMilliseconds(),
            ).also {
                it.onSystemDetected = onSystemDetected
                sessions.add(it)
            }
        }
        // SFTP：恢复为未连接条目（session=null），进 tab 时自动重连；
        // 浏览路径从持久化恢复（进程重启后回到上次目录，而非 home）
        if (sftpSessions.isEmpty()) {
            repository.loadRecentSftpEntries().forEach { entry ->
                val host = byId[entry.hostId] ?: return@forEach
                sftpSessions.add(
                    SftpSessionEntry(
                        host = host,
                        session = null,
                        uiState = SftpUiState().also { it.path = entry.path },
                        createdAt = entry.createdAt.takeIf { it > 0L } ?: Clock.System.now().toEpochMilliseconds(),
                    ),
                )
            }
        }
        // 旧条目没有 createdAt：上面已补当前时间，必须立即写回；否则每次进程
        // 重启都会重新生成时间，菜单里的“创建时间”会看起来不断变化。
        persist()
    }

    private fun persist() {
        repository.saveRecentTerminalEntries(
            sessions.map {
                RecentTerminalEntry(
                    hostId = it.host.id,
                    launchHerdr = it.launchMode == TerminalLaunchMode.HERDR,
                    createdAt = it.createdAt,
                )
            },
        )
        repository.saveRecentSftpEntries(
            sftpSessions.map { RecentSftpEntry(it.host.id, it.uiState.path, it.createdAt) },
        )
    }

    /** 供 AppRoot 退后台时调用：保存最新 SFTP 浏览路径（杀 App 后恢复到上次目录）。 */
    fun persistNow() = persist()

    /** 打开主机：有活跃会话则复用，否则凭据解析后新建。 */
    fun open(
        host: Host,
        autoReconnect: Boolean,
        launchMode: TerminalLaunchMode = TerminalLaunchMode.SHELL,
        onSystemDetected: ((Host) -> Unit)? = null,
    ): TerminalController {
        TermLog.i("session") { "open ${host.name} (${sessions.count { it.host.id == host.id } + 1}th)" }
        // 同一主机支持多个会话（Termius 风格）：每次打开都新建，
        // 旧的保留在列表，可从卡片下拉/连接页重入。
        val (pw, key) = resolveCredentials(host)
        val controller =
            TerminalController(
                host,
                pw,
                key,
                repository,
                autoReconnect,
                launchMode = launchMode,
                strings = strings,
            )
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
        TermLog.i("session") { "remove ${controller.host.name}" }
        controller.destroy()
        sessions.remove(controller)
        persist()
    }

    /**
     * 当前主机配置 + 已存凭据的签名。与 [TerminalController.credentialKey] 比对：
     * 不一致即主机配置/凭据已变更——旧会话不复用，应新建（旧会话保留可手动关闭）。
     */
    fun signatureFor(host: Host): String {
        val (pw, key) = resolveCredentials(host)
        return credentialSignature(host, pw, key)
    }

    /**
     * 登记 SFTP 会话（连接成功后由调用方加入）。返回条目：调用方建 tab 时用同一 uiState。
     * 每主机单会话：已有条目则替换（保留浏览状态，重连语义一致）——
     * 否则进程恢复条目 + 新连接会并存两个同主机条目。
     */
    fun addSftp(
        host: Host,
        session: SftpSession,
        connectionToken: Any,
    ): SftpSessionEntry {
        val existing = sftpSessions.firstOrNull { it.host.id == host.id }
        val entry =
            SftpSessionEntry(
                host = host,
                session = session,
                uiState = existing?.uiState ?: SftpUiState(),
                connectionToken = connectionToken,
                createdAt = existing?.createdAt ?: Clock.System.now().toEpochMilliseconds(),
            )
        // 先摘除旧条目（登记新代次）再 close 旧连接：close 同步触发旧连接的
        // onClosed，届时代次已不匹配，不会把共享 uiState 误标 disconnected
        if (existing != null) {
            sftpSessions.remove(existing)
        }
        sftpSessions.add(entry)
        existing?.session?.let { old ->
            try {
                old.close()
            } catch (_: Exception) {
            }
        }
        persist()
        return entry
    }

    /**
     * SFTP 断线重连：替换条目中的 session（uiState 保留，浏览路径/列表不丢）。
     * 旧 session 先 close 释放连接；重连失败由调用方负责提示。
     */
    fun reconnectSftp(
        entry: SftpSessionEntry,
        newSession: SftpSession,
        connectionToken: Any,
    ) {
        TermLog.i("sftp") { "reconnect ${entry.host.name}" }
        val idx = sftpSessions.indexOf(entry)
        if (idx >= 0) {
            val old = entry.session
            // 先替换（新代次立即生效）再关旧连接：旧连接 close 同步触发 onClosed，
            // 代次已不匹配 → 不误标 disconnected（否则重连成功 banner 也不消失）
            sftpSessions[idx] =
                SftpSessionEntry(
                    entry.host,
                    newSession,
                    entry.uiState,
                    connectionToken,
                    entry.createdAt,
                )
            old?.let {
                try {
                    it.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    /** 断开 SFTP 会话但保留条目（与终端会话同语义：灰点可重连，浏览状态保留）。 */
    fun disconnectSftp(entry: SftpSessionEntry) {
        // 先置空 session 再 close：close 同步触发 onClosed，此时条目已无 session，
        // 回调查不到可标记对象（disconnected 由这里主动置位，不受旧回调干扰）
        val idx = sftpSessions.indexOf(entry)
        if (idx >= 0) {
            sftpSessions[idx] =
                SftpSessionEntry(
                    host = entry.host,
                    session = null,
                    uiState = entry.uiState,
                    createdAt = entry.createdAt,
                )
            entry.uiState.disconnected = true
            // 用户主动断开：不自动重连（进 tab 显示断开 banner，手动点重连）；
            // 意外断链（onClosed 路径）仍保留自动重连一次
            entry.uiState.autoReconnectAttempted = true
        }
        try {
            entry.session?.close()
        } catch (_: Exception) {
        }
        persist()
    }

    fun closeSftp(entry: SftpSessionEntry) {
        // 先移除再 close：close 同步触发 onClosed，届时条目已不在列表，
        // 不会误标/误日志
        sftpSessions.remove(entry)
        try {
            entry.session?.close()
        } catch (_: Exception) {
        }
        persist()
    }

    /** 关闭主机的全部会话：断开但保留可重入（终端灰点重连；SFTP 保路径重连恢复浏览）。 */
    fun closeAllForHost(hostId: String) {
        sessions.filter { it.host.id == hostId }.forEach { disconnect(it) }
        sftpSessions.filter { it.host.id == hostId && it.session != null }.toList().forEach { disconnectSftp(it) }
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
internal fun credentialSignature(
    host: Host,
    password: String?,
    privateKeyPem: String?,
): String {
    // 敏感字段只保留散列：避免明文密码/私钥以签名串形式常驻内存
    fun hash(s: String?): String = if (s == null) "-" else base64Encode(Sha256.digest(s.encodeToByteArray()))
    return "${host.username}@${host.hostname}:${host.port}|${host.authMethod}|" +
        "${hash(password)}|${hash(privateKeyPem)}|" +
        "${host.startupCommand}|${host.connectionMode}|${host.moshUdpPort}|${host.moshThemeSync}"
}
