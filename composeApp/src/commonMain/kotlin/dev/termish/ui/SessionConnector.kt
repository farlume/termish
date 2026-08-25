package dev.termish.ui

import dev.termish.data.ConnectionMode
import dev.termish.herdr.HerdrProbe
import dev.termish.mosh.MoshExitReason
import dev.termish.notify.NotificationCenter
import dev.termish.notify.NotificationEvent
import dev.termish.ssh.MoshSession
import dev.termish.ssh.SYSTEM_PROBE_COMMAND
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshSession
import dev.termish.ssh.createKmpMoshSession
import dev.termish.ssh.detectSystemFromOutput
import dev.termish.ssh.parseMoshConnect
import dev.termish.ssh.parseMoshServerPid
import dev.termish.util.NetworkChangeKind
import dev.termish.util.TermLog
import dev.termish.util.TermTrace
import dev.termish.util.ioDispatcher
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 连接编排层：两模式（SSH / Mosh）建连、重连退避、网络事件、herdr 工作台
 * 开关（探测/引导安装/注入）、mosh 主题注入、系统探测。
 *
 * herdr 是远端应用而非传输协议（[Host.launchHerdr]）：
 * - Mosh：引导 `mosh-server new -- herdr`，mosh 会话直接跑 herdr TUI
 * - SSH / mosh 降级：连接后向 shell 注入 `herdr` 命令（退出回 shell）
 * - 远端未装：引导安装卡片（官网脚本，实时日志）；勾选开关 = 显式同意监控
 *
 * 状态所有权（Compose 观察点：status / frame / buffer …）留在
 * [TerminalController]——本类通过同包 internal 访问读写，UI 观察点不变。
 */
