package dev.termish.ui

import dev.termish.data.ConnectionMode
import dev.termish.herdr.HerdrProbe
import dev.termish.herdr.parseHerdrSnapshot
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
import dev.termish.util.NetworkChangeKind
import dev.termish.util.TermLog
import dev.termish.util.TermTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 连接编排层：三模式（SSH / Mosh / HERDR）建连、重连退避、网络事件、
 * herdr 探测/引导/降级、mosh 主题注入、系统探测。
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
        /** herdr 官网安装命令（curl 管道 sh，默认装到 ~/.local/bin）。 */
        private const val HERDR_INSTALL_CMD = "curl -fsSL https://herdr.dev/install.sh | sh"
        /** 安装超时：下载二进制 + 校验，给足时间（默认 exec 15s 不够）。 */
        private const val HERDR_INSTALL_TIMEOUT_MS = 180_000L
    }

    // ------------------------------------------------------------------
    // 入口
    // ------------------------------------------------------------------

    fun connect(columns: Int, rows: Int) {
        // 允许 IDLE / 已断开 / 失败状态下（重）连接，缓冲保留
        if (c.status != ConnStatus.IDLE && c.status != ConnStatus.CLOSED && c.status != ConnStatus.ERROR) return
        c.lastCols = columns
        c.lastRows = rows
        c.reconnectAttempts = 0
        c.reconnectCount = 0
        c.errorMessage = null
        doConnect()
    }

    /** 按最近一次窗口尺寸重连（保留屏幕缓冲）；用于退到后台后回前台恢复会话。 */
    fun reconnect() {
        if (c.status != ConnStatus.IDLE && c.status != ConnStatus.CLOSED && c.status != ConnStatus.ERROR) return
        connect(c.lastCols, c.lastRows)
    }

    private fun newConnection() = SshConnection(
        host = c.host.hostname,
        port = c.host.port,
        username = c.host.username,
        password = c.password,
        privateKeyPem = c.privateKeyPem,
        keepAliveSeconds = c.repository.loadSettings().keepaliveSeconds,
        terminalType = c.repository.loadSettings().terminalType,
        // 重连场景网络多半已断：TCP 超时从 15s 缩短到 5s——
        // 否则 3 次重连 × 15s ≈ 1 分钟「连接中」（用户感知卡死）
        connectTimeoutMillis = if (c.reconnectAttempts > 0) 5_000 else 15_000,
    )

    private fun doConnect() {
        val t0 = c.nowMs()
        TermLog.i("ssh") { "connect start ${c.host.name} ${c.host.hostname}:${c.host.port} mode=${c.host.connectionMode} attempt=${c.reconnectAttempts} timeout=${newConnection().connectTimeoutMillis}ms" }
        // 连接 span：引擎经 onTraceStep 填充阶段耗时（tcp+kex/auth/shell）
        val trace = TermTrace.begin(
            "ssh.connect", "ssh",
            "host" to c.host.name, "attempt" to c.reconnectAttempts.toString(),
            "mode" to c.host.connectionMode.name,
        )
        c.status = ConnStatus.CONNECTING
        c.scope.launch {
            try {
                when (c.host.connectionMode) {
                    ConnectionMode.MOSH -> {
                        doConnectMosh()
                        return@launch
                    }
                    ConnectionMode.HERDR -> {
                        doConnectHerdr()
                        return@launch
                    }
                    ConnectionMode.SSH -> {}
                }
                val s = c.sessionFactory(newConnection(), c.callbacks(trace))
                c.session = s
                val info = s.connectAndStart(c.lastCols, c.lastRows)
                // 连接期间用户可能已关闭会话：不能置 CONNECTED，且必须释放刚建好的连接
                if (c.status == ConnStatus.CLOSED) {
                    c.session = null
                    try { s.close() } catch (_: Exception) {
                    }
                    return@launch
                }
                // TOFU：记录主机指纹
                info.hostKey?.let { c.repository.touchConnected(c.host.id, it.fingerprintSha256) }
                trace.step("connected")
                trace.end()
                TermLog.i("ssh") { "connected ${c.host.name} kex=${info.kexAlgorithm} in ${c.nowMs() - t0}ms" }
                finishConnected(s)
                // HERDR 模式：探测 herdr（snapshot 成功 = 已装）——显式选模式即同意监控
                if (c.host.connectionMode == ConnectionMode.HERDR) {
                    val ssh = s
                    c.scope.launch {
                        val raw = runCatching { ssh.runCommand("herdr api snapshot") }.getOrNull()
                        c.herdrAvailable = raw != null && raw.isNotBlank()
                        TermLog.d("herdr") { "probe ${c.host.name}: herdrAvailable=${c.herdrAvailable}" }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e // 协程取消不是连接失败：不置 ERROR、不停保活
            } catch (e: Exception) {
                if (c.status != ConnStatus.CLOSED) {
                    val elapsed = c.nowMs() - t0
                    trace.fail(e.message)
                    if (elapsed > 10_000) {
                        TermLog.w("ssh") { "connect SLOW/FAIL after ${elapsed}ms ${c.host.name}: ${e.message}（TCP 超时特征：网络黑洞/防火墙丢包）" }
                    } else {
                        TermLog.e("ssh") { "connect failed after ${elapsed}ms ${c.host.name}: ${e.message}" }
                    }
                    c.status = ConnStatus.ERROR
                    c.errorMessage = e.message
                    // 自动重连失败：会话已死，必须停掉保活，否则前台服务+wakelock 空转
                    //（首连失败时 keepAliveActive=false，stopKeepAlive 有 guard，安全）
                    c.stopKeepAlive()
                    // 重连上下文（非首次连接）失败：后台通知，提示需人工干预
                    if (c.reconnectAttempts > 0) {
                        NotificationCenter.post(
                            NotificationEvent.RECONNECT_FAILED,
                            "Termish",
                            strings().notificationReconnectFailed(c.host.name, e.message ?: strings().terminalFailed),
                            hostId = c.host.id,
                        )
                    }
                }
            }
        }
    }

    /**
     * Mosh 模式：SSH 引导 mosh-server，UDP 首包确认后关闭 SSH（引导工具使命完成）。
     * 降级语义（两种）：引导失败 = 远端未安装 mosh-server → 降级
     * （Mosh：SSH shell；HERDR：SSH + herdr TUI）；引导成功但 UDP 首包超时 =
     * mosh 连接失败 → 同样降级到 SSH（SSH 引导通道还活着，直接当显示通道——
     * UDP 不通时用户至少拿到一个可用 shell，而不是面对一个报错发愣；
     * banner 提示降级原因 + 固定 UDP 端口解法）。
     *
     * @param existingSession HERDR 复用已认证连接（探测后引导；null = 自建）
     * @param bootstrapExtra 引导命令追加参数（HERDR：` -- herdr`，mosh 会话直接跑 herdr）
     * @param onDegraded 降级回调（HERDR：exec+pty 跑 herdr；null = 普通 shell）
     */
    private suspend fun doConnectMosh(
        existingSession: SshSession? = null,
        bootstrapExtra: String = "",
        onDegraded: (suspend (SshSession) -> Unit)? = null,
    ) {
        val t0 = c.nowMs()
        try {
            // 1. SSH 连接 + shell（引导通道；降级时变显示通道，Mosh 成功时关闭）
            val s = existingSession ?: c.sessionFactory(newConnection(), c.callbacks())
            if (existingSession == null) {
                c.session = s
                val info = s.connectAndStart(c.lastCols, c.lastRows)
                if (c.status == ConnStatus.CLOSED) {
                    c.session = null
                    try { s.close() } catch (_: Exception) { }
                    return
                }
                info.hostKey?.let { c.repository.touchConnected(c.host.id, it.fingerprintSha256) }
            }

            // 2. 同连接引导 mosh-server（runCommand 复用已认证连接；探测输出跟在
            //    MOSH CONNECT 之后，不影响 parseMoshConnect，自动识别系统）
            val moshColors = if (c.repository.loadSettings().terminalType == "xterm-256color") "256" else "8"
            val baseBootstrap = if (c.host.moshUdpPort in 1024..65535) {
                "mosh-server new -c $moshColors -p ${c.host.moshUdpPort} -l LANG=en_US.UTF-8$bootstrapExtra"
            } else {
                "mosh-server new -c $moshColors -l LANG=en_US.UTF-8$bootstrapExtra"
            }
            val bootstrap = "$baseBootstrap 2>&1; $SYSTEM_PROBE_COMMAND"
            TermLog.i("mosh") { "bootstrap ${c.host.name}: $baseBootstrap" }
            val raw = s.runCommand(bootstrap, 5_000)
            val parsed = raw?.let { parseMoshConnect(it) }
            detectSystemFromOutput(raw ?: "")?.takeIf { it.isNotBlank() }?.let { detected ->
                if (c.host.system.isBlank()) {
                    val updated = c.host.copy(system = detected)
                    c.repository.upsertHost(updated)
                    c.onSystemDetected?.invoke(updated)
                }
            }
            if (parsed == null) {
                // 3a. 引导失败 = 远端未装 mosh-server（或固定端口被占）→ 降级路径之一
                val rawTrimmed = raw?.trim()?.take(120)
                val reason = when {
                    c.host.moshUdpPort in 1024..65535 && raw?.contains("Address already in use") == true ->
                        strings().moshPortBusy(c.host.moshUdpPort)
                    // 2>&1 后 command not found 进 stdout：精确识别「未安装 mosh-server」
                    raw?.contains("not found") == true || raw?.contains("No such file") == true ->
                        strings().moshServerMissing
                    rawTrimmed.isNullOrEmpty() -> strings().moshServerMissing
                    else -> strings().moshBootstrapFailed(rawTrimmed)
                }
                TermLog.w("mosh") { "mosh bootstrap failed ${c.host.name}: $reason——降级" }
                if (onDegraded != null) {
                    onDegraded(s)
                } else {
                    finishConnected(s)
                }
                showDegradeNotice(reason)
                TermLog.i("mosh") { "mosh degraded ${c.host.name} in ${c.nowMs() - t0}ms" }
                return
            }
            val (moshPort, moshKey) = parsed
            TermLog.i("mosh") { "mosh-server up port=$moshPort ${c.host.hostname}" }

            // 3b. 引导成功：建 mosh client，等 UDP 首包确认（连接成功判定）
            withContext(Dispatchers.Main) { prepareThemeSync() }
            val peerReady = CompletableDeferred<Unit>()
            val client = createKmpMoshSession(
                ip = c.host.hostname,
                port = moshPort,
                key = moshKey,
                columns = c.lastCols,
                rows = c.lastRows,
                scope = c.scope,
                uiBuffer = c.buffer,
                onTitle = { t -> c.title = t },
                onClipboard = { text ->
                    if (c.repository.loadSettings().osc52Clipboard) c.onRemoteClipboard?.invoke(text)
                },
                onExit = { reason -> handleMoshExit(reason) },
                onFrame = { c.frame++ },
                onPeerConnected = {
                    peerReady.complete(Unit)
                    c.moshSession?.let { onMoshConnected(it) }
                },
                onLinkStatus = { secs ->
                    if (secs >= LINK_LOST_THRESHOLD_SECONDS && c.linkLostSeconds < LINK_LOST_THRESHOLD_SECONDS) {
                        TermLog.w("mosh") { "link lost ${c.host.name} ${secs}s" }
                    }
                    c.linkLostSeconds = secs
                },
            )
            c.moshSession = client
            TermLog.i("mosh") { "mosh client started ${c.host.name} cols=${c.lastCols}x${c.lastRows}" }
            if (c.status == ConnStatus.CLOSED) {
                c.moshSession = null
                client.close()
                return
            }
            val udpOk = withTimeoutOrNull(MOSH_UDP_CONFIRM_MS) { peerReady.await() }
            if (udpOk == null && c.status != ConnStatus.CONNECTED) {
                // 3c. 引导成功但 UDP 首包超时 = mosh 连接失败：降级到 SSH
                //（SSH 引导通道还活着，直接当显示通道；UDP 不通是真实故障但
                // 用户至少拿到一个可用 shell，banner 提示原因与固定端口解法）
                TermLog.w("mosh") { "mosh UDP unconfirmed ${c.host.name} ${MOSH_UDP_CONFIRM_MS}ms——降级 SSH" }
                c.moshSession?.close()
                c.moshSession = null
                if (onDegraded != null) {
                    onDegraded(s)
                } else {
                    finishConnected(s)
                }
                showDegradeNotice(strings().moshUdpDegraded)
                TermLog.i("mosh") { "mosh degraded-to-ssh ${c.host.name} in ${c.nowMs() - t0}ms" }
                return
            }
            // 3d. UDP 确认成功：mosh 连接成功——关闭 SSH（引导工具使命完成；
            //     close 同步触发 onClosed，用 swallowClosed 短路避免误判断开）
            c.swallowClosed = true
            c.session = null
            try { s.close() } catch (_: Exception) { }
            c.swallowClosed = false
            TermLog.i("mosh") { "mosh connected ${c.host.name} in ${c.nowMs() - t0}ms（SSH 引导通道已关）" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (c.status != ConnStatus.CLOSED) {
                TermLog.e("mosh") { "mosh connect failed ${c.host.name}: ${e.message}" }
                c.status = ConnStatus.ERROR
                c.errorMessage = e.message
                c.stopKeepAlive()
            }
        }
    }

    /**
     * HERDR 模式：herdr 的一等连接模式（Mosh 优先，降级能力在 Mosh 内）。
     *
     * - SSH 连接（认证/TOFU 一次）→ 探测 herdr（候选路径；失败明确报错并释放）
     * - 探测成功 → Mosh 引导 `mosh-server new -- herdr`（mosh 会话直接跑 herdr TUI）
     *   - 引导失败（无 mosh-server）→ 降级 SSH + exec+pty 跑 herdr（无 shell 回显）
     *   - UDP 首包超时 → 同样降级 SSH + exec+pty 跑 herdr（banner 提示原因）
     *   - Mosh 成功 → 关闭 SSH（漫游）
     */
    private suspend fun doConnectHerdr() {
        val t0 = c.nowMs()
        try {
            // HERDR 连接阶段：shell 输出抑制（决策前不渲染，避免提示符/命令回显割裂）
            c.herdrSuppressShellOutput = true
            // 1. SSH 连接 + shell（探测/引导通道）
            val s = c.sessionFactory(newConnection(), c.callbacks())
            c.session = s
            val info = s.connectAndStart(c.lastCols, c.lastRows)
            if (c.status == ConnStatus.CLOSED) {
                c.session = null
                try { s.close() } catch (_: Exception) { }
                return
            }
            info.hostKey?.let { c.repository.touchConnected(c.host.id, it.fingerprintSha256) }

            // 2. 探测 herdr：失败明确报错（不降级——选 HERDR = herdr 工作台）
            val probed = HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
            if (probed == null) {
                // 远端无 herdr：保留 SSH 连接作为引导通道，进入「待安装」状态
                //（banner 显示安装按钮，点击后自动装并继续 HERDR 连接）
                TermLog.w("herdr") { "HERDR not found ${c.host.name}: 引导安装" }
                c.herdrSuppressShellOutput = false
                finishConnected(s, sendStartup = false)
                c.herdrNeedsInstall = true
                return
            }
            val herdrBin = probed.bin
            TermLog.i("herdr") { "HERDR probe ok ${c.host.name} bin=$herdrBin" }

            // 3. Mosh 优先（降级在 doConnectMosh 内）：mosh-server 直接跑 herdr
            doConnectMosh(
                existingSession = s,
                bootstrapExtra = " -- $herdrBin",
                onDegraded = { ssh ->
                    // 降级（无 mosh-server）：SSH + exec+pty 直接跑 herdr TUI（无回显）
                    startHerdrExec(ssh, herdrBin)
                },
            )
            c.herdrSuppressShellOutput = false
            TermLog.i("herdr") { "HERDR connected ${c.host.name} in ${c.nowMs() - t0}ms" }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (c.status != ConnStatus.CLOSED) {
                TermLog.e("herdr") { "HERDR connect failed ${c.host.name}: ${e.message}" }
                c.status = ConnStatus.ERROR
                c.errorMessage = e.message
                c.stopKeepAlive()
            }
        }
    }

    /**
     * HERDR 引导安装：远端无 herdr 时，在已认证连接上执行官网安装脚本
     * （curl https://herdr.dev/install.sh | sh），成功后重新探测并继续 HERDR
     * 连接（Mosh 优先）。失败则 banner 显示原因，可再次点击重试。
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
                withContext(Dispatchers.IO) {
                    // 流式 exec（JVM）：逐块读脚本输出进日志；iOS 无 startExec 回退 runCommand
                    val exec = s.startExec(HERDR_INSTALL_CMD, c.lastCols, c.lastRows)
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
                        log.append(s.runCommand(HERDR_INSTALL_CMD, HERDR_INSTALL_TIMEOUT_MS) ?: "")
                        c.herdrInstallLog = log.toString()
                    }
                }
                // 从安装日志解析实际安装路径（不写死 ~/.local/bin、不依赖 $HOME），
                // 优先探测该路径，回退 HerdrProbe 候选
                val installPath = parseInstallPath(log.toString())
                val probed = withContext(Dispatchers.IO) {
                    val explicit = installPath?.let { path ->
                        val raw = s.runCommand("$path api snapshot", 5_000)
                        raw?.let { snap ->
                            parseHerdrSnapshot(snap)?.let { HerdrProbe.Result(path, it) }
                        }
                    }
                    explicit ?: HerdrProbe.probe { cmd -> s.runCommand(cmd, 5_000) }
                }
                if (probed == null) {
                    TermLog.e("herdr") { "install then probe failed ${c.host.name}: ${log.take(120)}" }
                    c.herdrInstalling = false
                    c.errorMessage = strings().herdrInstallFailed(log.toString().trim().take(200).ifBlank { null })
                    return@launch
                }
                TermLog.i("herdr") { "herdr installed ${c.host.name} bin=${probed.bin}" }
                c.herdrInstalling = false
                c.herdrNeedsInstall = false
                c.herdrSuppressShellOutput = true
                doConnectMosh(
                    existingSession = s,
                    bootstrapExtra = " -- ${probed.bin}",
                    onDegraded = { ssh -> startHerdrExec(ssh, probed.bin) },
                )
                c.herdrSuppressShellOutput = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                c.herdrInstalling = false
                c.errorMessage = strings().herdrInstallFailed(e.message)
            }
        }
    }

    /** HERDR 降级显示通道：exec+pty 跑 herdr（无 shell 提示符/命令回显），
     *  EOF = 工作台关闭（正常结束，关闭整个会话）。 */
    private suspend fun startHerdrExec(s: SshSession, herdrBin: String) {
        val exec = s.startExec(herdrBin, c.lastCols, c.lastRows)
        if (exec != null) {
            c.herdrExec = exec
            // 状态收尾：CONNECTED + 保活 + 免疫期（此前漏掉——会话实际可用
            // 但状态永远停在 CONNECTING，banner 一直显示「连接中…」）
            finishConnected(s, sendStartup = false)
            val execRef = exec
            c.scope.launch {
                try {
                    while (true) {
                        val chunk = execRef.read() ?: break
                        c.enqueueOutput(chunk)
                    }
                    if (c.status != ConnStatus.CLOSED) {
                        TermLog.i("herdr") { "herdr exec exited ${c.host.name}（工作台关闭）" }
                        c.close()
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    if (c.status != ConnStatus.CLOSED) {
                        TermLog.w("herdr") { "herdr exec read error ${c.host.name}: ${e.message}" }
                        c.close()
                    }
                }
            }
            TermLog.i("herdr") { "HERDR degraded to ssh-exec ${c.host.name}" }
        } else {
            // exec+pty 不可用（如 iOS 暂未实现）：退回 shell 命令路径
            s.sendData("$herdrBin\n".encodeToByteArray())
            finishConnected(s, sendStartup = false)
            TermLog.i("herdr") { "HERDR degraded to ssh-shell ${c.host.name}" }
        }
    }

    /** 连接收尾（Mosh 降级 / SSH 共用）：CONNECTED + 保活 + 免疫期 + 系统探测。 */
    private fun finishConnected(s: SshSession, sendStartup: Boolean = true) {
        c.status = ConnStatus.CONNECTED
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
                    val updated = c.host.copy(system = detected)
                    c.repository.upsertHost(updated)
                    c.onSystemDetected?.invoke(updated)
                }
            }
        }
        // 启动命令（如 tmux new -A -s main）：HERDR 不发送（herdr 是唯一入口）
        if (sendStartup && c.host.startupCommand.isNotBlank()) {
            s.sendData((c.host.startupCommand.trim() + "\n").encodeToByteArray())
        }
        c.frame++
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

    fun handleMoshExit(reason: MoshExitReason) {
        TermLog.w("mosh") { "mosh exit ${c.host.name} reason=$reason status=${c.status}" }
        if (c.status == ConnStatus.CONNECTED) {
            c.moshSession = null
            // 已连接后异常退出（非用户关闭）：自动重连
            if (c.autoReconnect && c.reconnectAttempts < RECONNECT_MOSH_MAX) {
                c.reconnectAttempts++
                c.status = ConnStatus.CONNECTING
                c.reconnectCount = c.reconnectAttempts
                c.errorMessage = null
                c.scope.launch {
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
    private fun moshExitMessage(reason: MoshExitReason): String? = when (reason) {
        MoshExitReason.SESSION_ERROR -> strings().moshSessionError
        MoshExitReason.CONNECT_TIMEOUT -> strings().moshConnectTimeout
        MoshExitReason.NORMAL -> null
    }

    /** mosh 会话真正建立后的统一收尾（收到对端首包时回调）。 */
    private fun onMoshConnected(client: MoshSession) {
        if (c.status == ConnStatus.CLOSED) { // 等待首包期间用户已关闭
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
        // 启动命令同样适用于 mosh 会话（登录 shell 里执行）
        if (c.host.startupCommand.isNotBlank()) {
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
        // 仅当配置了启动命令（TUI 会话：herdr/tmux 等）才注入：
        // 注入的 OSC 应答会作为「用户输入」送达远端 shell，普通 shell（bash
        // readline）不解析 OSC，会把 ESC]10;… 原样回显成特殊字符。
        // 有启动命令说明会话会跑 TUI（herdr 查询终端主题），注入才安全有效。
        if (!c.host.moshThemeSync || c.host.startupCommand.isBlank()) return
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
            c.scope.launch {
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
        // 网络完全丢失（飞行模式等）：TCP 悬挂时 keepalive 写缓冲吸收、读不到
        // EOF，连接会长期显示绿色——主动断开让状态正确，并触发 onClosed 的
        // 退避重连（网络未恢复时失败 → 灰点 + 后台通知；恢复后回前台自动重连）
        if (kind == NetworkChangeKind.LOST) {
            if (c.status == ConnStatus.CONNECTED) {
                TermLog.w("net") { "LOST: force close ${c.host.name}（TCP 悬挂时主动断开）" }
                c.reconnectAttempts = 0
                c.session?.close()
            }
            return
        }
        val now = c.nowMs()
        if (now < c.networkImmuneUntilMs) { TermLog.d("net") { "TRANSPORT: 免疫期内跳过 ${c.host.name}" }; return }
        if (now - c.lastNetworkReconnectAtMs < NETWORK_DEBOUNCE_MS) { TermLog.d("net") { "TRANSPORT: 防抖跳过 ${c.host.name}" }; return }
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
 * 从 install.sh 输出解析实际安装路径（install.sh 打印
 * `installed herdr to /path/herdr`）。不写死 `~/.local/bin`、不依赖 $HOME：
 * 无论脚本装到哪个目录，都以它报告的真实路径为准。
 */
internal fun parseInstallPath(log: String): String? {
    val marker = "installed herdr to "
    val idx = log.indexOf(marker)
    if (idx < 0) return null
    return log.substring(idx + marker.length)
        .substringBefore('\n')
        .trim()
        .takeIf { it.startsWith("/") }
}
