package dev.termish.screen

import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshExecChannel
import dev.termish.ssh.SshSession
import dev.termish.ssh.createSshSession
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock

/** ScreenSession 只持有结构化行为；所有用户可见文案由 AppStrings 在创建时注入。 */
internal data class ScreenSessionMessages(
    val connectionFailed: String,
    val readChannelFailed: String,
    val tcpPortMissing: String,
    val tcpChannelFailed: (Int) -> String,
    val tcpDisconnected: String,
    val screenInUse: String,
    val ffmpegMissing: String,
    val unsupportedOs: (String) -> String,
    val relayUpgradeRequired: String,
    val displayMissing: String,
    val serviceNotRunning: String,
    val waylandHint: String,
    val screenAsleepHint: String,
    val screenLockedHint: String,
    val firstFrameTimeout: String,
    val decoderInitializationFailed: (String?) -> String,
    val decoderNoOutput: (Int) -> String,
    val decodingFailed: (String) -> String,
    val playerUnsupported: String,
) {
    fun playerFailure(failure: ScreenPlayerFailure): String =
        when (failure) {
            is ScreenPlayerFailure.Initialization -> decoderInitializationFailed(failure.detail)
            is ScreenPlayerFailure.NoOutput -> decoderNoOutput(failure.fedFrames)
            is ScreenPlayerFailure.Decoding -> decodingFailed(failure.detail)
            ScreenPlayerFailure.Unsupported -> playerUnsupported
        }
}

/**
 * 屏幕推流会话：独立 SSH 连接 + direct-tcpip 转发远端回环视频服务，
 * TCP 显式分帧后将 H.264 Annex-B 完整帧喂给硬件解码器。
 *
 * 架构（macOS 屏幕录制权限的硬约束决定）：
 * - macOS 的 TCC 只对 GUI 登录会话放行屏幕捕获，SSH/mosh 后台会话无论给
 *   sshd/ffmpeg 授权都无法抓屏（实测：挂起/黑帧/退出）。
 * - 因此推流进程（ffmpeg avfoundation 抓屏 → VideoToolbox 硬编 H.264；
 *   Linux 退回落 libx264 软编）作为
 *   LaunchAgent 跑在用户 GUI 域（launchctl bootstrap gui/$(id -u)），常驻
 *   监听回环端口；手机侧复用 SSH 传输视频/控制 TCP 流，不需要
 *   额外公网端口或 UDP 回程。
 * - 服务缺失时远端上报 SCREEN_SERVICE_MISSING → uiState.serviceMissing，
 *   UI 引导一键安装（[installService]，与 herdr 安装引导同模式）。
 */