internal class SessionConnector(
    private val c: TerminalController,
    private val strings: () -> AppStrings,
) {
    companion object {
        /** SSH 意外断线自动重连上限次数（指数退避，见 [RECONNECT_BASE_DELAY_MS]）。 */
        private const val RECONNECT_SSH_MAX = 3

        /** mosh 已连接后异常退出自动重连上限（UDP 环境更脆弱，上限更低）。 */
        private const val RECONNECT_MOSH_MAX = 2

        /** UDP 首包确认窗口：引导成功但首包未到 = mosh 连接失败 → 降级 SSH。 */
        private const val MOSH_UDP_CONFIRM_MS = 5_000L

        /** UDP 未确认时，通过仍存活的 SSH 控制通道清理本次 detached mosh-server。 */
        private const val MOSH_CLEANUP_TIMEOUT_MS = 3_000L

        /** mosh 降级提示自动消失时长（提示常驻会压住终端顶部）。 */
        private const val MOSH_DEGRADE_NOTICE_MS = 6_000L

        /** 自动重连基础退避：第 n 次重连延迟 2n 秒。 */
        private const val RECONNECT_BASE_DELAY_MS = 2_000L

        /** 连接成功后的网络事件免疫期：刚连上不折腾，避免「连上即断」循环。 */
        private const val NETWORK_IMMUNE_MS = 30_000L

        /** 网络切换主动重连的防抖窗口。 */
        private const val NETWORK_DEBOUNCE_MS = 15_000L

        /** 连接保持稳定后重连计数归零的观察期（mosh「连上即退」防循环）。 */
        private const val MOSH_STABLE_RESET_MS = 30_000L

        /** mosh 主题注入延迟：太早会被 shell readline 当输入回显成乱码，太晚 herdr 显示灰蒙层。 */
        private const val MOSH_THEME_INJECT_DELAY_MS = 1_200L

        /**
         * herdr 注入时机：等终端输出静默（登录 shell 的 PAM MOTD——Ubuntu
         * landscape/ESM 检测可能耗时 1-3s 且输出几十行——已打完）再注入。
         * 静默阈值：连续这么久无新输出即认为 MOTD 结束。
         */
        private const val HERDR_OUTPUT_QUIET_MS = 250L

        /** 静默检测轮询步长。 */
        private const val HERDR_QUIET_POLL_MS = 50L

        /** 静默等待上限：持续有输出的异常场景（如登录脚本在跑日志）到此强制注入。 */
        private const val HERDR_INJECT_MAX_WAIT_MS = 3_000L

        /** herdr 官网安装命令（curl 管道 sh，默认装到 ~/.local/bin）。 */
        private const val HERDR_INSTALL_CMD = "curl -fsSL https://herdr.dev/install.sh | sh"

        /** 安装超时：下载二进制 + 校验，给足时间（默认 exec 15s 不够）。 */
        private const val HERDR_INSTALL_TIMEOUT_MS = 180_000L

        /** mosh 安装超时：包管理器下载 + 依赖，给足时间。 */
        private const val MOSH_INSTALL_TIMEOUT_MS = 180_000L
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    fun connect(
        columns: Int,
        rows: Int,
    ) {
        // 允许 IDLE / 已断开 / 失败状态下（重）连接，缓冲保留
        if (c.status != ConnStatus.IDLE && c.status != ConnStatus.CLOSED && c.status != ConnStatus.ERROR) return
        c.lastCols = columns
        c.lastRows = rows
        c.reconnectAttempts = 0
        c.reconnectCount = 0
        c.errorMessage = null
        // 重连时重置：降级重连（moshDegradedToSsh）的 SSH 输出要正常进显示
        c.moshDisplayTakeover = false
        doConnect()
    }

    /** 按最近一次窗口尺寸重连（保留屏幕缓冲）；用于退到后台后回前台恢复会话。 */
    fun reconnect() {
        if (c.status != ConnStatus.IDLE && c.status != ConnStatus.CLOSED && c.status != ConnStatus.ERROR) return
        connect(c.lastCols, c.lastRows)
    }

    private fun newConnection(): SshConnection {
        val settings = c.repository.loadSettings()
        return SshConnection(
            host = c.host.hostname,
            port = c.host.port,
            username = c.host.username,
            password = c.password,
            privateKeyPem = c.privateKeyPem,
            keepAliveSeconds = settings.keepaliveSeconds,
            terminalType = settings.terminalType,
            // 重连场景网络多半已断：TCP 超时从 15s 缩短到 5s——
            // 否则 3 次重连 × 15s ≈ 1 分钟「连接中」（用户感知卡死）
            connectTimeoutMillis = if (c.reconnectAttempts > 0) 5_000 else 15_000,
        )
    }

    private fun isCurrentGeneration(generation: Int): Boolean = c.connectionGeneration == generation && c.status != ConnStatus.CLOSED

    private fun isCurrentSession(
        generation: Int,
        session: SshSession,
    ): Boolean = isCurrentGeneration(generation) && c.session === session

    /** 释放已被断开/新建连取代的会话，不影响当前代的 session 引用。 */
    private fun closeStaleSession(session: SshSession) {
        if (c.session === session) c.session = null
        try {
            session.close()
        } catch (e: Exception) {
            TermLog.w("ssh") { "stale session close failed ${c.host.name}: ${e.message}" }
        }
    }

    private fun doConnect() {
        val generation = ++c.connectionGeneration
        val t0 = c.nowMs()
        TermLog.i("ssh") {
            "connect start ${c.host.name} ${c.host.hostname}:${c.host.port} mode=${c.host.connectionMode} attempt=${c.reconnectAttempts} timeout=${newConnection().connectTimeoutMillis}ms"
        }
        // 连接 span：引擎经 onTraceStep 填充阶段耗时（tcp+kex/auth/shell）
        val trace =
            TermTrace.begin(
                "ssh.connect",
                "ssh",
                "host" to c.host.name,
                "attempt" to c.reconnectAttempts.toString(),
                "mode" to c.host.connectionMode.name,
            )
        c.status = ConnStatus.CONNECTING
        c.scope.launch {
            try {
                if (!isCurrentGeneration(generation)) return@launch
                when (c.host.connectionMode) {
                    ConnectionMode.MOSH -> {
                        doConnectMosh(generation = generation)
                        return@launch
                    }
                    ConnectionMode.SSH -> {}
                }
                val s = c.sessionFactory(newConnection(), c.callbacks(trace))
                if (!isCurrentGeneration(generation)) {
                    closeStaleSession(s)
                    return@launch
                }
                c.session = s
                // sessionFactory 返回与登记之间也可能发生断开/重连。
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return@launch
                }
                val info = s.connectAndStart(c.lastCols, c.lastRows)
                // 连接期间可能已断开或开始了新一代：旧代不能置 CONNECTED。
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return@launch
                }
                // TOFU：记录主机指纹
                info.hostKey?.let { c.repository.touchConnected(c.host.id, it.fingerprintSha256) }
                trace.step("connected")
                trace.end()
                TermLog.i("ssh") { "connected ${c.host.name} kex=${info.kexAlgorithm} in ${c.nowMs() - t0}ms" }
                // herdr 工作台开关：探测远端 herdr；缺失 → 引导安装卡片
                // （会话保活；勾选开关 = 显式同意 agent 监控）
                if (c.host.launchHerdr) {
                    val probed = HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
                    if (probed == null) {
                        TermLog.w("herdr") { "herdr not found ${c.host.name}: 引导安装" }
                        c.herdrNeedsInstall = true
                        finishConnected(s, generation, sendStartup = false)
                        return@launch
                    }
                    c.herdrBin = probed.bin
                    TermLog.i("herdr") { "herdr probe ok ${c.host.name} bin=${probed.bin}" }
                }
                finishConnected(s, generation)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消不是连接失败：不置 ERROR、不停保活
            } catch (e: Exception) {
                if (isCurrentGeneration(generation)) {
                    val elapsed = c.nowMs() - t0
                    trace.fail(e.message)
                    if (elapsed > 10_000) {
                        TermLog.w(
                            "ssh",
                        ) { "connect SLOW/FAIL after ${elapsed}ms ${c.host.name}: ${e.message}（TCP 超时特征：网络黑洞/防火墙丢包）" }
                    } else {
                        TermLog.e("ssh") { "connect failed after ${elapsed}ms ${c.host.name}: ${e.message}" }
                    }
                    c.errorMessage = e.message
                    // 自动重连失败：会话已死，必须停掉保活，否则前台服务+wakelock 空转
                    // （首连失败时 keepAliveActive=false，stopKeepAlive 有 guard，安全）
                    c.stopKeepAlive()
                    c.session = null

                    if (c.reconnectAttempts > 0 && c.autoReconnect && c.reconnectAttempts < RECONNECT_SSH_MAX) {
                        // doConnect() 自身失败不会再收到 onClosed，必须在这里继续推进
                        // 剩余重试；否则注释所承诺的「最多 3 次」实际只会尝试 1 次。
                        c.reconnectAttempts++
                        c.reconnectCount = c.reconnectAttempts
                        c.status = ConnStatus.CONNECTING
                        val delayMs = RECONNECT_BASE_DELAY_MS * c.reconnectAttempts
                        TermLog.w("ssh") {
                            "reconnect retry ${c.reconnectAttempts}/$RECONNECT_SSH_MAX ${c.host.name} in ${delayMs}ms"
                        }
                        c.scope
                            .launch {
                                delay(delayMs)
                                if (c.status != ConnStatus.CLOSED) doConnect()
                            }.also { c.reconnectJob = it }
                    } else if (c.reconnectAttempts > 0) {
                        // 所有自动重连均失败：只在最终耗尽时关闭会话并通知一次。
                        c.status = ConnStatus.CLOSED
                        TermLog.e("ssh") { "reconnect exhausted ${c.host.name} -> CLOSED" }
                        NotificationCenter.post(
                            NotificationEvent.RECONNECT_FAILED,
                            "Termish",
                            strings().notificationReconnectFailed(c.host.name, e.message ?: strings().terminalFailed),
                            hostId = c.host.id,
                        )
                    } else {
                        // 首次连接失败由 UI 展示错误，不进入自动重连循环。
                        c.status = ConnStatus.ERROR
                    }
                }
            }
        }
    }

    /**
     * Mosh 模式：SSH 引导 mosh-server，UDP 首包确认后关闭 SSH（引导工具使命完成）。
     * herdr 工作台开关（[Host.launchHerdr]）：引导前探测 herdr（缺失 → 安装卡片），
     * 引导命令追加 ` -- herdr`（mosh 会话直接跑 herdr TUI）。
     * 降级语义（两种）：引导失败 = 远端未安装 mosh-server → 安装卡片或降级
     * （SSH shell；launchHerdr 时注入 herdr）；引导成功但 UDP 首包超时 =
     * mosh 连接失败 → 同样降级到 SSH（SSH 引导通道还活着，直接当显示通道——
     * UDP 不通时用户至少拿到一个可用 shell，而不是面对一个报错发愣；
     * banner 提示降级原因 + 固定 UDP 端口解法）。
     *
     * @param existingSession 复用已认证连接（安装引导后续连；null = 自建）
     */
    private suspend fun doConnectMosh(
        existingSession: SshSession? = null,
        generation: Int = c.connectionGeneration,
    ) {
        val t0 = c.nowMs()
        try {
            if (!isCurrentGeneration(generation)) return
            // 1. SSH 连接 + shell（引导通道；降级时变显示通道，Mosh 成功时关闭）
            val s = existingSession ?: c.sessionFactory(newConnection(), c.callbacks())
            if (existingSession == null) {
                if (!isCurrentGeneration(generation)) {
                    closeStaleSession(s)
                    return
                }
                c.session = s
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return
                }
                val info = s.connectAndStart(c.lastCols, c.lastRows)
                if (!isCurrentSession(generation, s)) {
                    closeStaleSession(s)
                    return
                }
                info.hostKey?.let { c.repository.touchConnected(c.host.id, it.fingerprintSha256) }
            } else if (!isCurrentSession(generation, s)) {
                return
            }

            // UDP 不通降级过的会话条目（moshDegradedToSsh）：不再重试 mosh 引导
            // （UDP 阻断不会因重连自愈，重试只会每次多耗一次引导 + 5s UDP 确认等待），
            // 直接走降级显示通道：SSH shell（launchHerdr 时注入 herdr）
            if (c.moshDegradedToSsh) {
                TermLog.i("mosh") { "mosh degraded earlier ${c.host.name}——重连直走 ssh（跳过 mosh 引导）" }
                if (!ensureHerdrProbed(s)) return // 缺失 → 安装卡片（finishConnected 已置 CONNECTED）
                finishConnected(s, generation)
                return
            }

            // herdr 工作台：引导前探测（缺失 → 安装卡片，SSH 显示通道保留）
            if (!ensureHerdrProbed(s)) return
            if (!isCurrentSession(generation, s)) {
                closeStaleSession(s)
                return
            }
            val bootstrapExtra =
                if (c.host.launchHerdr) {
                    " -- ${shSingleQuote(c.herdrBin ?: "herdr")}"
                } else {
                    ""
                }

            // 2. 同连接引导 mosh-server（runCommand 复用已认证连接；探测输出跟在
            //    MOSH CONNECT 之后，不影响 parseMoshConnect，自动识别系统）
            val moshColors = if (c.repository.loadSettings().terminalType == "xterm-256color") "256" else "8"
            val baseBootstrap =
                if (c.host.moshUdpPort in 1024..65535) {
                    "mosh-server new -s -c $moshColors -p ${c.host.moshUdpPort} -l LANG=en_US.UTF-8$bootstrapExtra"
                } else {
                    "mosh-server new -s -c $moshColors -l LANG=en_US.UTF-8$bootstrapExtra"
                }
            val bootstrap = "$baseBootstrap 2>&1; $SYSTEM_PROBE_COMMAND"
            TermLog.i("mosh") { "bootstrap ${c.host.name}: $baseBootstrap" }
            val raw = s.runCommand(bootstrap, 5_000)
            if (!isCurrentSession(generation, s)) {
                closeStaleSession(s)
                return
            }
            val parsed = raw?.let { parseMoshConnect(it) }
            detectSystemFromOutput(raw ?: "")?.takeIf { it.isNotBlank() }?.let { detected ->
                if (c.host.system.isBlank()) {
                    // 只更新 system 字段（基于仓库最新值）：c.host 是创建时快照，
                    // 整条 upsert 会抹掉刚记录的 TOFU 指纹（新主机重连重复弹窗）
                    c.repository.patchHost(c.host.id) { it.copy(system = detected) }
                    c.onSystemDetected?.invoke(c.host.copy(system = detected))
                }
            }
            if (parsed == null) {
                // 3a. 引导失败 = 远端未装 mosh-server（或固定端口被占）→ 降级路径之一
                val rawTrimmed = raw?.trim()?.take(120)
                // 2>&1 后 command not found 进 stdout：精确识别「未安装 mosh-server」
                val missing =
                    raw?.contains("not found") == true ||
                        raw?.contains("No such file") == true ||
                        rawTrimmed.isNullOrEmpty()
                val reason =
                    when {
                        c.host.moshUdpPort in 1024..65535 && raw?.contains("Address already in use") == true ->
                            strings().moshPortBusy(c.host.moshUdpPort)
                        missing -> strings().moshServerMissing
                        else -> strings().moshBootstrapFailed(rawTrimmed)
                    }
                // 引导失败一律进安装卡片（卡片可选「安装」或「降级 SSH」）：
                // 缺失必然要装；已装但引导失败（locale/依赖等）也提供修复入口，
                // 不再静默降级只剩一条失败通知（用户反馈：没装 mosh-server 却
                // 只看到「引导失败」，没有安装选项）
                TermLog.w("mosh") { "mosh bootstrap failed ${c.host.name}: $reason——引导安装（可降级）" }
                // 先置引导态再置 CONNECTED（消除状态中间帧：awaitStatus 后
                // 立即断言 moshNeedsInstall 的测试不会看到中间帧）
                c.moshNeedsInstall = true
                c.moshInstallReason = if (missing) null else reason
                finishConnected(s, generation, sendStartup = false)
                return
            }
            val (moshPort, moshKey) = parsed
            val moshServerPid = parseMoshServerPid(raw.orEmpty())
            TermLog.i("mosh") { "mosh-server up port=$moshPort ${c.host.hostname}" }
            // 引导成功：待安装状态立即清除（残留可能来自上一次会话）
            c.moshNeedsInstall = false
            c.moshInstallReason = null

            // 3b. 引导成功：建 mosh client，等 UDP 首包确认（连接成功判定）
            withContext(Dispatchers.Main) { prepareThemeSync() }
            val peerReady = CompletableDeferred<Unit>()
            val client =
                createKmpMoshSession(
                    ip = c.host.hostname,
                    port = moshPort,
                    key = moshKey,
                    columns = c.lastCols,
                    rows = c.lastRows,
                    scope = c.scope,
                    uiBuffer = c.buffer,
                    onTitle = { t -> if (isCurrentGeneration(generation)) c.title = t },
                    onClipboard = { text ->
                        if (isCurrentGeneration(generation) && c.repository.loadSettings().osc52Clipboard) {
                            c.onRemoteClipboard?.invoke(text)
                        }
                    },
                    onExit = { reason -> handleMoshExit(reason, generation) },
                    onFrame = { if (isCurrentGeneration(generation)) c.frame++ },
                    onPeerConnected = {
                        peerReady.complete(Unit)
                        c.moshSession?.let { onMoshConnected(it, generation) }
                    },
                    onLinkStatus = { secs ->
                        if (isCurrentGeneration(generation)) {
                            if (secs >= LINK_LOST_THRESHOLD_SECONDS && c.linkLostSeconds < LINK_LOST_THRESHOLD_SECONDS) {
                                TermLog.w("mosh") { "link lost ${c.host.name} ${secs}s" }
                            }
                            c.linkLostSeconds = secs
                        }
                    },
                )
            if (!isCurrentSession(generation, s)) {
                client.close()
                closeStaleSession(s)
                return
            }
            c.moshSession = client
            TermLog.i("mosh") { "mosh client started ${c.host.name} cols=${c.lastCols}x${c.lastRows}" }
            if (!isCurrentSession(generation, s)) {
                c.moshSession = null
                client.close()
                return
            }
            val udpOk = withTimeoutOrNull(MOSH_UDP_CONFIRM_MS) { peerReady.await() }
            if (!isCurrentSession(generation, s)) {
                if (c.moshSession === client) c.moshSession = null
                client.close()
                closeStaleSession(s)
                return
            }
            if (udpOk == null && c.status != ConnStatus.CONNECTED) {
                // 3c. 引导成功但 UDP 首包超时 = mosh 连接失败：降级到 SSH
                // （SSH 引导通道还活着，直接当显示通道；UDP 不通是真实故障但
                // 用户至少拿到一个可用 shell，banner 提示原因与固定端口解法）
                TermLog.w("mosh") { "mosh UDP unconfirmed ${c.host.name} ${MOSH_UDP_CONFIRM_MS}ms——降级 SSH" }
                c.moshSession?.close()
                c.moshSession = null
                cleanupUnconfirmedMoshServer(s, moshServerPid)
                // UDP 不通是环境性阻断：标记本会话条目后续重连直走 SSH，
                // 不再重试 mosh（新开会话才会重新尝试）
                c.moshDegradedToSsh = true
                finishConnected(s, generation)
                showDegradeNotice(strings().moshUdpDegraded)
                TermLog.i("mosh") { "mosh degraded-to-ssh ${c.host.name} in ${c.nowMs() - t0}ms" }
                return
            }
            // 3d. UDP 确认成功：mosh 连接成功——先门控 SSH 引导输出再关闭连接（PAM MOTD
            //     等迟到字节会写进 mosh 会话的 UI buffer，盖在 herdr TUI 下方永久残留），
            //     close 同步触发 onClosed，用 swallowClosed 短路避免误判断开
            c.moshDisplayTakeover = true
            c.swallowClosed = true
            c.session = null
            try {
                s.close()
            } catch (_: Exception) {
            }
            c.swallowClosed = false
            TermLog.i("mosh") { "mosh connected ${c.host.name} in ${c.nowMs() - t0}ms（SSH 引导通道已关）" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (isCurrentGeneration(generation)) {
                TermLog.e("mosh") { "mosh connect failed ${c.host.name}: ${e.message}" }
                c.status = ConnStatus.ERROR
                c.errorMessage = e.message
                c.stopKeepAlive()
            }
        }
    }

    /**
     * UDP 首包未确认时清理本次引导出来的远端进程。
     *
     * PID 来自 mosh-server 自己的 detached 输出；执行 kill 前再次通过 `ps comm`
     * 校验目标仍是 mosh-server，防止极端 PID 复用误伤其他进程。SIGUSR1 是
     * mosh-server 提供的断连会话终止信号，不使用会波及其他会话的 pkill。
     */
    private fun cleanupUnconfirmedMoshServer(
        session: SshSession,
        pid: Int?,
    ) {
        if (pid == null) {
            TermLog.w("mosh") { "mosh cleanup skipped ${c.host.name}: detached pid unavailable" }
            return
        }
        val command =
            "MOSH_PID=$pid; " +
                "MOSH_COMM=\$(ps -p \"\$MOSH_PID\" -o comm= 2>/dev/null); " +
                "case \"\$MOSH_COMM\" in *mosh-server) " +
                "kill -USR1 \"\$MOSH_PID\" && echo MOSH_CLEANUP_OK ;; esac"
        val cleaned =
            runCatching {
                session.runCommand(command, MOSH_CLEANUP_TIMEOUT_MS)?.contains("MOSH_CLEANUP_OK") == true
            }.getOrDefault(false)
        if (cleaned) {
            TermLog.i("mosh") { "cleaned unconfirmed mosh-server pid=$pid ${c.host.name}" }
        } else {
            TermLog.w("mosh") { "mosh cleanup not confirmed pid=$pid ${c.host.name}" }
        }
    }

    /**
     * herdr 工作台开关开启时确保已探测（[c.herdrBin]）；未装 → 引导安装卡片
     *（保留 SSH 连接，置 CONNECTED），返回 false。开关未开直接返回 true。
     */
    private suspend fun ensureHerdrProbed(s: SshSession): Boolean {
        if (!c.host.launchHerdr || c.herdrBin != null) return true
        val probed = HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
        if (probed == null) {
            TermLog.w("herdr") { "herdr not found ${c.host.name}: 引导安装" }
            c.herdrNeedsInstall = true
            finishConnected(s, c.connectionGeneration, sendStartup = false)
            return false
        }
        c.herdrBin = probed.bin
        TermLog.i("herdr") { "herdr probe ok ${c.host.name} bin=${probed.bin}" }
        return true
    }

    /**
     * herdr 引导安装：远端无 herdr 时，在已认证连接上执行官网安装脚本
     * （curl https://herdr.dev/install.sh | sh），成功后重新探测并继续连接
     * （Mosh：带 `-- herdr` 重新引导；SSH / 已降级：注入 herdr 命令）。
     * 安装过程流式读脚本输出进 [TerminalController.herdrInstallLog]（引导卡片
     * 实时展示），卡住时用户能看到日志不再更新。
     */
    fun installHerdr() {
        val s = c.session ?: return
        if (c.herdrInstalling) return
        c.herdrInstalling = true
        c.errorMessage = null
        c.herdrInstallLog = ""
        c.scope.launch {
            try {
                val log = StringBuilder()
                // 非交互 exec 的 $HOME 可能空/错（install.sh 会 mkdir 到 /.local/bin 失败），
                // 用 pwd 拿真实主目录（sshd 默认 chdir 到 home），显式传给安装脚本
                val home =
                    withContext(ioDispatcher()) {
                        s.runCommand("pwd")?.trim()?.takeIf { it.startsWith("/") }
                    }
                // 单引号转义主目录（含空格/特殊字符时不破坏 shell 语义）
                val installCmd =
                    if (home !=
                        null
                    ) {
                        "HOME=${shSingleQuote(home)} $HERDR_INSTALL_CMD"
                    } else {
                        HERDR_INSTALL_CMD
                    }
                withContext(ioDispatcher()) {
                    // 流式 exec（JVM）：逐块读脚本输出进日志；iOS 无 startExec 回退 runCommand
                    val exec = s.startExec(installCmd, c.lastCols, c.lastRows)
                    if (exec != null) {
                        try {
                            while (true) {
                                val chunk = exec.read() ?: break
                                log.append(chunk.decodeToString().replace("\r", ""))
                                c.herdrInstallLog = log.toString()
                            }
                        } finally {
                            exec.close()
                        }
                    } else {
                        log.append(s.runCommand(installCmd, HERDR_INSTALL_TIMEOUT_MS) ?: "")
                        c.herdrInstallLog = log.toString()
                    }
                }
                // 探测：install.sh 输出解析的实际路径 → $home/.local/bin → HerdrProbe 候选。
                // 用 --version（判安装）：刚装完 daemon 必然未运行，api snapshot 会
                // server_not_running 报错——此前装完仍报失败正是这个误判
                val probed =
                    withContext(ioDispatcher()) {
                        val candidates =
                            buildList {
                                parseInstallPath(log.toString())?.let(::add)
                                home?.let { add("$it/.local/bin/herdr") }
                            }.distinct()
                        val explicit =
                            candidates.firstNotNullOfOrNull { path ->
                                val raw = s.runCommand("${shSingleQuote(path)} --version", 5_000)
                                raw
                                    ?.takeIf { HerdrProbe.isVersionOutput(it) }
                                    ?.let { HerdrProbe.Result(path) }
                            }
                        explicit ?: HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
                    }
                if (probed == null) {
                    TermLog.e("herdr") { "install then probe failed ${c.host.name}: ${log.take(120)}" }
                    c.herdrInstalling = false
                    c.errorMessage =
                        strings().herdrInstallFailed(
                            log
                                .toString()
                                .trim()
                                .take(200)
                                .ifBlank { null },
                        )
                    return@launch
                }
                TermLog.i("herdr") { "herdr installed ${c.host.name} bin=${probed.bin}" }
                c.herdrBin = probed.bin
                c.herdrInstalling = false
                c.herdrNeedsInstall = false
                if (c.host.connectionMode == ConnectionMode.MOSH && !c.moshDegradedToSsh) {
                    // Mosh：带 herdr 参数重新引导（mosh 会话直接跑 herdr TUI）
                    doConnectMosh(existingSession = s)
                } else {
                    // SSH / 已降级条目：会话已 CONNECTED，直接注入 herdr 命令
                    // （延迟 + 清屏，与 finishConnected 的注入路径一致：登录 shell
                    //  的 MOTD 迟到输出会把 herdr 画面顶上去）
                    sendHerdrLaunch(s)
                    // 安装成功 → 启动 agent 监控（正常路径由 finishConnected 启动，
                    // 但此分支在 installHerdr 后才恢复可监控状态）
                    c.startHerdrMonitor()
                    c.frame++
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                c.herdrInstalling = false
                c.errorMessage = strings().herdrInstallFailed(e.message)
            }
        }
    }

    /**
     * Mosh 引导安装：Mosh 模式远端未装 mosh-server 时，在已认证连接上按
     * 系统包管理器安装。权限三态：root 直装 / 非 root 免密 sudo（`sudo -n`）/ 非
     * root 需密码（引导卡片输入，`sudo -S` 从 stdin 喂密码——JVM 经 exec 通道
     * 写入不进命令行，iOS 回退 printf 管道；密码不经 Compose 状态、不进日志）。
     * 成功后重新引导 mosh（失败自然回流引导卡片或降级）。
     * 安装过程流式读输出进 [TerminalController.moshInstallLog]（引导卡片实时展示）。
     *
     * @param password sudo 密码（首次点击后探测发现 sudo 需要密码时，卡片会
     * 显示输入框，用户输入后再点安装传入）。
     */
    fun installMosh(password: String? = null) {
        val s = c.session ?: return
        if (c.moshInstalling) return
        c.moshInstalling = true
        c.errorMessage = null
        c.moshInstallLog = ""
        c.scope.launch {
            try {
                // 系统探测（失败按未知处理：提示手动安装）
                val system = detectSystemFromOutput(s.probeSystem() ?: "")
                val isRoot = s.runCommand("id -u", 3_000)?.trim() == "0"
                val installCmd = moshInstallCommand(system)
                if (installCmd == null) {
                    TermLog.w("mosh") { "mosh install unsupported ${c.host.name}: system=$system" }
                    c.moshInstalling = false
                    c.errorMessage = strings().moshInstallUnsupported(system ?: "?")
                    return@launch
                }
                // sudo 需求探测：非 root 时看 `sudo -n true` 是否免密
                val sudoNeedsPassword =
                    if (isRoot) {
                        false
                    } else {
                        val hasSudo = s.runCommand("command -v sudo", 3_000)?.isNotBlank() == true
                        if (!hasSudo) {
                            // 非 root 且无 sudo：无法自动安装，提示手动（避免让用户白输密码）
                            TermLog.w("mosh") { "mosh install ${c.host.name}: sudo not found（非 root）" }
                            c.moshInstalling = false
                            c.errorMessage = strings().moshInstallFailed("sudo not found on remote (non-root user)")
                            return@launch
                        }
                        val sudoNaked = s.runCommand("sudo -n true 2>&1 && echo SUDO_OK", 3_000)
                        sudoNaked?.contains("SUDO_OK") != true
                    }
                if (sudoNeedsPassword && password.isNullOrBlank()) {
                    // 需要密码但用户还没输：卡片显示密码输入框，等用户输入后重试
                    TermLog.w("mosh") { "mosh install ${c.host.name}: sudo needs password——等待用户输入" }
                    c.moshInstalling = false
                    c.moshNeedsSudoPassword = true
                    return@launch
                }
                val fullCmd =
                    when {
                        isRoot -> installCmd
                        sudoNeedsPassword -> "sudo -S -p '' sh -c '$installCmd'"
                        else -> "sudo -n $installCmd"
                    }
                TermLog.i(
                    "mosh",
                ) { "mosh install ${c.host.name}: $installCmd${if (sudoNeedsPassword) "（sudo -S）" else ""}" }
                val log = StringBuilder()
                withContext(ioDispatcher()) {
                    // 流式 exec（JVM）：逐块读安装输出进日志；iOS 无 startExec 回退 runCommand
                    val exec = s.startExec(fullCmd, c.lastCols, c.lastRows)
                    if (exec != null) {
                        try {
                            // sudo -S 从 stdin 读密码：PTY 上 sudo 会关回显，密码不出现在
                            // 命令行（ps 不可见）；写晚于 exec 启动也没事，PTY 输入缓冲兜底
                            if (sudoNeedsPassword) exec.write((password + "\n").encodeToByteArray())
                            while (true) {
                                val chunk = exec.read() ?: break
                                log.append(chunk.decodeToString().replace("\r", ""))
                                c.moshInstallLog = log.toString()
                            }
                        } finally {
                            exec.close()
                        }
                    } else {
                        // iOS 无 exec 通道：POSIX printf 管道喂密码（单引号转义，跨平台无
                        // base64 -d 参数差异问题）；密码错时 sudo 输出 Sorry, try again
                        val piped =
                            if (sudoNeedsPassword) {
                                "printf '%s' ${shSingleQuote(password!!)} | $fullCmd"
                            } else {
                                fullCmd
                            }
                        log.append(s.runCommand(piped, MOSH_INSTALL_TIMEOUT_MS) ?: "")
                        c.moshInstallLog = log.toString()
                    }
                }
                // 防御性清洗：万一 PTY 回显了密码，日志不落明文（短密码不洗，避免误伤）
                if (sudoNeedsPassword && (password?.length ?: 0) >= 4) {
                    c.moshInstallLog = c.moshInstallLog.replace(password!!, "***")
                }
                // 安装后验证 mosh-server 就位（PATH 探测）；失败 → 明确报错，
                // 卡片保留可重试/降级（日志尾部进 banner 提示原因）
                val verified = s.runCommand("command -v mosh-server", 5_000)?.isNotBlank() == true
                if (!verified) {
                    TermLog.e("mosh") { "mosh install then verify failed ${c.host.name}: ${log.take(120)}" }
                    c.moshInstalling = false
                    c.errorMessage =
                        strings().moshInstallFailed(
                            log
                                .toString()
                                .trim()
                                .take(200)
                                .ifBlank { null },
                        )
                    return@launch
                }
                // 安装完成：直接重新引导（bootstrap 即最终验证：成功 → mosh；
                // 仍缺 → 卡片重现可重试/降级；其他错误 → 普通降级）。
                // launchHerdr 开关由 doConnectMosh 内部处理（引导命令带 -- herdr）
                TermLog.i("mosh") { "mosh install finished ${c.host.name}: ${log.take(120)}" }
                c.moshInstalling = false
                c.moshNeedsSudoPassword = false
                c.moshNeedsInstall = false
                doConnectMosh(existingSession = s)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                c.moshInstalling = false
                c.errorMessage = strings().moshInstallFailed(e.message)
            }
        }
    }

    /** 引导卡片上的「降级 SSH」：放弃安装，当前 SSH 显示通道转正。
     *  用户明确选择 SSH → 标记本会话条目后续重连不再重试 mosh。
     *  herdr 工作台开关开启时注入 herdr 命令（退出回 shell）；否则启动命令。 */
    fun degradeMoshToSsh() {
        if (!c.moshNeedsInstall) return
        TermLog.i("mosh") { "mosh install skipped ${c.host.name}——降级 SSH" }
        c.moshNeedsInstall = false
        c.moshNeedsSudoPassword = false
        c.moshDegradedToSsh = true
        val cmd =
            if (c.host.launchHerdr) {
                c.herdrBin
            } else {
                c.host.startupCommand
                    .trim()
                    .takeIf { it.isNotBlank() }
            }
        if (cmd != null) {
            if (c.host.launchHerdr) {
                // herdr 注入统一走延迟 + 清屏路径（防 MOTD 迟到输出顶掉画面）
                sendHerdrLaunch(c.session ?: return)
            } else {
                c.session?.sendData((cmd + "\n").encodeToByteArray())
            }
        }
        c.frame++
    }

    /** 系统 → mosh 安装命令（无 sudo 前缀，权限层在 [installMosh] 按 root/免密/密码组装）；未知系统返回 null。 */
    private fun moshInstallCommand(system: String?): String? =
        when (system) {
            "ubuntu", "debian" -> "apt-get update -y && apt-get install -y mosh"
            "fedora", "centos", "rhel", "rocky", "almalinux" -> "dnf install -y mosh"
            "arch", "manjaro", "endeavouros" -> "pacman -S --noconfirm mosh"
            "alpine" -> "apk add --no-cache mosh"
            "opensuse", "opensuse-leap", "opensuse-tumbleweed", "suse" -> "zypper -n install mosh"
            "void" -> "xbps-install -y mosh"
            "macos", "darwin" -> "brew install mosh"
            else -> null
        }

    /** 连接收尾（Mosh 降级 / SSH 共用）：CONNECTED + 保活 + 免疫期 + 系统探测。 */
    private fun finishConnected(
        s: SshSession,
        generation: Int,
        sendStartup: Boolean = true,
    ) {
        if (!isCurrentSession(generation, s)) {
            closeStaleSession(s)
            return
        }
        c.status = ConnStatus.CONNECTED
        // 静默检测起点：从连接完成算起。MOTD 已打完 → 250ms 后注入 herdr；
        // 还在打 → lastOutputAtMs 被消费循环持续刷新，等它打完再注入。
        c.lastOutputAtMs = c.nowMs()
        c.reconnectAttempts = 0
        c.reconnectCount = 0
        c.errorMessage = null
        c.startKeepAlive()
        c.networkImmuneUntilMs = c.nowMs() + NETWORK_IMMUNE_MS
        c.swallowClosed = false
        if (c.host.system.isBlank()) {
            c.scope.launch {
                val raw = runCatching { s.probeSystem() }.getOrNull()
                TermLog.d("ssh") { "probeSystem ${c.host.name}: ${raw?.take(60) ?: "null"}" }
                val detected = raw?.let { detectSystemFromOutput(it) }
                if (detected != null && detected.isNotBlank() && c.status == ConnStatus.CONNECTED) {
                    // 只更新 system 字段（基于仓库最新值）：c.host 是创建时快照，
                    // 整条 upsert 会抹掉刚记录的 TOFU 指纹（新主机重连重复弹窗）
                    c.repository.patchHost(c.host.id) { it.copy(system = detected) }
                    c.onSystemDetected?.invoke(c.host.copy(system = detected))
                }
            }
        }
        // 启动入口：herdr 工作台开关优先（herdr 即入口，探测拿到的完整路径）；
        // 否则启动命令（如 tmux new -A -s main，实现会话现场恢复）
        if (sendStartup) {
            if (c.host.launchHerdr) {
                sendHerdrLaunch(s)
            } else {
                val cmd =
                    c.host.startupCommand
                        .trim()
                        .takeIf { it.isNotBlank() }
                if (cmd != null) s.sendData((cmd + "\n").encodeToByteArray())
            }
        }
        // herdr 工作台：连接就绪后启动 agent 监控（blocked 通知的轮询源）。
        // 未装 herdr 时走安装卡片路径（finishConnected 提前 return），不会到这；
        // 安装成功后由 installHerdr 显式启动。stop 由 controller.close() 统一收口。
        if (c.host.launchHerdr) c.startHerdrMonitor()
        c.frame++
    }

    /**
     * 经 shell 注入 herdr 启动命令（SSH / 降级路径；Mosh 路径由引导参数 `-- herdr`
     * 直接 exec，不经登录 shell 无此问题）。
     *
     * 等输出静默 + 清屏两个手段叠加，解决 Ubuntu 主机 MOTD 残留：
     * - 登录 shell 的 PAM MOTD（landscape/ESM 检测）可能耗时 1-3s 且输出几十行；
     *   立即注入会被 readline 回显在 MOTD 中间，herdr 启动后 MOTD 迟到字节把画面
     *   顶上去（herdr 只显示头部一小块、下方残留文案）
     * - 注入时机 = 终端输出静默（[HERDR_OUTPUT_QUIET_MS] 无新输出，见
     *   [TerminalController.lastOutputAtMs]）：无 MOTD 的主机 ~250ms 即注入（不
     *   像固定延迟那样干等），有 MOTD 的等它打完再注入（干净）；持续输出场景
     *   [HERDR_INJECT_MAX_WAIT_MS] 上限兜底
     * - 注入命令先清屏（当前屏 + scrollback）再启动 herdr：即使 MOTD 已打出，
     *   herdr 的起始画面也是干净的
     * - 清屏用 printf 八进制转义（POSIX \0ddd，dash/bash 均支持），不依赖
     *   /usr/bin/clear 是否存在
     * 注入前若会话已关闭/重连（session 被替换）则放弃：新连接有自己的一次注入。
     */
    private fun sendHerdrLaunch(s: SshSession) {
        val bin = c.herdrBin ?: "herdr"
        c.scope.launch {
            val t0 = c.nowMs()
            while (c.nowMs() - c.lastOutputAtMs < HERDR_OUTPUT_QUIET_MS &&
                c.nowMs() - t0 < HERDR_INJECT_MAX_WAIT_MS
            ) {
                delay(HERDR_QUIET_POLL_MS)
            }
            if (c.session !== s || c.status != ConnStatus.CONNECTED) return@launch
            val launch = "printf '\\0033[2J\\0033[3J\\0033[H' && ${shSingleQuote(bin)}\n"
            s.sendData(launch.encodeToByteArray())
            TermLog.i("herdr") { "injected herdr launch (quiet+clear) ${c.host.name}" }
        }
    }

    /** 降级 banner：提示降级原因，几秒后自动消失（常驻会压住终端顶部）。
     *  === 只清这条提示；期间若被真实错误覆盖则不清。 */
    private fun showDegradeNotice(message: String) {
        c.errorMessage = message
        c.scope.launch {
            delay(MOSH_DEGRADE_NOTICE_MS)
            if (c.errorMessage === message) c.errorMessage = null
        }
    }

    fun handleMoshExit(
        reason: MoshExitReason,
        generation: Int = c.connectionGeneration,
    ) {
        if (generation != c.connectionGeneration) return
        TermLog.w("mosh") { "mosh exit ${c.host.name} reason=$reason status=${c.status}" }
        if (c.status == ConnStatus.CONNECTED) {
            c.moshSession = null
            // 已连接后异常退出（非用户关闭）：自动重连
            if (c.autoReconnect && c.reconnectAttempts < RECONNECT_MOSH_MAX) {
                c.reconnectAttempts++
                c.status = ConnStatus.CONNECTING
                c.reconnectCount = c.reconnectAttempts
                c.errorMessage = null
                c.scope
                    .launch {
                        delay(RECONNECT_BASE_DELAY_MS * c.reconnectAttempts)
                        if (c.status != ConnStatus.CLOSED) doConnect()
                    }.also { c.reconnectJob = it }
            } else {
                c.status = ConnStatus.CLOSED
                // 会话异常/超时等原因必须浮现（此前被静默丢弃，
                // 用户只能看到「已断开」且不知为何）
                moshExitMessage(reason)?.let { c.errorMessage = it }
                c.stopKeepAlive()
            }
        } else if (c.status == ConnStatus.CONNECTING) {
            c.status = ConnStatus.ERROR
            c.errorMessage = moshExitMessage(reason) ?: strings().moshClientExited
            c.stopKeepAlive()
        }
    }

    /** 退出原因 → 用户可见文案；NORMAL（正常关闭）返回 null（不显示错误）。 */
    private fun moshExitMessage(reason: MoshExitReason): String? =
        when (reason) {
            MoshExitReason.SESSION_ERROR -> strings().moshSessionError
            MoshExitReason.CONNECT_TIMEOUT -> strings().moshConnectTimeout
            MoshExitReason.NORMAL -> null
        }

    /** mosh 会话真正建立后的统一收尾（收到对端首包时回调）。 */
    private fun onMoshConnected(
        client: MoshSession,
        generation: Int,
    ) {
        if (!isCurrentGeneration(generation) || c.moshSession !== client) { // 已关闭或被新代取代
            client.close()
            return
        }
        // 延迟注入：不能太早（herdr 接管前字节会被 shell readline 当输入回显成乱码），
        // 也不能太晚（herdr 默认主题会显示几秒灰色蒙层）。1200ms 时 herdr 通常已接管。
        if (c.moshThemePayload != null) {
            c.scope.launch {
                delay(MOSH_THEME_INJECT_DELAY_MS)
                injectThemeIfNeeded()
            }
        }
        c.status = ConnStatus.CONNECTED
        c.reconnectCount = 0
        c.errorMessage = null
        c.linkLostSeconds = 0
        c.startKeepAlive()
        // 网络切换免疫期 + 稳定期重置：
        // 刚连上 30 秒内网络事件不再触发主动重连；连接保持 30 秒后重连计数归零，
        // 避免「连上即退」场景下 onExit 自动重连无限循环
        c.networkImmuneUntilMs = c.nowMs() + NETWORK_IMMUNE_MS
        c.scope.launch {
            delay(MOSH_STABLE_RESET_MS)
            if (c.status == ConnStatus.CONNECTED) c.reconnectAttempts = 0
        }
        // 启动命令仅普通会话发送：herdr 工作台下 mosh-server 直接跑 herdr
        // （`-- herdr`），再发启动命令会打进 herdr TUI 的输入流
        if (!c.host.launchHerdr && c.host.startupCommand.isNotBlank()) {
            client.sendData((c.host.startupCommand.trim() + "\n").encodeToByteArray())
        }
        c.frame++
    }

    /**
     * Mosh 下把手机终端主题注入远端（herdr 等从 stdin 解析 OSC 应答）。
     * 见 [dev.termish.term.TerminalEmulator.buildThemeSyncPayload]。
     * 连接后固定延迟注入；字节通过 mosh 输入通道送达，
     * herdr 会像收到终端应答一样解析。
     */
    private fun prepareThemeSync() {
        // 仅 TUI 会话（herdr 工作台 / 配置了启动命令的 herdr、tmux 等）才注入：
        // 注入的 OSC 应答会作为「用户输入」送达远端 shell，普通 shell（bash
        // readline）不解析 OSC，会把 ESC]10;… 原样回显成特殊字符。有 TUI 才
        // 会查询终端主题，注入才安全有效。
        if (!c.host.moshThemeSync || (!c.host.launchHerdr && c.host.startupCommand.isBlank())) return
        c.moshThemePayload = c.emulator.buildThemeSyncPayload()
        c.moshThemeInjected = false
    }

    private fun injectThemeIfNeeded() {
        val payload = c.moshThemePayload ?: return
        if (c.moshThemeInjected) return
        val client = c.moshSession ?: return
        if (c.status == ConnStatus.CONNECTED && client.isActive()) {
            c.moshThemeInjected = true
            client.sendData(payload)
        }
    }

    /**
     * SSH 意外断线（callbacks.onClosed 委托）：指数退避自动重连（终端缓冲保留），
     * 重连耗尽置 CLOSED 并发后台通知。
     */
    fun onUnexpectedClose(reason: String?) {
        TermLog.w("ssh") { "onClosed ${c.host.name} reason=$reason status=${c.status} attempts=${c.reconnectAttempts}" }
        val wasConnected = c.status == ConnStatus.CONNECTED || c.status == ConnStatus.AUTH
        if (c.autoReconnect && wasConnected && c.reconnectAttempts < RECONNECT_SSH_MAX) {
            c.reconnectAttempts++
            c.session = null
            c.status = ConnStatus.CONNECTING
            c.reconnectCount = c.reconnectAttempts
            c.errorMessage = null
            c.scope
                .launch {
                    delay(RECONNECT_BASE_DELAY_MS * c.reconnectAttempts)
                    if (c.status != ConnStatus.CLOSED) doConnect()
                }.also { c.reconnectJob = it }
            return
        }
        TermLog.e("ssh") { "reconnect exhausted ${c.host.name} -> CLOSED" }
        c.status = ConnStatus.CLOSED
        c.stopKeepAlive()
        if (reason != null) c.errorMessage = reason
        // 后台事件通知：意外断开（未重连）报 CONNECTION_LOST；
        // 自动重连耗尽仍失败报 RECONNECT_FAILED（用户需人工干预）；
        // 均带「重新连接」动作（通知点击按 hostId 重连）
        NotificationCenter.post(
            if (c.reconnectAttempts > 0) {
                NotificationEvent.RECONNECT_FAILED
            } else {
                NotificationEvent.CONNECTION_LOST
            },
            "Termish",
            if (c.reconnectAttempts > 0) {
                strings().notificationReconnectFailed(c.host.name, reason ?: strings().terminalFailed)
            } else {
                strings().notificationConnectionLost(c.host.name, reason ?: strings().terminalDisconnected)
            },
            hostId = c.host.id,
        )
    }

    /**
     * 网络切换（Wi-Fi ↔ 流量等）时由平台层调用：
     * - SSH：主动断开旧连接，走 onClosed 的自动重连路径（重置计数，避免等 TCP 超时）；
     * - Mosh：UDP 客户端 IP 变化后无法恢复，直接重建（重新 SSH bootstrap）。
     */
    fun onNetworkChanged(kind: NetworkChangeKind) {
        TermLog.i("net") { "network event $kind status=${c.status} immune=${c.nowMs() < c.networkImmuneUntilMs}" }
        if (!c.autoReconnect) return
        // mosh：断网与跨网络切换都【不重建】——UDP 无连接 + 服务器从客户端新源
        // 地址学习回包目标 + 端口轮换，mosh 会在网络变化后自行恢复（原生 mosh
        // 的漫游能力）。只有客户端异常退出（onExit）才走自动重连。
        if (c.moshSession != null) return
        // 单独收到 LOST 不主动拆 SSH：部分 Android ROM 在 App 退后台数秒后会
        // 暂时撤销默认网络回调，但已有 TCP socket 和前台服务仍然有效。此时关
        // session 会造成“切其他 App，回来必重新连接”。真正的网络切换由后续
        // TRANSPORT_CHANGED 处理；socket 确实死亡则 reader/onClosed 自行重连。
        // 这也允许短暂 Wi-Fi 中断后在 IP 未变化时沿用原 TCP 连接。
        if (kind == NetworkChangeKind.LOST) {
            TermLog.i("net") { "LOST: keep socket ${c.host.name}, wait for transport change or onClosed" }
            return
        }
        val now = c.nowMs()
        if (now < c.networkImmuneUntilMs) {
            TermLog.d("net") { "TRANSPORT: 免疫期内跳过 ${c.host.name}" }
            return
        }
        if (now - c.lastNetworkReconnectAtMs < NETWORK_DEBOUNCE_MS) {
            TermLog.d("net") { "TRANSPORT: 防抖跳过 ${c.host.name}" }
            return
        }
        c.lastNetworkReconnectAtMs = now
        when (c.status) {
            ConnStatus.CONNECTED -> {
                c.reconnectAttempts = 0
                c.session?.close()
            }
            // 连接/重连已在途中：网络刚切换，等当前流程完成即可（不重置计数）
            ConnStatus.CONNECTING, ConnStatus.AUTH -> {}
            else -> {}
        }
    }
}

/**
 * POSIX sh 单引号转义：`'` → `'\''`，用于 printf 管道喂 sudo 密码
 * （跨平台无 base64 -d 参数差异）。
 */
internal fun shSingleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * 从 install.sh 输出解析实际安装路径（install.sh 打印
 * `installed herdr to /path/herdr`）。不写死 `~/.local/bin`、不依赖 $HOME：
 * 无论脚本装到哪个目录，都以它报告的真实路径为准。
 */
internal fun parseInstallPath(log: String): String? {
    val marker = "installed herdr to "
    val idx = log.indexOf(marker)
    if (idx < 0) return null
    return log
        .substring(idx + marker.length)
        .substringBefore('\n')
        .trim()
        .takeIf { it.startsWith("/") }
}