class ScreenSession internal constructor(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
    private val scope: CoroutineScope,
    private val uiState: ScreenUiState,
    private val messages: ScreenSessionMessages,
    /** 非主动关闭的断流回调（EOF/异常）：AppRoot 借此自动重连（用户反馈：
     * relay 重启/会话切换导致「画面流已断开」需手动重连）。 */
    private val onStreamLost: (() -> Unit)? = null,
) {
    private var ssh: SshSession? = null
    private var player: ScreenPlayer? = null

    @Volatile
    private var running = false
    private var installing = false

    /** 首帧超时（连接建立后无帧到达视为推流异常，给可见提示）。 */
    private var firstFrameDeadline = 0L
    private var firstFrameError: String? = null

    private var tcpSession: ScreenTcpSession? = null

    fun start() {
        if (running) return
        running = true
        TermLog.i("screen") { "start ${connection.host}:${connection.port}" }
        scope.launch {
            try {
                val session = withContext(ioDispatcher()) { createSshSession(connection, callbacks) }
                ssh = session
                // close() 可能发生在 create/connect 的阻塞阶段（快速切画质、连续点
                // 重连）。旧实现只把 running 置 false，但启动协程仍会继续建立
                // direct-tcpip，成为“幽灵连接”并踢掉新画面，形成数秒一次的循环。
                if (!running) {
                    session.close()
                    return@launch
                }
                // 先建立连接 + 认证，后续控制面 exec 与视频 direct-tcpip
                // 都复用这条 SSH 连接。
                val connected = withContext(ioDispatcher()) { session.connectAuthOnly() }
                TermLog.i("screen") { "connectAuthOnly=${connected != null}" }
                if (!running) {
                    session.close()
                    return@launch
                }
                if (connected == null) {
                    uiState.error = messages.connectionFailed
                    running = false
                    return@launch
                }
                // 控制面探测：relay 存活 + 版本/ffmpeg/屏幕状态 + TCP 内部端口。
                // 视频面随后用同一 SSH 连接的 direct-tcpip 通道访问远端回环端口，
                // 不要求公网额外开放视频端口。
                val result =
                    withContext(ioDispatcher()) {
                        session.runCommandDetailed(READ_STREAM_SCRIPT, 15_000)
                    }
                if (!running) {
                    session.close()
                    return@launch
                }
                if (result == null) {
                    uiState.error = messages.readChannelFailed
                    running = false
                    return@launch
                }
                // 处理远端探测标记（版本过旧/缺 ffmpeg/无显示/服务未运行/屏幕状态）
                if (!handleReadStreamStderr(result.stderr)) {
                    running = false
                    return@launch
                }
                val tcpPort =
                    result.stdout
                        .lineSequence()
                        .firstOrNull { it.startsWith("SCREEN_TCP_PORT:") }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.toIntOrNull()
                val authToken = parseAuthToken(result.stdout)
                // 远端推流参数回读：同步 UI 档位（relay 每连接读 conf，重装 App/
                // 多端写入后远端值可能与本机默认不同——否则 UI 显示 30 实推 120）
                val (cfgFps, cfgScale) = parseStreamCfg(result.stdout)
                cfgFps?.let { uiState.streamFps = it }
                cfgScale?.let { uiState.streamQuality = qualityIndexFor(it) }
                if (tcpPort == null || authToken == null) {
                    uiState.error = if (authToken == null) messages.relayUpgradeRequired else messages.tcpPortMissing
                    uiState.serviceMissing = authToken == null
                    uiState.relayNeedsUpgrade = authToken == null
                    running = false
                    return@launch
                }
                // relay 与 SSH direct-tcpip 通道启动存在很短竞态（服务刚升级/重启时），
                // 小步重试而不是直接把一次 connection-refused 暴露给用户。
                var videoChannel: SshExecChannel? = null
                for (attempt in 0 until 6) {
                    videoChannel =
                        withContext(ioDispatcher()) {
                            session.openDirectTcpip("127.0.0.1", tcpPort)
                        }
                    if (videoChannel != null) break
                    if (attempt < 5) delay(250)
                }
                val directChannel = videoChannel
                if (!running) {
                    directChannel?.close()
                    session.close()
                    return@launch
                }
                if (directChannel == null) {
                    uiState.error = messages.tcpChannelFailed(tcpPort)
                    running = false
                    return@launch
                }
                // 播放器接管 H.264 硬解/渲染；首帧回调清超时。
                val p =
                    ScreenPlayer(
                        onReady = {
                            firstFrameDeadline = 0
                            scope.launch {
                                val readyAt = Clock.System.now().toEpochMilliseconds()
                                uiState.videoReadyAtMillis = readyAt
                                uiState.videoReady = true
                                // 画面到达：清除超时与息屏/锁屏提示（可恢复状态）
                                if (uiState.error == firstFrameError) uiState.error = null
                                firstFrameError = null
                                uiState.screenHint = null
                                delay(SCREEN_STABLE_WINDOW_MS)
                                if (running && uiState.videoReadyAtMillis == readyAt) {
                                    uiState.streamReconnectAttempts = 0
                                }
                            }
                        },
                        onError = { failure -> scope.launch { uiState.error = messages.playerFailure(failure) } },
                    )
                player = p
                uiState.player = p
                p.start()
                // 解码能力探测（按当前画质档位对应分辨率）：档位菜单据此
                // 隐藏解码器跑不满的帧率项（旗舰机 1080p 通常 60+；若探测
                // 值更低说明真瓶颈，推高了也白推——解码器满载排队延迟更高）
                val capScale = uiState.streamQuality
                val capW =
                    when (capScale) {
                        0 -> 960
                        2 -> 1920
                        3 -> 2560
                        else -> 1280
                    }
                uiState.decoderMaxFps = probeDecoderMaxFps(capW, (capW * 9 / 16).coerceAtLeast(480))
                TermLog.i("screen") { "decoder capability: ${uiState.decoderMaxFps}fps @ ${capW}p" }
                // close() 也可能发生在播放器/解码能力初始化期间。必须在真正发送
                // TCP 认证包前再核对一次，否则已被替换的旧会话仍会晚到远端，
                // 抢占新会话并造成 3~6 秒一次的断开循环。
                if (!running) {
                    p.stop()
                    directChannel.close()
                    session.close()
                    return@launch
                }
                firstFrameDeadline = Clock.System.now().toEpochMilliseconds() + 12_000
                // 首帧超时监控：连接建立但迟迟无帧 → 提示（避免无限黑屏）。
                // 首帧到达后 onReady 把 deadline 清零，本监控自然退出
                scope.launch {
                    while (running && firstFrameDeadline > 0) {
                        if (Clock.System.now().toEpochMilliseconds() > firstFrameDeadline) {
                            if (running && !uiState.videoReady && uiState.error == null) {
                                firstFrameError = messages.firstFrameTimeout
                                uiState.error = firstFrameError
                            }
                            break
                        }
                        delay(500)
                    }
                }
                // 双向视频通道复用已认证 SSH：远端 relay 仅监听回环地址，
                // 视频与控制均不依赖运营商 UDP 回程或额外公网端口映射。
                val tcp =
                    ScreenTcpSession(
                        channel = directChannel,
                        scope = scope,
                        authToken = authToken,
                        onVideoPacket = { data -> p.feed(data) },
                        onStatus = { status ->
                            // 首包状态：0=OK，1=macOS 缺辅助功能权限，2=不支持控制，
                            // 3=已有另一台设备占用。占用是明确拒绝，不进入自动重连。
                            if (status == SCREEN_TCP_STATUS_BUSY) {
                                running = false
                                firstFrameDeadline = 0
                                TermLog.i("screen") { "screen stream is already in use by another device" }
                                scope.launch {
                                    runCatching { p.stop() }
                                    if (player === p) player = null
                                    uiState.player = null
                                    uiState.controlSender = null
                                    uiState.keySender = null
                                    uiState.connected = false
                                    uiState.videoReady = false
                                    uiState.error = messages.screenInUse
                                }
                                scope.launch(ioDispatcher()) { session.close() }
                                false
                            } else {
                                scope.launch {
                                    uiState.controlPermissionMissing = status == 1
                                    uiState.controlUnsupported = status == 2
                                }
                                true
                            }
                        },
                        onDisconnected = {
                            // direct-tcpip 断开通常意味着底层 SSH 或 relay 已断；走
                            // AppRoot 的完整重连，重新认证并重建干净通道。
                            if (running) {
                                running = false
                                val readyFor =
                                    uiState.videoReadyAtMillis
                                        .takeIf { it > 0 }
                                        ?.let { Clock.System.now().toEpochMilliseconds() - it }
                                TermLog.w("screen") { "video channel disconnected readyForMs=$readyFor" }
                                scope.launch {
                                    uiState.connected = false
                                    uiState.error = messages.tcpDisconnected
                                }
                                onStreamLost?.invoke()
                            }
                        },
                    )
                tcpSession = tcp
                tcp.start()
                if (!running) {
                    tcp.close()
                    p.stop()
                    session.close()
                    return@launch
                }
                // 视频包持续到达不代表画面仍在推进：部分 Android MediaCodec
                // 驱动会在 native dequeue 中永久阻塞。检测显示层停滞后重建整条
                // 播放链路，避免界面一直停在第一帧却仍显示“控制中”。
                scope.launch {
                    while (running && tcp.isActive()) {
                        delay(1_000)
                        val now = Clock.System.now().toEpochMilliseconds()
                        val renderedAt = p.lastRenderedAtMillis
                        if (
                            isScreenRenderingStalled(
                                now,
                                renderedAt,
                                uiState.videoReady,
                                p.renderSurfaceAttached,
                            )
                        ) {
                            TermLog.w("screen") {
                                "视频渲染停滞：idle=${now - renderedAt}ms，主动重建播放链路"
                            }
                            tcp.fail("render-stalled")
                            break
                        }
                    }
                }
                uiState.connected = true
                // 远程操作发送器：控制包走 TCP 通道
                uiState.controlSender = { type, x, y, extra ->
                    tcp.sendRaw(ScreenControlPacket.encode(type, x, y, extra))
                }
                // 远程键盘发送器：文本 / 键码+修饰 → TCP 控制包
                uiState.keySender = { keyCode, mods, text ->
                    if (text.isNotEmpty()) {
                        runCatching { tcp.sendRaw(ScreenControlPacket.encodeText(text)) }
                    } else {
                        tcp.sendRaw(ScreenControlPacket.encodeKey(keyCode, mods))
                    }
                }
                TermLog.i("screen") { "SSH direct-tcpip 视频会话已建立 remote=127.0.0.1:$tcpPort" }
            } catch (e: Exception) {
                // message 可能为 null（如 NetworkOnMainThreadException），必须记全类名 + 堆栈
                TermLog.w("screen") { "screen session error: ${e::class.qualifiedName}: ${e.message}" }
                TermLog.w("screen") { e.stackTraceToString().take(1500) }
                if (running) {
                    uiState.error = messages.connectionFailed
                    running = false
                    onStreamLost?.invoke()
                }
            }
        }
    }

    /**
     * 处理读流脚本 stderr 的探测标记（一次性；替代原 readErr 循环）。
     * 返回 false = 已设置错误态 / 引导安装态，调用方应停止。
     */
    private fun handleReadStreamStderr(err: String): Boolean {
        if (err.contains("FFMPEG_MISSING")) {
            uiState.ffmpegMissing = true
            uiState.serviceMissing = true
            uiState.error = messages.ffmpegMissing
            return false
        }
        if (err.contains("SCREEN_UNSUPPORTED_OS")) {
            val os =
                err
                    .substringAfter("SCREEN_UNSUPPORTED_OS:")
                    .lineSequence()
                    .first()
                    .trim()
            uiState.error = messages.unsupportedOs(os)
            return false
        }
        if (err.contains("SCREEN_RELAY_OLD") || err.contains("SCREEN_AUTH_MISSING")) {
            uiState.serviceMissing = true
            uiState.relayNeedsUpgrade = true
            uiState.error = messages.relayUpgradeRequired
            return false
        }
        if (err.contains("SCREEN_NO_DISPLAY")) {
            uiState.error = messages.displayMissing
            return false
        }
        if (err.contains("SCREEN_SERVICE_MISSING") ||
            err.contains("SCREEN_VIDEO_SERVICE_MISSING") ||
            err.contains("Connection refused", ignoreCase = true)
        ) {
            uiState.serviceMissing = true
            uiState.error = messages.serviceNotRunning
            return false
        }
        // 提示类（不阻断推流）
        if (err.contains("SCREEN_WAYLAND_ONLY")) {
            uiState.screenHint = messages.waylandHint
        }
        if (err.contains("SCREEN_ASLEEP")) {
            uiState.screenHint = messages.screenAsleepHint
        } else if (err.contains("SCREEN_LOCKED")) {
            uiState.screenHint = messages.screenLockedHint
        }
        return true
    }

    /**
     * 一键安装远端推流服务（幂等）：检测/安装 ffmpeg → 写 LaunchAgent plist
     * → bootstrap 到用户 GUI 域并验证端口监听。与 herdr 安装引导同模式：
     * 流式输出进 [onLog]（UI 实时展示），完成后回调 [onComplete]。
     */
    fun installService(
        sudoPassword: String? = null,
        onLog: (String) -> Unit,
        onComplete: (Boolean) -> Unit,
    ) {
        if (installing) return
        val s = ssh
        if (s == null) {
            onComplete(false)
            return
        }
        installing = true
        uiState.installing = true
        uiState.installLog = ""
        scope.launch {
            try {
                // 与 Mosh 安装一致：Linux 缺 ffmpeg 时先判断权限三态。
                // 仅确实需要交互式 sudo 的场景显示密码框；密码不写进命令行。
                val os = withContext(ioDispatcher()) { s.runCommand("uname -s", 3_000)?.trim() }
                val ffmpegPresent =
                    if (os == "Linux") {
                        withContext(ioDispatcher()) {
                            s.runCommand(FFMPEG_PROBE_SCRIPT, 3_000)?.contains("FFMPEG_OK") == true
                        }
                    } else {
                        true
                    }
                val xlibPresent =
                    if (os == "Linux") {
                        withContext(ioDispatcher()) {
                            s.runCommand(XLIB_PROBE_SCRIPT, 3_000)?.contains("XLIB_OK") == true
                        }
                    } else {
                        true
                    }
                val isRoot =
                    os == "Linux" &&
                        withContext(ioDispatcher()) { s.runCommand("id -u", 3_000)?.trim() == "0" }
                val hasSudo =
                    os == "Linux" &&
                        !isRoot &&
                        withContext(ioDispatcher()) {
                            s.runCommand("command -v sudo", 3_000)?.isNotBlank() == true
                        }
                val sudoPasswordless =
                    if (os == "Linux" && (!ffmpegPresent || !xlibPresent) && !isRoot && hasSudo) {
                        withContext(ioDispatcher()) {
                            s
                                .runCommand("sudo -n true 2>/dev/null && echo SUDO_OK", 3_000)
                                ?.contains("SUDO_OK") == true
                        }
                    } else {
                        false
                    }
                val sudoNeedsPassword =
                    needsScreenSudoPassword(
                        os = os,
                        ffmpegPresent = ffmpegPresent,
                        xlibPresent = xlibPresent,
                        isRoot = isRoot,
                        hasSudo = hasSudo,
                        sudoPasswordless = sudoPasswordless,
                    )
                if (sudoNeedsPassword && sudoPassword.isNullOrBlank()) {
                    TermLog.i("screen") { "Linux ffmpeg install requires sudo password" }
                    installing = false
                    uiState.installing = false
                    uiState.needsSudoPassword = true
                    return@launch
                }

                val log = StringBuilder()
                val command =
                    if (sudoNeedsPassword) {
                        "TERMISH_SUDO_STDIN=1\n$INSTALL_SCRIPT"
                    } else {
                        INSTALL_SCRIPT
                    }
                val ch = withContext(ioDispatcher()) { s.startExecRaw(command) }
                if (ch != null) {
                    // 无 PTY 的 stdin 不回显；sudo -S 只为本次安装读取这一行。
                    if (sudoNeedsPassword) {
                        withContext(ioDispatcher()) {
                            ch.write((sudoPassword + "\n").encodeToByteArray())
                        }
                    }

                    fun visibleLog(): String {
                        val tail = log.toString().takeLast(4096)
                        return if (sudoNeedsPassword && (sudoPassword?.length ?: 0) >= 4) {
                            tail.replace(sudoPassword!!, "***")
                        } else {
                            tail
                        }
                    }

                    // stdout = 安装进度；stderr 错误合并进日志
                    val errJob =
                        scope.launch {
                            while (true) {
                                val err = withContext(ioDispatcher()) { ch.readErr() } ?: break
                                log.append(err.decodeToString().replace("\r", ""))
                                onLog(visibleLog())
                            }
                        }
                    withContext(ioDispatcher()) {
                        while (true) {
                            val data = ch.read() ?: break
                            log.append(data.decodeToString().replace("\r", ""))
                            onLog(visibleLog())
                        }
                        ch.close()
                    }
                    errJob.cancel()
                }
                installing = false
                uiState.installing = false
                // 脚本 set -e 失败时通道 EOF 但退出码拿不到：以脚本的成功标记
                // TERMISH_SCREEN_OK 判定，避免装失败也触发重连白转圈
                val succeeded = log.contains("TERMISH_SCREEN_OK")
                if (succeeded) uiState.needsSudoPassword = false
                onComplete(succeeded)
            } catch (e: Exception) {
                TermLog.w("screen") { "install service error: $e" }
                installing = false
                uiState.installing = false
                onComplete(false)
            }
        }
    }

    /**
     * 设置远端推流参数（帧率/分辨率）：SSH 写 relay 配置（~/.termish-screen.conf），
     * 下次重建会话（relay 每连接读取）生效。
     */
    suspend fun setStreamConfig(
        fps: Int,
        scale: String,
    ): Boolean {
        val s = ssh ?: return false
        // scale 为受控常量（如 1280:-2，无引号字符），安全直接拼接
        val cmd = "printf 'fps=%d\\nscale=%s\\n' $fps '$scale' > ~/.termish-screen.conf"
        return withContext(ioDispatcher()) { s.runCommand(cmd, 5_000) != null }
    }

    fun close() {
        running = false
        firstFrameDeadline = 0
        firstFrameError = null
        tcpSession?.close()
        tcpSession = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player = null
        uiState.player = null
        uiState.controlSender = null
        uiState.keySender = null
        uiState.connected = false
        uiState.videoReady = false
        try {
            ssh?.close()
        } catch (_: Exception) {
        }
        ssh = null
    }

    companion object {
        /** 推流服务 TCP 端口（LaunchAgent 常驻；读流脚本 lsof 探测存活）。 */
        const val SCREEN_PORT = 17321

        /** relay 内部 TCP 视频端口（仅监听远端回环，由 SSH direct-tcpip 访问）。 */
        const val SCREEN_TCP_PORT = 17323

        /** 安装前探测 ffmpeg；与读流/安装脚本使用同一组非交互 SSH PATH 兜底。 */
        internal val FFMPEG_PROBE_SCRIPT =
            """
            for cand in ${'$'}(command -v ffmpeg 2>/dev/null) "${'$'}HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
              if [ -n "${'$'}cand" ] && [ -x "${'$'}cand" ]; then echo FFMPEG_OK; exit 0; fi
            done
            """.trimIndent()

        /** Linux 远程控制依赖探测；缺失时与 ffmpeg 共用一次 sudo 授权安装。 */
        internal val XLIB_PROBE_SCRIPT =
            """
            /usr/bin/python3 -c 'import Xlib' >/dev/null 2>&1 && echo XLIB_OK
            """.trimIndent()

        /** Linux 缺依赖时是否需要弹出 sudo 密码输入；纯逻辑供各平台一致回归。 */
        internal fun needsScreenSudoPassword(
            os: String?,
            ffmpegPresent: Boolean,
            xlibPresent: Boolean = true,
            isRoot: Boolean,
            hasSudo: Boolean,
            sudoPasswordless: Boolean,
        ): Boolean =
            os == "Linux" &&
                (!ffmpegPresent || !xlibPresent) &&
                !isRoot &&
                hasSudo &&
                !sudoPasswordless

        /**
         * relay 协议版本：客户端内置安装脚本部署的 relay 与远端已运行 relay
         * 的匹配标识。更新 relay 行为（推流参数/自愈逻辑）时 +1——
         * 读流脚本检测远端版本文件，不匹配时引导重新安装（用户反馈：
         * 客户端脚本应与远端脚本版本匹配，否则旧 relay 跑不起新功能）。
         */
        const val RELAY_VERSION = 41

        /**
         * 读流前置脚本：只做 relay/版本/ffmpeg/显示状态探测，成功时回报
         * 远端回环 TCP 视频端口。实际视频不经 exec stdout，由 SSH direct-tcpip
         * 建立独立双向通道。
         */
        val READ_STREAM_SCRIPT =
            """
            PORT=$SCREEN_PORT
            OS=${'$'}(uname)
            # 先确认远端确实是可抓取的桌面系统，再检查 relay 版本。否则无桌面的
            # Linux 服务器/容器会被误判成“服务版本旧”，诱导用户执行无意义的安装。
            case "${'$'}OS" in
              Darwin)
                # macOS：avfoundation 抓屏（LaunchAgent 服务）
                :
                ;;
              Linux)
                # Linux 桌面（X11）：ffmpeg x11grab 抓屏。需要图形会话——
                # SSH 无显示环境（服务器/容器）时直接提示，不引导安装。
                # 检测：X socket + X server 进程。Xwayland 也计（Wayland 桌面
                # 的 X11 兼容层，Ubuntu 22.04+ 默认 GNOME 即 Wayland 会话）；
                # 纯 Wayland 只能抓 X11 应用窗口，标记提示但不阻断。
                if ! ls /tmp/.X11-unix/X* >/dev/null 2>&1; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
                fi
                # 不能把 GDM greeter 自己的 Xwayland 当成当前 SSH 用户桌面：
                # 机器刚重启、用户尚未图形登录时它通常属于 gdm-greeter，当前
                # 用户既读不到 Xauthority，也无法注入控制事件。
                HAS_USER_DISPLAY=0
                if pgrep -u "${'$'}(id -u)" -x Xorg >/dev/null 2>&1 \
                  || pgrep -u "${'$'}(id -u)" -x X >/dev/null 2>&1 \
                  || pgrep -u "${'$'}(id -u)" -x Xwayland >/dev/null 2>&1; then
                  HAS_USER_DISPLAY=1
                elif command -v loginctl >/dev/null 2>&1; then
                  for sid in ${'$'}(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="${'$'}(id -u)" '${'$'}2 == uid { print ${'$'}1 }'); do
                    SESSION_TYPE="${'$'}(loginctl show-session "${'$'}sid" -p Type --value 2>/dev/null)"
                    SESSION_REMOTE="${'$'}(loginctl show-session "${'$'}sid" -p Remote --value 2>/dev/null)"
                    case "${'$'}SESSION_TYPE:${'$'}SESSION_REMOTE" in
                      x11:no) HAS_USER_DISPLAY=1; break ;;
                    esac
                  done
                fi
                if [ "${'$'}HAS_USER_DISPLAY" != "1" ]; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
                fi
                if ! pgrep -u "${'$'}(id -u)" -x Xorg >/dev/null 2>&1 \
                  && pgrep -u "${'$'}(id -u)" -x Xwayland >/dev/null 2>&1; then
                  echo "SCREEN_WAYLAND_ONLY" >&2
                fi
                ;;
              *)
                echo "SCREEN_UNSUPPORTED_OS:${'$'}OS" >&2
                exit 1
                ;;
            esac
            # relay 版本匹配：远端版本文件缺失/不一致
            # → 旧 relay（不支持新协议）→ 引导重新安装（用户反馈：客户端脚本
            # 应与远端脚本版本匹配）
            if [ ! -f "${'$'}HOME/.termish-screen.version" ] || [ "${'$'}(cat "${'$'}HOME/.termish-screen.version" 2>/dev/null)" != "$RELAY_VERSION" ]; then
              echo "SCREEN_RELAY_OLD:have=${'$'}(cat "${'$'}HOME/.termish-screen.version" 2>/dev/null || echo none) expect=$RELAY_VERSION" >&2
              exit 1
            fi
            TOKEN_FILE="${'$'}HOME/.termish-screen.token"
            TOKEN="${'$'}(tr -d '\r\n' < "${'$'}TOKEN_FILE" 2>/dev/null || true)"
            if ! printf '%s' "${'$'}TOKEN" | grep -Eq '^[0-9a-fA-F]{64}${'$'}'; then
              echo "SCREEN_AUTH_MISSING" >&2
              exit 1
            fi
            # ffmpeg 查找：SSH 非交互会话 PATH 受限（无 brew 目录），command -v 常漏掉
            # brew 安装的 ffmpeg → 误报 FFMPEG_MISSING（v1.5.0 用户反馈：装过还提示安装）
            FF=""
            for cand in ${'$'}(command -v ffmpeg 2>/dev/null) "${'$'}HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
              if [ -n "${'$'}cand" ] && [ -x "${'$'}cand" ]; then FF="${'$'}cand"; break; fi
            done
            if [ -z "${'$'}FF" ]; then echo "FFMPEG_MISSING" >&2; exit 1; fi
            if ! (lsof -nP -iTCP:${'$'}PORT -sTCP:LISTEN >/dev/null 2>&1 \
              || ss -ltn 2>/dev/null | grep -q ":${'$'}PORT "); then
              echo "SCREEN_SERVICE_MISSING" >&2; exit 1
            fi
            if ! (lsof -nP -iTCP:${'$'}((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1 \
              || ss -ltn 2>/dev/null | grep -q ":${'$'}((PORT + 2)) "); then
              echo "SCREEN_VIDEO_SERVICE_MISSING" >&2; exit 1
            fi
            # 屏幕状态探测（仅提示，不阻断推流）：息屏时 avfoundation 无帧、
            # 锁屏时画面为锁屏界面——客户端据此给出明确提示而非「连接不上」
            if [ "${'$'}OS" = "Darwin" ]; then
            /usr/bin/python3 -c '
            import ctypes
            cg = ctypes.cdll.LoadLibrary("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
            cg.CGMainDisplayID.restype = ctypes.c_uint32
            cg.CGDisplayIsAsleep.argtypes = [ctypes.c_uint32]
            cg.CGDisplayIsAsleep.restype = ctypes.c_ubyte
            if cg.CGDisplayIsAsleep(cg.CGMainDisplayID()):
                print("SCREEN_ASLEEP")
            cg.CGSessionCopyCurrentDictionary.restype = ctypes.c_void_p
            cg.CFStringCreateWithCString.restype = ctypes.c_void_p
            cg.CFStringCreateWithCString.argtypes = [ctypes.c_void_p, ctypes.c_char_p, ctypes.c_int]
            cg.CFDictionaryGetValue.restype = ctypes.c_void_p
            cg.CFDictionaryGetValue.argtypes = [ctypes.c_void_p, ctypes.c_void_p]
            d = cg.CGSessionCopyCurrentDictionary()
            key = cg.CFStringCreateWithCString(None, b"CGSSessionScreenIsLocked", 0x08000100)
            if d and cg.CFDictionaryGetValue(d, key):
                print("SCREEN_LOCKED")
            ' 2>&1 | grep -E 'SCREEN_(ASLEEP|LOCKED)' >&2 || true
            fi
            # 推流参数回读（客户端同步档位显示；conf 可能为其它端写入的旧值）
            CFG="${'$'}HOME/.termish-screen.conf"
            [ -f "${'$'}CFG" ] && grep -E '^(fps|scale)=' "${'$'}CFG" | sed 's/^fps=/SCREEN_CFG_FPS:/;s/^scale=/SCREEN_CFG_SCALE:/' || true
            # 视频流走 SSH direct-tcpip：只回报 relay 的远端回环 TCP 端口，
            # 客户端无需也不会直接访问公网端口。token 仅经已认证 SSH stdout
            # 返回，不写日志；direct-tcpip 建连后先用它做握手。
            echo "SCREEN_TCP_PORT:${'$'}((PORT + 2))"
            echo "SCREEN_AUTH_TOKEN:${'$'}TOKEN"
            """.trimIndent()

        /**
         * 推流服务安装脚本（幂等，远端执行）：检测/安装 ffmpeg（brew 或静态包
         * 到 ~/bin）→ 生成 Python 转发器（accept 客户端 → 拉起 ffmpeg 抓屏推流；
         * 客户端断开 → kill ffmpeg → 循环等下一连接，天然自愈）→ LaunchAgent
         * bootstrap 到用户 GUI 域 → 验证端口监听。
         *
         * GUI 域进程有屏幕录制权限（登录会话），SSH 后台会话没有——这是该架构
         * 存在的根本原因（TCC 对 sshd/ffmpeg 二进制授权均无效，实测）。
         */
        val INSTALL_SCRIPT =
            """
            set -e
            PORT=$SCREEN_PORT
            OS=${'$'}(uname)
            PLIST="${'$'}HOME/Library/LaunchAgents/dev.termish.screen.plist"
            # ---- ffmpeg：缺失时安装（查找路径与读流脚本一致，避免装完仍被非交互
            # PATH 误报缺失）。macOS 走 brew/静态包；Linux 需 apt（sudo）——
            # 检测不到且无 apt 权限时给出明确提示（日志可见）----
            FF=""
            for cand in ${'$'}(command -v ffmpeg 2>/dev/null) "${'$'}HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
              if [ -n "${'$'}cand" ] && [ -x "${'$'}cand" ]; then FF="${'$'}cand"; break; fi
            done
            # Linux 系统依赖共用同一授权入口。不能只在缺 ffmpeg 时定义，否则
            # ffmpeg 已存在、python-xlib 缺失时会绕过 App 提供的 sudo 密码，
            # 最终画面可看但被误报为“不支持远程控制”。
            if [ "${'$'}OS" = "Linux" ]; then
              ADMIN_AVAILABLE=1
              if [ "${'$'}(id -u)" = "0" ]; then
                run_admin() { "${'$'}@"; }
              elif command -v sudo >/dev/null 2>&1 && sudo -n true 2>/dev/null; then
                run_admin() { sudo -n "${'$'}@"; }
              elif [ "${'$'}{TERMISH_SUDO_STDIN:-}" = "1" ] && command -v sudo >/dev/null 2>&1; then
                run_admin() { sudo -S -p '' "${'$'}@"; }
              else
                ADMIN_AVAILABLE=0
                run_admin() { return 1; }
              fi
            fi
            if [ -z "${'$'}FF" ]; then
              if [ "${'$'}OS" = "Darwin" ]; then
              if command -v brew >/dev/null 2>&1; then
                echo "==> 正在通过 Homebrew 安装 ffmpeg（约几分钟）"
                brew install ffmpeg
                # brew 装完重新定位（PATH 受限会话里 command -v 仍可能失败，兜底 brew 前缀）
                FF="${'$'}(command -v ffmpeg 2>/dev/null)"
                [ -z "${'$'}FF" ] && FF="/opt/homebrew/bin/ffmpeg"
                [ -x "${'$'}FF" ] || FF="/usr/local/bin/ffmpeg"
              else
                echo "==> 正在下载 ffmpeg 静态版到 ~/bin"
                mkdir -p "${'$'}HOME/bin"
                curl -fsSL -o /tmp/termish-ffmpeg.zip https://evermeet.cx/ffmpeg/getrelease/zip
                rm -rf /tmp/termish-ffmpeg && mkdir -p /tmp/termish-ffmpeg
                unzip -q /tmp/termish-ffmpeg.zip -d /tmp/termish-ffmpeg
                cp /tmp/termish-ffmpeg/ffmpeg "${'$'}HOME/bin/ffmpeg"
                chmod +x "${'$'}HOME/bin/ffmpeg"
                FF="${'$'}HOME/bin/ffmpeg"
              fi
              else
                # Linux：root 或免密 sudo 时自动安装；App 已提供密码时走 sudo -S；
                # 缺少可用授权方式时输出与发行版匹配的手动命令。
                if [ "${'$'}ADMIN_AVAILABLE" != "1" ]; then
                  if command -v apt-get >/dev/null 2>&1; then
                    echo "==> Linux 需要管理员权限：请先在终端执行 sudo apt-get update && sudo apt-get install -y ffmpeg，然后重试" >&2
                  elif command -v dnf >/dev/null 2>&1; then
                    echo "==> Linux 需要管理员权限：请先在终端执行 sudo dnf install -y ffmpeg，然后重试" >&2
                  elif command -v pacman >/dev/null 2>&1; then
                    echo "==> Linux 需要管理员权限：请先在终端执行 sudo pacman -S --needed ffmpeg，然后重试" >&2
                  else
                    echo "==> Linux 需要 ffmpeg：请用系统包管理器安装后重试" >&2
                  fi
                  exit 1
                fi
                if command -v apt-get >/dev/null 2>&1; then
                  echo "==> 正在通过 apt 安装 ffmpeg"
                  # update + install 放进同一次 sudo：即使远端禁用 sudo 时间戳缓存，
                  # 也只读取一次密码，不会在第二条命令等待额外输入。
                  run_admin sh -c 'apt-get update -qq && apt-get install -y -qq ffmpeg'
                elif command -v dnf >/dev/null 2>&1; then
                  echo "==> 正在通过 dnf 安装 ffmpeg"
                  run_admin dnf install -y -q ffmpeg
                elif command -v pacman >/dev/null 2>&1; then
                  echo "==> 正在通过 pacman 安装 ffmpeg"
                  run_admin pacman -S --needed --noconfirm ffmpeg
                else
                  echo "==> 无法识别 Linux 包管理器，请手动安装 ffmpeg 后重试" >&2
                  exit 1
                fi
                FF="${'$'}(command -v ffmpeg 2>/dev/null)"
                [ -n "${'$'}FF" ] || FF="/usr/bin/ffmpeg"
              fi
            fi
            FF_REAL=${'$'}(readlink -f "${'$'}FF" 2>/dev/null || echo "${'$'}FF")
            echo "==> ffmpeg: ${'$'}FF_REAL"
            # 每个远端账号独立的 256-bit bearer token：回环 TCP/UDP 也会被同机
            # 其它 OS 用户访问，不能把“只监听 127.0.0.1”当作认证边界。
            TOKEN_FILE="${'$'}HOME/.termish-screen.token"
            TOKEN="${'$'}(tr -d '\r\n' < "${'$'}TOKEN_FILE" 2>/dev/null || true)"
            if ! printf '%s' "${'$'}TOKEN" | grep -Eq '^[0-9a-fA-F]{64}${'$'}'; then
              umask 077
              TOKEN_TMP="${'$'}TOKEN_FILE.tmp.${'$'}${'$'}"
              /usr/bin/python3 -c 'import secrets; print(secrets.token_hex(32))' > "${'$'}TOKEN_TMP"
              mv "${'$'}TOKEN_TMP" "${'$'}TOKEN_FILE"
            fi
            chmod 600 "${'$'}TOKEN_FILE"
            # ---- 远程操作依赖：pyobjc（Quartz CGEvent，仅 macOS）----
            # ⚠️ 必须限定 Darwin：pyobjc-framework-* 是 macOS 专属包，Ubuntu 上
            # pip 安装会下载/编译失败甚至卡住（用户反馈：Ubuntu 安装服务失败）——
            # Linux 无此依赖，跳过
            if [ "${'$'}OS" = "Darwin" ]; then
              if /usr/bin/python3 -c "import Quartz, ApplicationServices" 2>/dev/null; then
                echo "==> pyobjc: 已就绪"
              else
                echo "==> 正在安装 pyobjc（远程操作依赖，约 1 分钟）"
                /usr/bin/python3 -m pip install --user -q pyobjc-framework-Quartz pyobjc-framework-ApplicationServices 2>/dev/null \
                  && echo "==> pyobjc: 安装完成" || echo "==> pyobjc: 安装失败——远程操作不可用（可看不可控），重装服务可重试"
              fi
            fi
            # ---- 远程操作依赖：python3-xlib（XTEST 注入）+ xclip（文本粘贴，仅 Linux）----
            if [ "${'$'}OS" = "Linux" ]; then
              # python3-xlib：优先系统包（复用 App 提供的 sudo 密码），失败再退
              # pip --user。Ubuntu 的 externally-managed Python 常会拒绝 pip，
              # 因此不能像旧实现一样只尝试 sudo -n 后静默降级。
              if /usr/bin/python3 -c "import Xlib" 2>/dev/null; then
                echo "==> python-xlib: 已就绪"
              else
                _XOK=0
                if command -v apt-get >/dev/null 2>&1; then
                  { run_admin apt-get install -y -qq python3-xlib 2>/dev/null || /usr/bin/python3 -m pip install --user -q python-xlib 2>/dev/null; } && _XOK=1
                elif command -v dnf >/dev/null 2>&1; then
                  { run_admin dnf install -y -q python3-xlib 2>/dev/null || /usr/bin/python3 -m pip install --user -q python-xlib 2>/dev/null; } && _XOK=1
                elif command -v pacman >/dev/null 2>&1; then
                  { run_admin pacman -S --needed --noconfirm python-xlib 2>/dev/null || /usr/bin/python3 -m pip install --user -q python-xlib 2>/dev/null; } && _XOK=1
                else
                  /usr/bin/python3 -m pip install --user -q python-xlib 2>/dev/null && _XOK=1
                fi
                [ "${'$'}_XOK" = "1" ] && echo "==> python-xlib: 安装完成" || echo "==> python-xlib: 安装失败——远程控制不可用（可看不可控）"
              fi
              # xclip：文本粘贴走剪贴板（无 pip 版，只能系统包）
              if command -v xclip >/dev/null 2>&1; then
                echo "==> xclip: 已就绪"
              else
                _XCLIP_OK=0
                if command -v apt-get >/dev/null 2>&1; then
                  run_admin apt-get install -y -qq xclip 2>/dev/null && _XCLIP_OK=1
                elif command -v dnf >/dev/null 2>&1; then
                  run_admin dnf install -y -q xclip 2>/dev/null && _XCLIP_OK=1
                elif command -v pacman >/dev/null 2>&1; then
                  run_admin pacman -S --needed --noconfirm xclip 2>/dev/null && _XCLIP_OK=1
                fi
                [ "${'$'}_XCLIP_OK" = "1" ] && echo "==> xclip: 安装完成" || echo "==> xclip: 安装失败——文本粘贴不可用（鼠标/键码仍可用）"
              fi
            fi
            # ---- Python 转发器（断开自愈 + 无客户端零开销）----
            APP_DIR="${'$'}HOME/Library/Application Support/termish"
            RELAY="${'$'}APP_DIR/screen-relay.py"
            mkdir -p "${'$'}APP_DIR"
            PORT="${'$'}PORT" FF_REAL="${'$'}FF_REAL" cat > "${'$'}RELAY" <<TERMISH_EOF
            #!/usr/bin/env python3
            import socket, subprocess, time, select, os, signal, sys, threading, zlib, struct, hmac
            RELAY_VERSION = $RELAY_VERSION
            TCP_PORT = ${'$'}PORT
            UDP_PORT = ${'$'}PORT + 1
            TCP_VIDEO_PORT = ${'$'}PORT + 2
            FF = "${'$'}FF_REAL"
            HEARTBEAT_MAGIC = b"THB\x01"
            RELOAD_MAGIC = b"THB\x02"
            CONTROL_MAGIC = b"THC1"
            STATUS_MAGIC = b"THS1"
            AUTH_MAGIC = b"THA1"
            HEARTBEAT_TYPE = 13
            TCP_OWNER_LEASE_SECONDS = 6.0
            AUTH_TOKEN_FILE = os.path.expanduser("~/.termish-screen.token")
            AUTH_TOKEN = open(AUTH_TOKEN_FILE, "rb").read().strip()
            if len(AUTH_TOKEN) != 64 or any(c not in b"0123456789abcdefABCDEF" for c in AUTH_TOKEN):
                raise RuntimeError("invalid screen auth token")

            def stop_legacy_relay_listener():
                # 早期版本可能没有可靠 PID 文件；新进程在 bind 前按监听端口定位
                # 旧 relay，并核对 cmdline 含当前脚本绝对路径后才终止它。
                if sys.platform == "darwin":
                    return
                pids = []
                try:
                    out = subprocess.run(
                        ["lsof", "-t", "-iTCP:%d" % TCP_PORT, "-sTCP:LISTEN"],
                        capture_output=True, text=True, timeout=2,
                    ).stdout
                    pids = [int(p) for p in out.split() if p.isdigit()]
                except Exception:
                    pass
                relay_path = os.path.realpath(__file__)
                stopped = False
                for pid in set(pids):
                    if pid == os.getpid():
                        continue
                    try:
                        cmdline = open("/proc/%d/cmdline" % pid, "rb").read().replace(b"\x00", b" ").decode("utf-8", "ignore")
                        if relay_path not in cmdline:
                            continue
                        os.kill(pid, signal.SIGTERM)
                        stopped = True
                    except Exception:
                        pass
                if stopped:
                    time.sleep(1.0)

            AUD_TYPE = 9  # AUD NAL 类型（帧对齐切分标记；模块级：class 作用域不进方法）
            # 远程操作：Quartz CGEvent 模拟鼠标/滚轮（点击需辅助功能权限）。
            # 系统 python 默认无 pyobjc：安装脚本已 pip --user 补装；仍失败时
            # 操作功能降级（只看不控）。AXIsProcessTrusted 在 ApplicationServices
            # 框架（Quartz 模块里没有，实测 AttributeError）
            try:
                import Quartz
            except Exception:
                Quartz = None
            try:
                from ApplicationServices import AXIsProcessTrusted as _AX
                # 授权弹窗（无权限时主动引导用户勾选，业界同款）：
                # AXIsProcessTrustedWithOptions 带 prompt 触发系统 TCC 授权框
                from ApplicationServices import AXIsProcessTrustedWithOptions as _AXPrompt, kAXTrustedCheckOptionPrompt as _AXPromptKey
            except Exception:
                try:
                    from Quartz import AXIsProcessTrusted as _AX
                except Exception:
                    _AX = None
                _AXPrompt = None
                _AXPromptKey = None
            # 授权弹窗节流：首次触发后 30s 内不再弹（防高频控制包反复打扰）
            _LAST_PROMPT = [0.0]
            # 虚拟鼠标拖动开始前的实体指针位置；拖动结束后恢复，不干扰被控端操作者。
            _VIRTUAL_MOUSE_ORIGIN = [None]
            _VIRTUAL_MOUSE_HELD = [False]
            _VIRTUAL_MOUSE_OWNER = [None]
            _VIRTUAL_MOUSE_LAST = [(0.0, 0.0)]

            # ---- Linux 远程控制：X11 XTEST 注入（对应 macOS CGEvent）----
            # Xlib 仅 Linux 桌面可用。模块导入与 X display 建连分开：用户服务可能
            # 先于图形会话启动，旧实现第一次连接失败后会永久保持“不支持控制”。
            # 后续状态/控制请求会重试建连，桌面就绪后无需再次重装服务。
            try:
                from Xlib import X as _XLIB_X
                from Xlib import XK as _XLIB_XK
                from Xlib import display as _xdisplay
                from Xlib.ext import xtest as _xtest
                _XLIB_AVAILABLE = True
            except Exception:
                _XLIB_AVAILABLE = False
            _XDISPLAY = None

            def _ensure_xtest():
                global _XDISPLAY
                if sys.platform == "darwin" or not _XLIB_AVAILABLE:
                    return False
                if _XDISPLAY is None:
                    try:
                        _XDISPLAY = _xdisplay.Display()
                    except Exception:
                        _XDISPLAY = None
                return _XDISPLAY is not None

            # Carbon kVK（US 布局，客户端键码体系）→ X keysym 名。Linux 注入时
            # 反查 X keycode（XKB 布局相关，经 XKeysymToKeycode 换算）
            _KVK_TO_KEYSYM = {
                0: "a", 1: "s", 2: "d", 3: "f", 4: "h", 5: "g", 6: "z", 7: "x",
                8: "c", 9: "v", 11: "b", 12: "q", 13: "w", 14: "e", 15: "r",
                16: "y", 17: "t", 18: "1", 19: "2", 20: "3", 21: "4", 22: "6",
                23: "5", 24: "equal", 25: "9", 26: "7", 27: "minus", 28: "8",
                29: "0", 30: "bracketright", 31: "o", 32: "u", 33: "bracketleft",
                34: "i", 35: "p", 37: "l", 38: "j", 39: "apostrophe", 40: "k",
                41: "semicolon", 42: "backslash", 43: "comma", 44: "slash",
                45: "n", 46: "m", 47: "period", 50: "grave",
                36: "Return", 48: "Tab", 49: "space", 51: "BackSpace",
                53: "Escape", 115: "Home", 119: "End", 116: "Prior",
                121: "Next", 117: "Delete",
                123: "Left", 124: "Right", 125: "Down", 126: "Up",
                # 修饰键（Carbon kVK → X keysym）
                55: "Super_L", 56: "Shift_L", 59: "Control_L", 58: "Alt_L",
                # F1-F12（Carbon kVK 非连续）
                122: "F1", 120: "F2", 99: "F3", 118: "F4", 96: "F5", 97: "F6",
                98: "F7", 100: "F8", 101: "F9", 109: "F10", 103: "F11", 111: "F12",
            }

            def _kvk_to_keycode(kvk):
                if _XDISPLAY is None:
                    return None
                name = _KVK_TO_KEYSYM.get(kvk)
                if name is None:
                    return None
                try:
                    keysym = _XLIB_XK.string_to_keysym(name)
                    if keysym == 0:
                        return None
                    kc = _XDISPLAY.keysym_to_keycode(keysym)
                    return kc if kc else None
                except Exception:
                    return None

            def _x_inject_control(typ, x, y, extra, payload):
                # 与 macOS handle_control 相同的控制包语义，Linux 用 XTEST 注入
                if not _ensure_xtest():
                    return
                try:
                    W = _XDISPLAY.screen().width_in_pixels
                    H = _XDISPLAY.screen().height_in_pixels
                    px = int(x * W)
                    py = int(y * H)
                    if typ == 0:  # 移动
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                    elif typ == 1:  # 左键按下（先移动到手指位置再按——对应 macOS 用坐标）
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonPress, 1)
                    elif typ == 2:  # 左键释放
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonRelease, 1)
                    elif typ == 3:  # 滚轮：按钮 4（上）/ 5（下），extra 为滚动量
                        btn = 4 if extra > 0 else 5
                        for _ in range(min(abs(extra), 30)):
                            _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonPress, btn)
                            _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonRelease, btn)
                    elif typ == 6:  # 右键按下
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonPress, 3)
                    elif typ == 7:  # 右键释放
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonRelease, 3)
                    elif typ == 8 or typ == 9:  # 虚拟点击（原子：移动+按下+释放）
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                        btn = 1 if typ == 8 else 3
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonPress, btn)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonRelease, btn)
                    elif typ == 10:  # 虚拟左键拖动开始
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonPress, 1)
                    elif typ == 11:  # 虚拟左键拖动移动
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                    elif typ == 12:  # 虚拟左键拖动结束
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.MotionNotify, x=px, y=py)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonRelease, 1)
                    elif typ == 5:  # 键码：extra=kvK，x 的低位 = 修饰掩码
                        _x_post_key(extra, int(x))
                    elif typ == 4:  # 文本：剪贴板 + Ctrl+V（XTEST 发不了 Unicode）
                        _x_type_text(payload)
                    _XDISPLAY.sync()
                except Exception:
                    pass

            def _x_post_key(kvk, mods):
                kc = _kvk_to_keycode(kvk)
                if kc is None:
                    return
                # 修饰键（kvk, 掩码位）→ 组合键：修饰按下 → 主键 → 修饰抬起
                mod_entries = [(55, 1), (56, 2), (59, 4), (58, 8)]
                mod_kcs = [m for m in (_kvk_to_keycode(e[0]) for e in mod_entries if mods & e[1]) if m]
                for mk in mod_kcs:
                    _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyPress, mk)
                _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyPress, kc)
                _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyRelease, kc)
                for mk in reversed(mod_kcs):
                    _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyRelease, mk)

            def _x_type_text(payload):
                try:
                    chars = payload.decode("utf-8", "ignore")
                    if not chars:
                        return
                    # xclip 写剪贴板 → Ctrl+V 粘贴（中文/符号都能过，无需键码映射）
                    import subprocess as _sp
                    p = _sp.Popen(["xclip", "-selection", "clipboard"], stdin=_sp.PIPE)
                    p.communicate(chars.encode("utf-8"))
                    ctrl = _kvk_to_keycode(59)  # Control
                    v = _kvk_to_keycode(9)      # kVK 9 = V
                    if ctrl and v:
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyPress, ctrl)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyPress, v)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyRelease, v)
                        _xtest.fake_input(_XDISPLAY, _XLIB_X.KeyRelease, ctrl)
                except Exception:
                    pass


            def handle_control(data, ax_ok, owner=None):
                # 17B: magic4 + type1 + x f32(4) + y f32(4) + extra i32(4)
                if len(data) < 17 or data[:4] != CONTROL_MAGIC:
                    return
                typ = data[4]
                x = struct.unpack(">f", data[5:9])[0]
                y = struct.unpack(">f", data[9:13])[0]
                extra = struct.unpack(">i", data[13:17])[0]
                if typ == 10:
                    _VIRTUAL_MOUSE_HELD[0] = True
                    _VIRTUAL_MOUSE_OWNER[0] = owner
                    _VIRTUAL_MOUSE_LAST[0] = (x, y)
                elif typ == 11 and _VIRTUAL_MOUSE_HELD[0]:
                    _VIRTUAL_MOUSE_LAST[0] = (x, y)
                elif typ == 12:
                    _VIRTUAL_MOUSE_LAST[0] = (x, y)
                if sys.platform != "darwin":
                    # Linux：XTEST 注入（鼠标/滚轮/键码/剪贴板文本）
                    _x_inject_control(typ, x, y, extra, data[17:])
                    if typ == 12:
                        _VIRTUAL_MOUSE_HELD[0] = False
                        _VIRTUAL_MOUSE_OWNER[0] = None
                    return
                if Quartz is None or not ax_ok:
                    if typ == 10 or typ == 12:
                        _VIRTUAL_MOUSE_HELD[0] = False
                        _VIRTUAL_MOUSE_OWNER[0] = None
                    return  # 无权限：状态包由调用方发送，客户端引导授权
                try:
                    if typ == 4:
                        # 文本键（软键盘字符/中文 IME 上屏）：payload = UTF-8
                        chars = data[17:].decode("utf-8", "ignore")
                        if chars:
                            _post_key_unicode(chars)
                        return
                    if typ == 5:
                        # 键码键（工具栏）：extra = 虚拟键码，x = 修饰掩码位域
                        mods = int(x)
                        _post_key_combo(extra, mods)
                        return
                    # 坐标换算：归一化(0-1) × 主屏原生分辨率（视频等比缩放，
                    # 归一化坐标在任意推流分辨率下映射一致）
                    b = Quartz.CGDisplayBounds(Quartz.CGMainDisplayID())
                    px = int(x * b.size.width)
                    py = int(y * b.size.height)
                    src = Quartz.CGEventSourceCreate(Quartz.kCGEventSourceStateHIDSystemState)
                    if typ == 0:
                        ev = Quartz.CGEventCreateMouseEvent(src, Quartz.kCGEventMouseMoved, (px, py), Quartz.kCGMouseButtonLeft)
                    elif typ == 1:
                        ev = Quartz.CGEventCreateMouseEvent(src, Quartz.kCGEventLeftMouseDown, (px, py), Quartz.kCGMouseButtonLeft)
                    elif typ == 2:
                        ev = Quartz.CGEventCreateMouseEvent(src, Quartz.kCGEventLeftMouseUp, (px, py), Quartz.kCGMouseButtonLeft)
                    elif typ == 3:
                        ev = Quartz.CGEventCreateScrollWheelEvent(src, Quartz.kCGScrollEventUnitLine, 1, extra)
                        # 滚轮事件定位到虚拟箭头处，但不移动系统鼠标指针。
                        if ev is not None:
                            Quartz.CGEventSetLocation(ev, (px, py))
                    elif typ == 6:
                        ev = Quartz.CGEventCreateMouseEvent(src, Quartz.kCGEventRightMouseDown, (px, py), Quartz.kCGMouseButtonRight)
                    elif typ == 7:
                        ev = Quartz.CGEventCreateMouseEvent(src, Quartz.kCGEventRightMouseUp, (px, py), Quartz.kCGMouseButtonRight)
                    elif typ == 8 or typ == 9:
                        # 虚拟鼠标只负责精确点按，不能带着 Mac 的实体指针跑。
                        # 保存实体指针位置，投递一组原子点击后立刻恢复。
                        current_event = Quartz.CGEventCreate(None)
                        current = Quartz.CGEventGetLocation(current_event) if current_event is not None else None
                        button = Quartz.kCGMouseButtonLeft if typ == 8 else Quartz.kCGMouseButtonRight
                        down_type = Quartz.kCGEventLeftMouseDown if typ == 8 else Quartz.kCGEventRightMouseDown
                        up_type = Quartz.kCGEventLeftMouseUp if typ == 8 else Quartz.kCGEventRightMouseUp
                        down = Quartz.CGEventCreateMouseEvent(src, down_type, (px, py), button)
                        up = Quartz.CGEventCreateMouseEvent(src, up_type, (px, py), button)
                        if down is not None:
                            Quartz.CGEventPost(Quartz.kCGHIDEventTap, down)
                        if up is not None:
                            Quartz.CGEventPost(Quartz.kCGHIDEventTap, up)
                        if current is not None:
                            restore = Quartz.CGEventCreateMouseEvent(
                                src, Quartz.kCGEventMouseMoved, current, Quartz.kCGMouseButtonLeft
                            )
                            if restore is not None:
                                Quartz.CGEventPost(Quartz.kCGHIDEventTap, restore)
                        return
                    elif typ == 10:
                        # 长按开始：记住实体指针，移动虚拟光标并按下左键。
                        current_event = Quartz.CGEventCreate(None)
                        if _VIRTUAL_MOUSE_ORIGIN[0] is None and current_event is not None:
                            _VIRTUAL_MOUSE_ORIGIN[0] = Quartz.CGEventGetLocation(current_event)
                        ev = Quartz.CGEventCreateMouseEvent(
                            src, Quartz.kCGEventLeftMouseDown, (px, py), Quartz.kCGMouseButtonLeft
                        )
                    elif typ == 11:
                        # macOS 必须使用 LeftMouseDragged；MouseMoved 不会触发控件拖放。
                        ev = Quartz.CGEventCreateMouseEvent(
                            src, Quartz.kCGEventLeftMouseDragged, (px, py), Quartz.kCGMouseButtonLeft
                        )
                    elif typ == 12:
                        up = Quartz.CGEventCreateMouseEvent(
                            src, Quartz.kCGEventLeftMouseUp, (px, py), Quartz.kCGMouseButtonLeft
                        )
                        if up is not None:
                            Quartz.CGEventPost(Quartz.kCGHIDEventTap, up)
                        current = _VIRTUAL_MOUSE_ORIGIN[0]
                        _VIRTUAL_MOUSE_ORIGIN[0] = None
                        _VIRTUAL_MOUSE_HELD[0] = False
                        _VIRTUAL_MOUSE_OWNER[0] = None
                        if current is not None:
                            restore = Quartz.CGEventCreateMouseEvent(
                                src, Quartz.kCGEventMouseMoved, current, Quartz.kCGMouseButtonLeft
                            )
                            if restore is not None:
                                Quartz.CGEventPost(Quartz.kCGHIDEventTap, restore)
                        return
                    else:
                        return
                    if ev is not None:
                        Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev)
                except Exception as e:
                    try:
                        sys.stderr.write("control error: %s\n" % e)
                    except Exception:
                        pass

            def release_virtual_mouse(owner):
                # TCP 异常断开/被新连接替换时也必须补发 mouse-up，避免被控端左键卡住。
                if not _VIRTUAL_MOUSE_HELD[0] or _VIRTUAL_MOUSE_OWNER[0] is not owner:
                    return
                x, y = _VIRTUAL_MOUSE_LAST[0]
                try:
                    if sys.platform != "darwin":
                        if _ensure_xtest():
                            _xtest.fake_input(_XDISPLAY, _XLIB_X.ButtonRelease, 1)
                            _XDISPLAY.sync()
                    elif Quartz is not None:
                        b = Quartz.CGDisplayBounds(Quartz.CGMainDisplayID())
                        point = (int(x * b.size.width), int(y * b.size.height))
                        src = Quartz.CGEventSourceCreate(Quartz.kCGEventSourceStateHIDSystemState)
                        up = Quartz.CGEventCreateMouseEvent(
                            src, Quartz.kCGEventLeftMouseUp, point, Quartz.kCGMouseButtonLeft
                        )
                        if up is not None:
                            Quartz.CGEventPost(Quartz.kCGHIDEventTap, up)
                        current = _VIRTUAL_MOUSE_ORIGIN[0]
                        if current is not None:
                            restore = Quartz.CGEventCreateMouseEvent(
                                src, Quartz.kCGEventMouseMoved, current, Quartz.kCGMouseButtonLeft
                            )
                            if restore is not None:
                                Quartz.CGEventPost(Quartz.kCGHIDEventTap, restore)
                except Exception:
                    pass
                finally:
                    _VIRTUAL_MOUSE_ORIGIN[0] = None
                    _VIRTUAL_MOUSE_HELD[0] = False
                    _VIRTUAL_MOUSE_OWNER[0] = None

            # 修饰键 → 虚拟键码（Carbon kVK）与 CGEvent 标志。
            # ⚠️ 必须条件定义：Linux 上 Quartz import 失败（None），模块级
            # 直接取属性会 AttributeError 崩溃（用户反馈：Ubuntu 安装服务失败
            # ——relay 启动即崩）。Quartz=None 时留空，控制函数入口已降级返回
            MOD_KEYS = {} if Quartz is None else {
                1: (55, Quartz.kCGEventFlagMaskCommand),   # Command
                2: (56, Quartz.kCGEventFlagMaskShift),     # Shift
                4: (59, Quartz.kCGEventFlagMaskControl),   # Control
                8: (58, Quartz.kCGEventFlagMaskAlternate), # Option
            }
            KEY_SOURCE = None

            def _key_source():
                global KEY_SOURCE
                if KEY_SOURCE is None:
                    KEY_SOURCE = Quartz.CGEventSourceCreate(Quartz.kCGEventSourceStateHIDSystemState)
                return KEY_SOURCE

            def _post_key_code(keycode, down, mods):
                ev = Quartz.CGEventCreateKeyboardEvent(_key_source(), keycode, down)
                if ev is None:
                    return
                flags = 0
                for m in MOD_KEYS.values():
                    if mods & m[0]:
                        flags |= m[1]
                if flags:
                    Quartz.CGEventSetFlags(ev, flags)
                Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev)

            def _post_key_combo(keycode, mods):
                # 修饰键按下 → 主键按下/抬起 → 修饰键抬起（组合键语义，如 ⌘C）
                mod_codes = [MOD_KEYS[m][0] for m in (1, 2, 4, 8) if mods & m]
                for mc in mod_codes:
                    _post_key_code(mc, True, mods)
                _post_key_code(keycode, True, mods)
                _post_key_code(keycode, False, mods)
                for mc in reversed(mod_codes):
                    _post_key_code(mc, False, mods)

            def _post_key_unicode(chars):
                # Unicode 文本：按字符逐个发送（中文/符号走 CGEventKeyboardSetUnicodeString，
                # 不依赖键码映射）
                for ch in chars:
                    ev = Quartz.CGEventCreateKeyboardEvent(_key_source(), 0, True)
                    if ev is None:
                        continue
                    Quartz.CGEventKeyboardSetUnicodeString(ev, len(ch), ch)
                    Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev)
                    ev2 = Quartz.CGEventCreateKeyboardEvent(_key_source(), 0, False)
                    if ev2 is not None:
                        Quartz.CGEventPost(Quartz.kCGHIDEventTap, ev2)
            # macOS 才有 ~/Library/Logs；Linux 用 ~/.termish-screen.err——
            # 目录不存在时 open() 抛异常 → ffmpeg 不会被拉起（用户反馈：
            # Ubuntu 端口监听但推流 0 字节）
            ERRLOG = os.path.expanduser("~/Library/Logs/termish-screen.err" if sys.platform == "darwin" else "~/.termish-screen.err")
            FF_PIDFILE = os.path.expanduser("~/.termish-screen-ffmpeg.pid")
            IS_MAC = sys.platform == "darwin"

            def remove_owned_ffmpeg_pid(pid):
                # 只删除仍指向当前 Termish 子进程的 PID 文件；不能用 pkill -x
                # ffmpeg，它会误杀用户自己的转码/录制任务。
                try:
                    if int(open(FF_PIDFILE).read().strip()) == pid:
                        os.remove(FF_PIDFILE)
                except Exception:
                    pass

            def stop_orphaned_ffmpeg():
                try:
                    pid = int(open(FF_PIDFILE).read().strip())
                    comm = subprocess.run(
                        ["ps", "-p", str(pid), "-o", "comm="],
                        capture_output=True, text=True, timeout=2,
                    ).stdout.strip()
                    if os.path.basename(comm) == "ffmpeg":
                        os.kill(pid, signal.SIGKILL)
                except Exception:
                    pass
                finally:
                    try:
                        os.remove(FF_PIDFILE)
                    except Exception:
                        pass

            def read_stream_cfg():
                # 推流参数：~/.termish-screen.conf（手机端全屏切换写入，格式 fps=/scale=）
                # 缺省 30fps / 1280x720（scale 保持宽高比）
                cfg = {"fps": "30", "scale": "1280:-2"}
                try:
                    for line in open(os.path.expanduser("~/.termish-screen.conf")):
                        line = line.strip()
                        if "=" in line:
                            k, v = line.split("=", 1)
                            if k in cfg and v.strip():
                                cfg[k] = v.strip()
                except Exception:
                    pass
                return cfg

            def make_args(cfg):
                # fps 滤镜强制限帧：avfoundation 实际输出 ~120fps（ProMotion），
                # -framerate 无效——fps 滤镜才是实际限帧（120fps 会把解码器灌爆）
                try:
                    fps = max(1, min(120, int(cfg["fps"])))
                except (TypeError, ValueError):
                    fps = 30
                # 约 0.5s 一个关键帧；不再把 60/120fps 固定为 g=15，
                # 否则会变成 0.25/0.125s 并徒增带宽和解码压力。
                gop = max(15, fps // 2)
                scale = cfg["scale"]
                # 桌面内容以文字和细线为主；默认 bicubic 下采样偏软，Lanczos 在
                # 同分辨率下保留更多边缘细节。对高于源尺寸的档位也比普通插值锐利。
                vf = "fps=" + str(fps)
                if scale != "native":
                    vf += ",scale=" + scale + ":flags=lanczos"
                if IS_MAC:
                    # macOS：VideoToolbox 硬编（M 系列 Media Engine 专核，CPU 零负担）。
                    # 历史教训：libx264 软编在 4K/高帧率下 CPU 打满 → 编码端掉帧（卡）+
                    # 被迫降质量（糊）；Parsec/ToDesk/RustDesk 全走硬编。
                    # 基础码率按分辨率档位映射，再按帧率提高。此前 30/60/120fps
                    # 共用固定码率，超清 120fps 每帧预算只剩 30fps 的四分之一，
                    # 分辨率虽是 2560，文字仍会糊。
                    if scale == "native" or scale.startswith("2560"):
                        base_mbps = 24
                    elif scale.startswith("1920"):
                        base_mbps = 10
                    elif scale.startswith("1280"):
                        base_mbps = 6
                    else:
                        base_mbps = 4
                    if fps >= 120:
                        bitrate_mbps = base_mbps * 2
                    elif fps >= 60:
                        bitrate_mbps = base_mbps * 3 // 2
                    else:
                        bitrate_mbps = base_mbps
                    bitrate = str(bitrate_mbps) + "M"
                    return ["-f", "avfoundation", "-capture_cursor", "1",
                            "-pixel_format", "uyvy422", "-i", "1:none",
                            "-hide_banner", "-loglevel", "error", "-framerate", str(fps),
                            "-vf", vf,
                            "-c:v", "h264_videotoolbox",
                            # 实时编码提示（低延时）：编码器按实时语义工作，无缓冲积压
                            "-realtime", "1",
                            # CBR 码率：屏幕流静态场景码率自动收敛，运动场景不超标
                            "-b:v", bitrate,
                            # 约 0.5s 关键帧间隔：丢帧/起播恢复快——
                            # 流畅度优先，静态画面 GOP 缩短码率代价可忽略
                            "-g", str(gop),
                            "-pix_fmt", "yuv420p",
                            # VT 不自带 AUD：h264_metadata 位流过滤器逐帧插入
                            # （实测每帧恰 1 个 AUD），relay 按 AUD 切帧对齐语义不变
                            "-bsf:v", "dump_extra=freq=keyframe,h264_metadata=aud=insert",
                            "-f", "h264", "-"]
                # Linux X11：x11grab 抓屏（DISPLAY 由服务启动时注入，默认 :0）
                # Linux 软编按画质档设置 CRF。此前一直使用 libx264 默认 CRF 23，
                # 即使把尺寸升到 2560，终端字体和 UI 细线仍会被量化得发糊。
                if scale == "native" or scale.startswith("2560"):
                    crf = "16"
                elif scale.startswith("1920"):
                    crf = "18"
                elif scale.startswith("1280"):
                    crf = "20"
                else:
                    crf = "22"
                common = ["-hide_banner", "-loglevel", "error", "-framerate", str(fps),
                          "-vf", vf, "-c:v", "libx264", "-preset", "ultrafast",
                          "-crf", crf,
                          # 不用 -tune zerolatency（其 sliced-threads 切碎帧），显式等价参数
                          # keyint 随帧率变化：约 0.5s 关键帧间隔——解码器重同步/丢帧
                          # 恢复最多等 0.5s（流畅度优先）；aud=1：每帧前发 AUD NAL，
                          # relay 按它切帧对齐块
                          "-x264opts", "sliced-threads=0:rc-lookahead=0:sync-lookahead=0:keyint=%d:aud=1" % gop,
                          "-pix_fmt", "yuv420p", "-g", str(gop),
                          # 显式无 B 帧（ultrafast 默认即 0，写死保险：B 帧需等参考帧，
                          # 会引入编码端重排延迟）
                          "-bf", "0",
                          "-threads", "1",
                          # 裸 Annex-B（无 TS 容器）+ AUD 分帧：每个帧前有 AUD NAL，
                          # relay 按 AUD 切块 → 一个 UDP 重组块 = 一个完整帧
                          # （客户端 MediaCodec 直解，无 ExoPlayer/HTTP/容器层）
                          "-bsf:v", "dump_extra=freq=keyframe",
                          "-f", "h264", "-"]
                return ["-f", "x11grab", "-i", os.environ.get("DISPLAY", ":0") + ".0"] + common

            def make_fragments(payload, mtu, frag_id):
                # 分片格式与客户端（Kotlin Fragment.toBytes）一致：8B id 大端 + 2B (final<<15|num)
                usable = mtu - 10
                compressed = zlib.compress(payload)
                frags = []
                num = 0
                off = 0
                while off < len(compressed):
                    end = min(off + usable, len(compressed))
                    final = 1 if end == len(compressed) else 0
                    hdr = struct.pack(">QH", frag_id, (final << 15) | num)
                    frags.append(hdr + compressed[off:end])
                    off = end
                    num += 1
                return frags

            LOCK = threading.Lock()
            STREAM = [None]  # 当前视频流（单 ffmpeg，TCP/旧 UDP 客户端二选一）
            CLIENT = [None]  # 当前客户端地址（心跳更新，漫游时变）
            LAST_HB = [0.0]
            TCP_CLIENT = [None]  # TCP 视频通道客户端（手机出站连接，单客户端）

            def relay_log(message):
                try:
                    with open(ERRLOG, "a") as log:
                        log.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), message))
                except Exception:
                    pass

            def wake_linux_display():
                # Linux 的 x11grab 在 DPMS Off 时仍持续输出合法黑帧：TCP、解码器、
                # fps 全部正常，客户端无法据此区分“桌面全黑”和“显示器休眠”。
                # 远程画面连接建立及观看期间周期性唤醒当前 DISPLAY；xset 缺失、
                # Wayland/X 权限不足均静默降级。s reset 只重置空闲计时器，不修改
                # 用户的屏保或 DPMS 配置，连接断开后原电源策略自然恢复。
                if IS_MAC:
                    return
                display = os.environ.get("DISPLAY", ":0")
                try:
                    subprocess.run(
                        ["xset", "-display", display, "s", "reset"],
                        stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2,
                    )
                    state = subprocess.run(
                        ["xset", "-display", display, "q"],
                        capture_output=True, text=True, timeout=2,
                    ).stdout
                    if "Monitor is Off" in state:
                        subprocess.run(
                            ["xset", "-display", display, "dpms", "force", "on"],
                            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=2,
                        )
                        relay_log("woke sleeping Linux display %s" % display)
                except Exception:
                    pass

            class UdpStream:
                # ffmpeg 抓屏 + AUD 帧对齐。最终版视频走 SSH 内的 TCP 通道；保留
                # loopback UDP 参数仅用于兼容旧 relay 测试，不再暴露公网 UDP 端口。
                def __init__(self, udp, addr, tcp_conn=None):
                    self.udp = udp
                    self.addr = addr
                    self.tcp_conn = tcp_conn
                    self.stopped = False

                    # 发送速率上限（字节/秒，AIMD 自适应：心跳丢帧率反馈驱动，
                    # 初始 12MB/s 满速；丢帧>15% ×0.7、<5% ×1.15）
                    self.rate = 12_000_000
                    self.last_rate_adj = 0.0
                    self.errf = open(ERRLOG, "a")
                    wake_linux_display()
                    self.ff = subprocess.Popen([FF] + make_args(read_stream_cfg()), stdout=subprocess.PIPE, stderr=self.errf)
                    try:
                        with open(FF_PIDFILE, "w") as f:
                            f.write(str(self.ff.pid))
                    except Exception:
                        pass
                    self.frag_id = 0
                    self.started = time.time()
                    self.last_data = time.time()
                    self.sent = 0
                    self.stop_reason = None
                    self.last_client_heartbeat = time.time()
                    self.last_display_keepalive = 0.0

                def start(self):
                    threading.Thread(target=self.pump, daemon=True).start()

                def send_frame(self, frame):
                    # SSH direct-tcpip 视频格式：[4B 大端长度][帧字节]。
                    # 连接写失败必须终止 pump；继续读 ffmpeg 只会空转并让客户端
                    # 永远等不到 EOF 后的完整重连。
                    tcp_c = self.tcp_conn
                    if tcp_c is not None:
                        try:
                            tcp_c.sendall(struct.pack(">I", len(frame)) + frame)
                        except Exception:
                            try:
                                tcp_c.close()
                            except Exception:
                                pass
                            raise
                    if self.addr is None:
                        self.sent += len(frame)
                        self.last_data = time.time()
                        return
                    # 一个完整帧 → 一组分片（同一 frag_id）。发送节奏按【速率】控制
                    # （非每 8 片硬歇 1ms）：静止帧只有 1-2 片不歇，运动大帧按字节速率
                    # 限到 ~12MB/s——每 8 片歇 1ms 会把 600 片的 IDR 帧拖 75ms，
                    # 单线程 pump 读-切-发串行，帧延迟雪崩（真机反馈：滑动黑块 + 卡顿）
                    import time as _t
                    _start = _t.monotonic()
                    _budget = 8 * 1200 / float(self.rate)  # 8 片 ≈ 9.6KB 的最大耗时
                    for i, f in enumerate(make_fragments(frame, 1200, self.frag_id)):
                        self.frag_id += 1
                        self.udp.sendto(f, self.addr)
                        if i % 8 == 7:
                            _spent = _t.monotonic() - _start
                            _target = (i // 8) * _budget
                            if _spent < _target:
                                time.sleep(_target - _spent)
                    self.sent += len(frame)
                    self.last_data = time.time()

                def find_aud_starts(self, buf):
                    # 扫描 AUD NAL（start code 后首字节 & 0x1F == 9）的偏移
                    starts = []
                    i = 0
                    n = len(buf)
                    while i + 4 < n:
                        if buf[i] == 0 and buf[i+1] == 0 and buf[i+2] == 1 and (buf[i+3] & 0x1F) == AUD_TYPE:
                            starts.append(i)
                            i += 4
                        else:
                            i += 1
                    return starts

                def pump(self):
                    buf = b""
                    close_reason = "pump ended"
                    # ⚠️ 用 os.read（原始管道读：有多少读多少）——python file.read(65536)
                    # 是凑满语义，静止画面低码率时要攢 ~20s 才返回一次（64KB/25B每帧），
                    # 表现为画面冻结 + 突发倾泻（真机反馈：滑动黑块 + 卡顿）
                    self.ff_stdout_fd = self.ff.stdout.fileno()
                    try:
                        while not self.stopped:
                            r, _, _ = select.select([self.ff_stdout_fd], [], [], 1.0)
                            if not r:
                                # 自愈看门狗：ffmpeg 卡死无输出（首帧 45s / 中途 20s）
                                now = time.time()
                                if (self.sent == 0 and now - self.started > 45) or (self.sent > 0 and now - self.last_data > 20):
                                    if display_asleep():
                                        continue
                                    close_reason = "ffmpeg output watchdog"
                                    break
                                continue
                            data = os.read(self.ff_stdout_fd, 262144)
                            if not data:
                                close_reason = "ffmpeg stdout eof code=%s" % self.ff.poll()
                                break
                            buf += data
                            # 按 AUD 边界切帧：从第 2 个 AUD 起每个 AUD 开新帧
                            # （第 1 个 AUD 之前的字节属于“开流半帧”，丢弃）
                            starts = self.find_aud_starts(buf)
                            if len(starts) >= 2:
                                for j in range(len(starts) - 1):
                                    self.send_frame(buf[starts[j]:starts[j+1]])
                                buf = buf[starts[-1]:]
                            elif len(buf) > 4 * 1024 * 1024:
                                # 安全阀：长时间无 AUD（非 x264 源）直接整段发
                                self.send_frame(buf)
                                buf = b""
                            elif starts:
                                # 只有一个 AUD 且在缓冲中后段：丢弃 AUD 之前的半帧字节
                                buf = buf[starts[0]:]
                    except Exception as e:
                        close_reason = "pump error %s: %s" % (type(e).__name__, e)
                    finally:
                        reason = self.stop_reason or close_reason
                        self.errf.write("[%s] video stream closed after %.1fs sent=%d transport=%s reason=%s\n" % (
                            time.strftime("%H:%M:%S"), time.time() - self.started, self.sent,
                            "tcp" if self.tcp_conn is not None else "udp", reason))
                        self.errf.flush()
                        # 自愈：ffmpeg 退出/看门狗触发后清空当前 stream，让主循环在
                        # 下次心跳时重新拉 ffmpeg（否则手机持续心跳会不断刷新 LAST_HB，
                        # 60s 超时永不触发，视频流会永久卡死）
                        with LOCK:
                            if STREAM[0] is self:
                                STREAM[0] = None
                                CLIENT[0] = None
                                if TCP_CLIENT[0] is self.tcp_conn:
                                    TCP_CLIENT[0] = None
                        try:
                            if self.tcp_conn is not None:
                                # 控制线程仍可能阻塞在 recv()；Linux 上仅 close()
                                # 不保证唤醒另一个线程，SSH 通道就一直等不到 EOF。
                                self.tcp_conn.shutdown(socket.SHUT_RDWR)
                                self.tcp_conn.close()
                        except Exception:
                            pass
                        remove_owned_ffmpeg_pid(self.ff.pid)

                def stop(self, reason="requested"):
                    if self.stop_reason is None:
                        self.stop_reason = reason
                    self.stopped = True
                    try:
                        if self.tcp_conn is not None:
                            self.tcp_conn.shutdown(socket.SHUT_RDWR)
                            self.tcp_conn.close()
                    except Exception:
                        pass
                    try:
                        self.ff.kill()
                    except Exception:
                        pass
                    try:
                        self.ff.wait(timeout=5)
                    except Exception:
                        try:
                            os.kill(self.ff.pid, signal.SIGKILL)
                        except Exception:
                            pass

            def display_asleep():
                # 主显示器是否睡眠（CGDisplayIsAsleep）。息屏时 ffmpeg 无帧源属正常，
                # 看门狗靠它区分「真卡死」与「屏幕关了」
                try:
                    import ctypes
                    cg = ctypes.cdll.LoadLibrary("/System/Library/Frameworks/CoreGraphics.framework/CoreGraphics")
                    cg.CGMainDisplayID.restype = ctypes.c_uint32
                    cg.CGDisplayIsAsleep.argtypes = [ctypes.c_uint32]
                    cg.CGDisplayIsAsleep.restype = ctypes.c_ubyte
                    return bool(cg.CGDisplayIsAsleep(cg.CGMainDisplayID()))
                except Exception:
                    return False

            # 启动时只清理由上一轮 Termish relay 记录的孤儿 ffmpeg；PID +
            # 进程名双重确认，绝不影响用户自行启动的 ffmpeg。
            stop_orphaned_ffmpeg()

            # 旧 UDP 兼容口仅监听回环。最终版视频走 SSH direct-tcpip，禁止再把
            # 未鉴权的心跳/reload/控制端口暴露到公网。
            stop_legacy_relay_listener()

            def make_udp():
                s = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
                s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                s.bind(("127.0.0.1", UDP_PORT))
                return s

            udp = make_udp()

            # TCP：仅 bind+listen 用于读流脚本 lsof 探测 relay 存活，不服务视频。
            # 同时监听 IPv6 回环（::1）：SSH -L [::1]:PORT 转发同样可达
            tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            tcp.bind(("127.0.0.1", TCP_PORT))
            tcp.listen(4)
            try:
                tcp6 = socket.socket(socket.AF_INET6, socket.SOCK_STREAM)
                tcp6.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                tcp6.bind(("::1", TCP_PORT))
                tcp6.listen(4)
            except OSError:
                pass

            def recv_exact(conn, count):
                out = bytearray()
                while len(out) < count:
                    chunk = conn.recv(count - len(out))
                    if not chunk:
                        raise EOFError("tcp client closed")
                    out.extend(chunk)
                return bytes(out)

            def tcp_control_loop(conn, stream):
                # 客户端 → relay：[4B 大端长度][THC1 控制包]。TCP 没有消息边界，
                # 必须显式分帧；特别是 UTF-8 文本包长度可变，不能按 recv() 猜边界。
                try:
                    while True:
                        size = struct.unpack(">I", recv_exact(conn, 4))[0]
                        if size < 17 or size > 64 * 1024:
                            raise ValueError("bad control size %d" % size)
                        data = recv_exact(conn, size)
                        if not data.startswith(CONTROL_MAGIC):
                            continue
                        # 客户端每 2 秒续租；普通控制事件也证明 owner 仍存活。
                        # 心跳只用于所有权，不进入辅助功能权限探测和输入注入。
                        stream.last_client_heartbeat = time.time()
                        if data[4] == HEARTBEAT_TYPE:
                            now = time.time()
                            if now - stream.last_display_keepalive >= 15.0:
                                stream.last_display_keepalive = now
                                wake_linux_display()
                            continue
                        ax_ok = False
                        if _AX is not None:
                            try:
                                ax_ok = bool(_AX())
                            except Exception:
                                ax_ok = False
                        if not ax_ok and _AXPrompt is not None:
                            try:
                                if time.time() - _LAST_PROMPT[0] > 30:
                                    _LAST_PROMPT[0] = time.time()
                                    _AXPrompt({_AXPromptKey: True})
                            except Exception:
                                pass
                        handle_control(data, ax_ok, conn)
                except Exception as e:
                    relay_log("tcp control closed: %s: %s" % (type(e).__name__, e))
                finally:
                    release_virtual_mouse(conn)
                    owned = False
                    with LOCK:
                        if TCP_CLIENT[0] is conn and STREAM[0] is stream:
                            TCP_CLIENT[0] = None
                            STREAM[0] = None
                            owned = True
                    if owned:
                        stream.stop("tcp control closed")

            # TCP 视频服务只监听远端回环；手机通过已认证 SSH direct-tcpip 访问。
            # App 在重建会话前会先关闭旧通道。远端再拒绝重复的已认证连接，形成
            # 第二道代次保护：迟到的旧启动协程/重复点击不能抢占健康画面。
            def tcp_serve():
                try:
                    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                    srv.bind(("127.0.0.1", TCP_VIDEO_PORT))
                    srv.listen(2)
                except OSError:
                    return
                while True:
                    conn = None
                    stream = None
                    try:
                        conn, peer = srv.accept()
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        conn.setsockopt(socket.SOL_SOCKET, socket.SO_SNDBUF, 256 * 1024)
                        # 回环地址不是用户隔离边界：同机其它 OS 用户也能连接固定端口。
                        # direct-tcpip 建连后首包必须携带仅当前远端账号可读的 token；
                        # 认证前不替换旧客户端、不启动 ffmpeg，也不返回任何画面数据。
                        conn.settimeout(5.0)
                        auth_size = struct.unpack(">I", recv_exact(conn, 4))[0]
                        if auth_size != 4 + len(AUTH_TOKEN):
                            raise PermissionError("bad auth size")
                        auth = recv_exact(conn, auth_size)
                        if not auth.startswith(AUTH_MAGIC) or not hmac.compare_digest(auth[4:], AUTH_TOKEN):
                            raise PermissionError("screen auth failed")
                        conn.settimeout(None)
                        # 认证与占位必须在同一临界区。健康 owner（2s 心跳，6s
                        # 租约）拒绝迟到的幽灵连接；App 已死但 SSH/TCP 仍假活时，
                        # 新连接可回收过期 owner，避免永久“连接中/反复重连”。
                        old_stream = None
                        busy = False
                        replace_reason = "tcp replaced legacy udp"
                        with LOCK:
                            if TCP_CLIENT[0] is not None:
                                active_stream = STREAM[0]
                                if active_stream is None or time.time() - active_stream.last_client_heartbeat <= TCP_OWNER_LEASE_SECONDS:
                                    busy = True
                                else:
                                    old_stream = active_stream
                                    replace_reason = "stale tcp lease replaced"
                            elif STREAM[0] is not None:
                                old_stream = STREAM[0]
                            if not busy:
                                STREAM[0] = None
                                TCP_CLIENT[0] = conn
                        if busy:
                            # 3=已有另一台设备占用。显式返回状态，让客户端展示
                            # 准确提示并停止自动重连，而不是把正常互斥伪装成 EOF。
                            conn.sendall(STATUS_MAGIC + bytes([3]))
                            relay_log("tcp busy peer=%s:%s" % peer)
                            conn.close()
                            conn = None
                            continue
                        if old_stream is not None:
                            old_stream.stop(replace_reason)
                        relay_log("tcp accepted peer=%s:%s" % peer)
                        # 首包 = 控制状态：
                        # 0=OK，1=macOS 缺辅助功能权限，2=平台不支持控制，3=被占用
                        if Quartz is None:
                            # Linux：XTEST 可用则支持控制（0），否则平台不支持（2）
                            ctrl_status = 0 if _ensure_xtest() else 2
                        else:
                            try:
                                ctrl_status = 0 if (_AX is not None and bool(_AX())) else 1
                            except Exception:
                                ctrl_status = 1
                        conn.sendall(STATUS_MAGIC + bytes([ctrl_status]))
                        stream = UdpStream(udp, None, conn)
                        with LOCK:
                            if TCP_CLIENT[0] is conn:
                                STREAM[0] = stream
                        stream.start()
                        threading.Thread(target=tcp_control_loop, args=(conn, stream), daemon=True).start()
                    except Exception as e:
                        owned = False
                        with LOCK:
                            if TCP_CLIENT[0] is conn:
                                TCP_CLIENT[0] = None
                                if STREAM[0] is stream:
                                    STREAM[0] = None
                                owned = True
                        if owned and stream is not None:
                            stream.stop("tcp setup failed")
                        try:
                            if conn is not None:
                                conn.close()
                        except Exception:
                            pass
                        try:
                            relay_log("tcp rejected/failed: %s: %s" % (type(e).__name__, e))
                        except Exception:
                            pass

            threading.Thread(target=tcp_serve, daemon=True).start()

            while True:
                r, _, _ = select.select([udp], [], [], 1.0)
                now = time.time()
                if r:
                    try:
                        data, addr = udp.recvfrom(65535)
                    except Exception:
                        continue
                    # 旧 UDP 兼容口同样要求 token，避免从遗留入口绕过 TCP 握手。
                    if len(data) <= len(AUTH_TOKEN) or not hmac.compare_digest(data[:len(AUTH_TOKEN)], AUTH_TOKEN):
                        continue
                    data = data[len(AUTH_TOKEN):]
                    # v35 起 TCP 与旧 UDP 兼容入口严格互斥。此前 UDP 心跳会在
                    # TCP 建连的短窗口创建孤儿 ffmpeg，reload 甚至能直接停止
                    # 健康 TCP，表现为恢复后每 3~6 秒再次断开。
                    with LOCK:
                        if TCP_CLIENT[0] is not None:
                            continue
                    if data.startswith(RELOAD_MAGIC):
                        # 重载（新会话首包）：重启 ffmpeg 重读推流参数——画质/帧率
                        # 切换的生效路径。漫游语义下旧 ffmpeg 永不重启，conf 写了
                        # 也读不到（UDP 版每次连接不再自动新 ffmpeg）。
                        # ⚠️ 不在 LOCK 内做阻塞 stop：stop() 里 ff.wait(5) 会与
                        # pump 线程 finally 的 LOCK 清理互等（实测卡 5-10s、
                        # reload 后拉不起流）——先锁内取旧流引用，锁外停
                        with LOCK:
                            if TCP_CLIENT[0] is not None:
                                continue
                            old = STREAM[0]
                            STREAM[0] = None
                            CLIENT[0] = addr
                        if old is not None:
                            old.stop()
                        s = UdpStream(udp, addr)
                        s.start()
                        udp_owned = False
                        with LOCK:
                            if TCP_CLIENT[0] is None and STREAM[0] is None:
                                STREAM[0] = s
                                udp_owned = True
                            LAST_HB[0] = now
                        if not udp_owned:
                            s.stop("udp lost ownership")
                    elif data.startswith(CONTROL_MAGIC):
                        # 远程操作控制包（触摸→鼠标/滚轮）：解析 + CGEvent 模拟。
                        # 回状态包（5B，权限状态）：客户端据此提示引导授权；
                        # 控制包同时证明客户端活着，刷新心跳/地址（漫游同语义）
                        ax_ok = False
                        if _AX is not None:
                            try:
                                ax_ok = bool(_AX())
                            except Exception:
                                ax_ok = False
                        if not ax_ok and _AXPrompt is not None:
                            # 主动弹系统授权框：用户勾选「python」后点击立即生效
                            try:
                                if time.time() - _LAST_PROMPT[0] > 30:
                                    _LAST_PROMPT[0] = time.time()
                                    _AXPrompt({_AXPromptKey: True})
                            except Exception:
                                pass
                        handle_control(data, ax_ok)
                        try:
                            # 控制状态：0=OK，1=macOS 缺辅助功能权限，2=平台不支持控制
                            #（Linux 走 XTEST：可用则 0，不可用则 2）
                            if Quartz is None:
                                ctrl_status = 0 if _ensure_xtest() else 2
                            else:
                                ctrl_status = 0 if ax_ok else 1
                            udp.sendto(STATUS_MAGIC + bytes([ctrl_status]), addr)
                        except Exception:
                            pass
                        with LOCK:
                            if TCP_CLIENT[0] is not None:
                                continue
                            if CLIENT[0] != addr:
                                CLIENT[0] = addr
                                if STREAM[0] is not None:
                                    STREAM[0].addr = addr
                            LAST_HB[0] = time.time()
                    elif data.startswith(HEARTBEAT_MAGIC):
                        # 丢帧反馈（可选第 5 字节）：丢帧率%驱动 AIMD 速率自适应——
                        # >15% 降速一档（×0.7）、<5% 且未满速则升一档，每秒至多一步。
                        # 打破「高码率洪流打满 WiFi 接收缓冲→持续丢帧」恶性循环
                        #（实测 120fps@1920 运动时丢 30-47%/秒）
                        loss_pct = data[4] if len(data) > 4 else 0
                        with LOCK:
                            if TCP_CLIENT[0] is not None:
                                continue
                            if STREAM[0] is None:
                                CLIENT[0] = addr
                                s = UdpStream(udp, addr)
                                s.start()
                                STREAM[0] = s
                            elif CLIENT[0] != addr:
                                # 漫游：地址变化，更新目标地址（ffmpeg 不重启，画面连续）
                                CLIENT[0] = addr
                                STREAM[0].addr = addr
                            if STREAM[0] is not None:
                                now2 = time.time()
                                if now2 - getattr(STREAM[0], 'last_rate_adj', 0) >= 1.0:
                                    STREAM[0].last_rate_adj = now2
                                    if loss_pct > 15:
                                        STREAM[0].rate = max(2_000_000, int(STREAM[0].rate * 0.7))
                                        STREAM[0].errf.write("[RATE] loss=%d%% -> %d B/s\n" % (loss_pct, STREAM[0].rate))
                                    elif loss_pct < 5 and STREAM[0].rate < 12_000_000:
                                        STREAM[0].rate = min(12_000_000, int(STREAM[0].rate * 1.15))
                            LAST_HB[0] = now
                # 旧 UDP 心跳超时才停流；TCP 连接由 send/recv EOF 驱动生命周期，
                # 不能套用 LAST_HB（否则 TCP 建立后因 LAST_HB=0 被立即误杀）。
                expired = None
                with LOCK:
                    if (STREAM[0] is not None and STREAM[0].tcp_conn is None
                            and now - LAST_HB[0] > 60):
                        expired = STREAM[0]
                        STREAM[0] = None
                        CLIENT[0] = None
                if expired is not None:
                    expired.stop("udp heartbeat timeout")
            TERMISH_EOF
            # ---- 服务启动：macOS 用 LaunchAgent（GUI 域录屏权限）；
            # Linux 用 systemd 用户服务（桌面自启动兜底），机器重启后随图形登录
            # 自动恢复。旧版仅 nohup，进程在本次 SSH 断开后能活、重启后必丢。----
            if [ "${'$'}OS" = "Darwin" ]; then
            mkdir -p "${'$'}HOME/Library/LaunchAgents"
            RELAY="${'$'}RELAY" cat > "${'$'}PLIST" <<TERMISH_EOF
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
              <key>Label</key><string>dev.termish.screen</string>
              <key>ProgramArguments</key>
              <array>
                <string>/usr/bin/python3</string>
                <string>${'$'}RELAY</string>
              </array>
              <key>RunAtLoad</key><true/>
              <key>KeepAlive</key><true/>
            </dict>
            </plist>
            TERMISH_EOF
            launchctl bootout gui/${'$'}(id -u) "${'$'}PLIST" 2>/dev/null || true
            sleep 1
            # relay 启动时会按自身 PID 文件清理上一轮孤儿 ffmpeg；这里不能
            # pkill 全部 ffmpeg，否则会误杀用户自己的转码/录制任务。
            launchctl bootstrap gui/${'$'}(id -u) "${'$'}PLIST"
            sleep 1
            # 验证用 launchctl（不碰连接：探测连接-断开会打断 relay 的当前服务周期；
            # 也不用 pgrep：安装脚本自身的 zsh 命令行含脚本文本会误匹配）
            if launchctl print gui/${'$'}(id -u)/dev.termish.screen 2>/dev/null | grep -q "state = running" \
              && lsof -nP -iTCP:${'$'}((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1; then
              echo $RELAY_VERSION > "${'$'}HOME/.termish-screen.version"
              echo "==> TERMISH_SCREEN_OK"
            else
              echo "==> 服务未启动（检查 ~/Library/Logs/termish-screen.err）" >&2
              exit 1
            fi
            else
            # Linux：重启 relay，并注册用户级持久服务。
            # ⚠️ 不用 pkill -f screen-relay.py：安装脚本自身（sh -c）命令行含
            # 脚本文本，-f 全匹配会把自己杀掉（macOS 分支同款坑，用户反馈：
            # Ubuntu 引导安装失败）——用 PID 文件精确清理
            RELAY_PID="${'$'}HOME/.termish-screen.pid"
            stop_termish_relay_pid() {
              OLD_PID="${'$'}1"
              # 安装脚本启用了 set -e；PID 文件失效是正常升级场景，函数必须
              # 显式成功返回，否则会在写入新 relay 后、启动它之前提前退出。
              case "${'$'}OLD_PID" in ''|*[!0-9]*) return 0 ;; esac
              [ -r "/proc/${'$'}OLD_PID/cmdline" ] || return 0
              OLD_CMD="${'$'}(tr '\0' ' ' < "/proc/${'$'}OLD_PID/cmdline" 2>/dev/null)"
              case "${'$'}OLD_CMD" in
                *"${'$'}RELAY"*) kill "${'$'}OLD_PID" 2>/dev/null || true ;;
              esac
              return 0
            }
            if [ -f "${'$'}RELAY_PID" ]; then
              stop_termish_relay_pid "${'$'}(cat "${'$'}RELAY_PID" 2>/dev/null)"
              rm -f "${'$'}RELAY_PID"
            fi
            # v31 及更早版本可能没有可靠 PID 文件，或失败的新进程覆盖过 PID。
            # 从监听端口找到旧进程后仍逐项核对 cmdline 必须包含当前 relay 绝对路径，
            # 只关闭 Termish 自己的服务，不使用会误杀安装脚本/其它 Python 的 pkill。
            LISTENER_PID=""
            if command -v lsof >/dev/null 2>&1; then
              LISTENER_PID="${'$'}(lsof -t -iTCP:${'$'}PORT -sTCP:LISTEN 2>/dev/null | head -1)"
            elif command -v fuser >/dev/null 2>&1; then
              LISTENER_PID="${'$'}(fuser -n tcp "${'$'}PORT" 2>/dev/null | awk '{print ${'$'}1}')"
            fi
            stop_termish_relay_pid "${'$'}LISTENER_PID"
            sleep 0.5
            # 启动包装器每次运行都重新探测 DISPLAY/XAUTHORITY。Xwayland 的 display
            # 号和授权文件会在重启后变化，不能把安装当时的值固化进 service unit。
            RUNNER="${'$'}HOME/.termish-screen-launch.sh"
            LOG="${'$'}HOME/.termish-screen.log"
            cat > "${'$'}RUNNER" <<'TERMISH_EOF'
            #!/bin/sh
            RELAY="${'$'}HOME/Library/Application Support/termish/screen-relay.py"
            LOG="${'$'}HOME/.termish-screen.log"
            while :; do
              XDISP=":0"
              XPID=""
              GRAPHICAL=0
              if command -v pgrep >/dev/null 2>&1; then
                # 只选择当前用户的图形服务器；GDM greeter 的 Xwayland 属于
                # gdm-greeter，连接它会因授权隔离而被误判为“不支持控制”。
                XPID="${'$'}(pgrep -u "${'$'}(id -u)" -x Xwayland 2>/dev/null | head -1)"
                if [ -z "${'$'}XPID" ]; then
                  XPID="${'$'}(pgrep -u "${'$'}(id -u)" -x Xorg 2>/dev/null | head -1)"
                fi
              fi
              if [ -n "${'$'}XPID" ] && [ -r "/proc/${'$'}XPID/cmdline" ]; then
                GRAPHICAL=1
                DETECTED_DISPLAY="${'$'}(tr '\0' '\n' < "/proc/${'$'}XPID/cmdline" | grep -E '^:[0-9]+${'$'}' | head -1)"
                [ -n "${'$'}DETECTED_DISPLAY" ] && XDISP="${'$'}DETECTED_DISPLAY"
              elif command -v loginctl >/dev/null 2>&1; then
                for sid in ${'$'}(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="${'$'}(id -u)" '${'$'}2 == uid { print ${'$'}1 }'); do
                  SESSION_TYPE="${'$'}(loginctl show-session "${'$'}sid" -p Type --value 2>/dev/null)"
                  SESSION_REMOTE="${'$'}(loginctl show-session "${'$'}sid" -p Remote --value 2>/dev/null)"
                  case "${'$'}SESSION_TYPE:${'$'}SESSION_REMOTE" in
                    x11:no)
                      GRAPHICAL=1
                      SESSION_DISPLAY="${'$'}(loginctl show-session "${'$'}sid" -p Display --value 2>/dev/null)"
                      [ -n "${'$'}SESSION_DISPLAY" ] && XDISP="${'$'}SESSION_DISPLAY"
                      break
                      ;;
                  esac
                done
              fi
              XNUM="${'$'}{XDISP#:}"
              if [ "${'$'}GRAPHICAL" = "1" ] && [ -S "/tmp/.X11-unix/X${'$'}XNUM" ]; then
                export DISPLAY="${'$'}XDISP"
                XAUTH=""
                if [ -n "${'$'}XPID" ] && [ -r "/proc/${'$'}XPID/cmdline" ]; then
                  XAUTH="${'$'}(tr '\0' '\n' < "/proc/${'$'}XPID/cmdline" | awk 'take { print; exit } ${'$'}0 == "-auth" { take=1 }')"
                fi
                if [ -z "${'$'}XAUTH" ] && [ -f "/run/user/${'$'}(id -u)/gdm/Xauthority" ]; then
                  XAUTH="/run/user/${'$'}(id -u)/gdm/Xauthority"
                fi
                if [ -z "${'$'}XAUTH" ] && [ -f "${'$'}HOME/.Xauthority" ]; then
                  XAUTH="${'$'}HOME/.Xauthority"
                fi
                if [ -n "${'$'}XAUTH" ] && [ -r "${'$'}XAUTH" ]; then
                  export XAUTHORITY="${'$'}XAUTH"
                fi
                exec /usr/bin/python3 "${'$'}RELAY" >> "${'$'}LOG" 2>&1
              fi
              sleep 2
            done
            TERMISH_EOF
            chmod 700 "${'$'}RUNNER"

            SERVICE_STARTED=0
            USER_RUNTIME="/run/user/${'$'}(id -u)"
            UNIT_DIR="${'$'}HOME/.config/systemd/user"
            UNIT="${'$'}UNIT_DIR/dev.termish.screen.service"
            if command -v systemctl >/dev/null 2>&1 && [ -S "${'$'}USER_RUNTIME/bus" ]; then
              mkdir -p "${'$'}UNIT_DIR"
              cat > "${'$'}UNIT" <<TERMISH_EOF
            [Unit]
            Description=Termish screen relay
            After=graphical-session.target

            [Service]
            Type=simple
            ExecStart=%h/.termish-screen-launch.sh
            Restart=on-failure
            RestartSec=2

            [Install]
            WantedBy=default.target
            TERMISH_EOF
              export XDG_RUNTIME_DIR="${'$'}USER_RUNTIME"
              export DBUS_SESSION_BUS_ADDRESS="unix:path=${'$'}USER_RUNTIME/bus"
              systemctl --user daemon-reload
              # enable --now 不会重启已运行的旧 unit；升级 relay 时必须显式 restart，
              # 否则端口仍由被 systemd 拉回的旧 Python 进程占用。
              if systemctl --user enable dev.termish.screen.service \
                && systemctl --user restart dev.termish.screen.service; then
                SERVICE_STARTED=1
                rm -f "${'$'}HOME/.config/autostart/dev.termish.screen.desktop"
                echo "==> systemd 用户服务：已启用（重启后自动恢复）"
              fi
            fi
            if [ "${'$'}SERVICE_STARTED" != "1" ]; then
              # 没有可用的 systemd user bus（部分精简桌面）时，通过 XDG autostart
              # 保证下次图形登录自动启动；当前安装仍用 nohup 立即拉起。
              mkdir -p "${'$'}HOME/.config/autostart"
              cat > "${'$'}HOME/.config/autostart/dev.termish.screen.desktop" <<'TERMISH_EOF'
            [Desktop Entry]
            Type=Application
            Name=Termish Screen Relay
            Exec=/bin/sh -c "${'$'}HOME/.termish-screen-launch.sh"
            X-GNOME-Autostart-enabled=true
            NoDisplay=true
            TERMISH_EOF
              nohup "${'$'}RUNNER" >/dev/null 2>&1 &
              echo ${'$'}! > "${'$'}RELAY_PID"
              echo "==> 桌面自启动服务：已启用（重启后自动恢复）"
            fi
            sleep 1.5
            # 验证控制面与回环视频端口都在监听；不建立探测连接（会触发 ffmpeg）。
            if (lsof -nP -iTCP:${'$'}PORT -sTCP:LISTEN >/dev/null 2>&1 || ss -ltn 2>/dev/null | grep -q ":${'$'}PORT ") \
              && (lsof -nP -iTCP:${'$'}((PORT + 2)) -sTCP:LISTEN >/dev/null 2>&1 || ss -ltn 2>/dev/null | grep -q ":${'$'}((PORT + 2)) "); then
              echo $RELAY_VERSION > "${'$'}HOME/.termish-screen.version"
              echo "==> TERMISH_SCREEN_OK"
            else
              echo "==> 服务未启动（检查 ${'$'}LOG）" >&2
              exit 1
            fi
            fi
            """.trimIndent()

        /**
         * 从读流脚本 stdout 解析远端推流参数（SCREEN_CFG_FPS / SCREEN_CFG_SCALE 行；
         * 缺项为 null——conf 缺失时不覆盖客户端本地默认档位）。
         */
        internal fun parseStreamCfg(stdout: String): Pair<Int?, String?> {
            var fps: Int? = null
            var scale: String? = null
            stdout.lineSequence().forEach { line ->
                when {
                    line.startsWith("SCREEN_CFG_FPS:") ->
                        line
                            .substringAfter(":")
                            .trim()
                            .toIntOrNull()
                            ?.let { fps = it }

                    line.startsWith("SCREEN_CFG_SCALE:") ->
                        line
                            .substringAfter(":")
                            .trim()
                            .takeIf { it.isNotEmpty() }
                            ?.let { scale = it }
                }
            }
            return Pair(fps, scale)
        }

        /** 从已认证 SSH 探测输出提取 relay bearer token；格式异常一律拒绝。 */
        internal fun parseAuthToken(stdout: String): String? =
            stdout
                .lineSequence()
                .firstOrNull { it.startsWith("SCREEN_AUTH_TOKEN:") }
                ?.substringAfter(":")
                ?.trim()
                ?.takeIf { token -> token.length == 64 && token.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' } }

        /** scale 字符串 → 画质档位 index（流畅/标清/高清/原画；未知按标清）。 */
        internal fun qualityIndexFor(scale: String): Int =
            when (scale) {
                "960:-2" -> 0
                "1920:-2" -> 2
                "native", "2560:-2" -> 3
                else -> 1
            }

        internal fun scaleForQuality(index: Int): String =
            when (index) {
                0 -> "960:-2"
                2 -> "1920:-2"
                3 -> "native"
                else -> "1280:-2"
            }

        /**
         * 测试用推流脚本：lavfi 测试图源（不依赖屏幕录制权限）。
         * 集成测试用它回归「exec raw 通道 + stderr 协程 + NAL 解析」链路。
         */
        val LAVFI_SCRIPT =
            """
            FF=${'$'}(command -v ffmpeg 2>/dev/null || echo "${'$'}HOME/bin/ffmpeg")
            if [ ! -x "${'$'}FF" ]; then echo "FFMPEG_MISSING" >&2; exit 1; fi
            exec "${'$'}FF" -hide_banner -loglevel error -f lavfi -i testsrc=size=640x360:rate=30 \
              -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 15 \
              -x264opts keyint=15:aud=1 -f h264 -flush_packets 1 -
            """.trimIndent()
    }
}
