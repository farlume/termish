package dev.termish.screen

import dev.termish.generated.resources.Res
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshExecChannel
import dev.termish.ssh.SshSession
import dev.termish.ssh.createSftpSession
import dev.termish.ssh.createSshSession
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlin.random.Random
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import org.jetbrains.compose.resources.ExperimentalResourceApi

/** ScreenSession 只持有结构化行为；所有用户可见文案由 AppStrings 在创建时注入。 */
internal data class ScreenSessionMessages(
    val connectionFailed: String,
    val readChannelFailed: String,
    val tcpPortMissing: String,
    val tcpChannelFailed: (Int) -> String,
    val tcpDisconnected: String,
    val screenInUse: String,
    val screenRecordingPermissionMissing: String,
    val ffmpegMissing: String,
    val unsupportedOs: (String) -> String,
    val relayUpgradeRequired: String,
    val displayMissing: String,
    val serviceNotRunning: String,
    val waylandHint: String,
    val waylandDependenciesMissing: String,
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
 *   Linux 自动探测硬件编码，失败时回退 libx264）作为
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
    /** 用户从权限错误主动重连；不用于断流自动重连。 */
    private val refreshPermissionsBeforeStart: Boolean = false,
    /** 非主动关闭的断流回调（EOF/异常）：AppRoot 借此自动重连（用户反馈：
     * relay 重启/会话切换导致「画面流已断开」需手动重连）。 */
    private val onStreamLost: (() -> Unit)? = null,
    /** 播放器持续过载时请求 AppRoot 重写配置并重建会话。 */
    private val onAdaptiveFpsRequested: ((Int) -> Unit)? = null,
) {
    private var ssh: SshSession? = null
    private var player: ScreenPlayer? = null

    @Volatile
    private var running = false
    private var installing = false

    /** 首帧超时（连接建立后无帧到达视为推流异常，给可见提示）。 */
    private var firstFrameDeadline = 0L
    private var firstFrameError: String? = null
    private var waylandPortalExpected = false

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
                if (refreshPermissionsBeforeStart) {
                    // CoreGraphics 可保留授权前的结果。只在用户主动重连时刷新
                    // 自己的 GUI LaunchAgent，不修改系统授权或重签名应用。
                    val refreshed =
                        withContext(ioDispatcher()) {
                            session.runCommandDetailed(REFRESH_PERMISSIONS_SCRIPT, 10_000)
                        }
                    if (!running) {
                        session.close()
                        return@launch
                    }
                    if (refreshed?.stdout?.lineSequence()?.any { it == "SCREEN_PERMISSION_REFRESH_OK" } != true) {
                        uiState.error = messages.serviceNotRunning
                        running = false
                        session.close()
                        return@launch
                    }
                    TermLog.i("screen") { "permission refresh completed ${connection.host}" }
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
                cfgFps?.let {
                    uiState.streamFps = it
                    if (uiState.preferredStreamFps == 0) uiState.preferredStreamFps = it
                }
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
                        targetFps = uiState.streamFps,
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
                // Wayland 首次连接必须由被控端用户确认系统 RemoteDesktop 门户；
                // 给足时间完成一次性授权，之后同一桌面会话复用权限并恢复 12s。
                val firstFrameTimeout =
                    if (waylandPortalExpected) SCREEN_WAYLAND_FIRST_VIDEO_TIMEOUT_MS else SCREEN_FIRST_VIDEO_TIMEOUT_MS
                firstFrameDeadline = Clock.System.now().toEpochMilliseconds() + firstFrameTimeout
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
                        firstVideoTimeoutMillis = firstFrameTimeout,
                        onVideoPacket = { packet -> p.feed(packet.data) },
                        playerMetrics = p::metrics,
                        onStatus = { status ->
                            // 首包与后续权限更新：0=OK，1=macOS 缺辅助功能权限，2=不支持控制，
                            // 3=占用，4=缺录屏权限；明确拒绝时不进入自动重连。
                            if (screenStatusRejectsConnection(status)) {
                                uiState.recordingPermissionMissing = status == SCREEN_TCP_STATUS_CAPTURE_PERMISSION_MISSING
                                running = false
                                firstFrameDeadline = 0
                                TermLog.i("screen") { "screen connection rejected status=$status" }
                                scope.launch {
                                    runCatching { p.stop() }
                                    if (player === p) player = null
                                    uiState.player = null
                                    uiState.controlSender = null
                                    uiState.keySender = null
                                    uiState.connected = false
                                    uiState.videoReady = false
                                    uiState.controlPermissionMissing = false
                                    uiState.error =
                                        if (status == SCREEN_TCP_STATUS_CAPTURE_PERMISSION_MISSING) {
                                            messages.screenRecordingPermissionMissing
                                        } else {
                                            messages.screenInUse
                                        }
                                }
                                scope.launch(ioDispatcher()) { session.close() }
                                false
                            } else {
                                uiState.recordingPermissionMissing = false
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
                    val adaptiveController = ScreenAdaptiveFpsController()
                    var previousTransport = tcp.metrics()
                    var lastMetricsLogAt = Clock.System.now().toEpochMilliseconds()
                    var previousUiTransport = previousTransport
                    var previousUiPlayer = p.metrics()
                    var previousUiMetricsAt = lastMetricsLogAt
                    var adaptiveRequestPending = false
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
                        if (uiState.videoReady && p.renderSurfaceAttached) {
                            val playerMetrics = p.metrics()
                            val transport = tcp.metrics()
                            val uiElapsed = (now - previousUiMetricsAt).coerceAtLeast(1)
                            val uiReceived = (transport.receivedFrames - previousUiTransport.receivedFrames).coerceAtLeast(0)
                            val uiBytes = (transport.receivedBytes - previousUiTransport.receivedBytes).coerceAtLeast(0)
                            val uiNetworkLost = (transport.lostFrames - previousUiTransport.lostFrames).coerceAtLeast(0)
                            val uiPlayerDropped =
                                (playerMetrics.droppedFrames - previousUiPlayer.droppedFrames).coerceAtLeast(0)
                            val uiTotal = uiReceived + uiNetworkLost
                            uiState.fps = (uiReceived * 1_000 / uiElapsed).toInt()
                            uiState.bitrateKbps = (uiBytes * 8 / uiElapsed).toInt()
                            uiState.jitterMillis = transport.jitterMillis
                            uiState.droppedPermille =
                                if (uiTotal > 0) {
                                    ((uiNetworkLost + uiPlayerDropped) * 1_000 / uiTotal).toInt().coerceIn(0, 1_000)
                                } else {
                                    0
                                }
                            previousUiTransport = transport
                            previousUiPlayer = playerMetrics
                            previousUiMetricsAt = now
                            if (now - lastMetricsLogAt >= 5_000) {
                                val bytes = (transport.receivedBytes - previousTransport.receivedBytes).coerceAtLeast(0)
                                val frames = (transport.receivedFrames - previousTransport.receivedFrames).coerceAtLeast(0)
                                val elapsed = (now - lastMetricsLogAt).coerceAtLeast(1)
                                val deliveredFps = frames * 1_000 / elapsed
                                val bitrateKbps = bytes * 8 / elapsed
                                TermLog.i("screen") {
                                    "stream metrics: fps=$deliveredFps bitrate=${bitrateKbps}kbps " +
                                        "received=${playerMetrics.receivedFrames} " +
                                        "rendered=${playerMetrics.renderedFrames} " +
                                        "dropped=${playerMetrics.droppedFrames} " +
                                        "busy=${playerMetrics.decoderBusyFrames} queue=${playerMetrics.queueDepth}"
                                }
                                previousTransport = transport
                                lastMetricsLogAt = now
                            }
                            val requestedFps =
                                adaptiveController.evaluate(
                                    metrics = playerMetrics,
                                    currentFps = uiState.streamFps,
                                    preferredFps =
                                        uiState.preferredStreamFps.takeIf { it > 0 }
                                            ?: uiState.streamFps,
                                    decoderMaxFps = uiState.decoderMaxFps,
                                    nowMillis = now,
                                    lastChangeAtMillis = uiState.adaptiveFpsChangedAtMillis,
                                )
                            if (!adaptiveRequestPending && requestedFps != null && requestedFps != uiState.streamFps) {
                                adaptiveRequestPending = true
                                val oldFps = uiState.streamFps
                                uiState.streamFps = requestedFps
                                uiState.adaptiveFpsChangedAtMillis = now
                                TermLog.w("screen") {
                                    "adaptive fps: $oldFps->$requestedFps " +
                                        "preferred=${uiState.preferredStreamFps} " +
                                        "decoderMax=${uiState.decoderMaxFps}"
                                }
                                onAdaptiveFpsRequested?.invoke(requestedFps)
                            }
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
        if (err.contains("SCREEN_WAYLAND_DEPS_MISSING")) {
            uiState.serviceMissing = true
            uiState.error = messages.waylandDependenciesMissing
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
            waylandPortalExpected = true
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
    @OptIn(ExperimentalResourceApi::class)
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
            var stagedBinary: String? = null
            try {
                // 与 Mosh 安装一致：Linux 缺 ffmpeg 时先判断权限三态。
                // 仅确实需要交互式 sudo 的场景显示密码框；密码不写进命令行。
                val os = withContext(ioDispatcher()) { s.runCommand("uname -s", 3_000)?.trim() }
                val arch = withContext(ioDispatcher()) { s.runCommand("uname -m", 3_000)?.trim() }
                val sessionType =
                    if (os == "Linux") {
                        withContext(ioDispatcher()) { s.runCommand(SESSION_TYPE_PROBE_SCRIPT, 5_000)?.trim() }
                    } else {
                        null
                    }
                val native = ScreenServiceAssets.binaryFor(os, arch)
                if (native == null) {
                    onLog(messages.unsupportedOs("$os/$arch"))
                    onComplete(false)
                    return@launch
                }
                val ffmpegPresent =
                    if (os == "Linux") {
                        withContext(ioDispatcher()) {
                            s.runCommand(FFMPEG_PROBE_SCRIPT, 3_000)?.contains("FFMPEG_OK") == true
                        }
                    } else {
                        true
                    }
                val xlibPresent =
                    if (os == "Linux" && sessionType != "wayland") {
                        withContext(ioDispatcher()) {
                            s.runCommand(XCLIP_PROBE_SCRIPT, 3_000)?.contains("XLIB_OK") == true
                        }
                    } else {
                        true
                    }
                val waylandDependenciesPresent =
                    if (os == "Linux" && sessionType == "wayland") {
                        withContext(ioDispatcher()) {
                            s.runCommand(WAYLAND_PROBE_SCRIPT, 5_000)?.contains("WAYLAND_OK") == true
                        }
                    } else {
                        true
                    }
                val isRoot =
                    os == "Linux" &&
                        withContext(ioDispatcher()) { s.runCommand("id -u", 3_000)?.trim() == "0" }
                val desktopManagerPresent =
                    os != "Linux" ||
                        withContext(ioDispatcher()) {
                            s.runCommand("command -v zenity", 3_000)?.isNotBlank() == true
                        }
                val hasSudo =
                    os == "Linux" &&
                        !isRoot &&
                        withContext(ioDispatcher()) {
                            s.runCommand("command -v sudo", 3_000)?.isNotBlank() == true
                        }
                val sudoPasswordless =
                    if (os == "Linux" &&
                        (!ffmpegPresent || !xlibPresent || !waylandDependenciesPresent || !desktopManagerPresent) &&
                        !isRoot &&
                        hasSudo
                    ) {
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
                        waylandDependenciesPresent = waylandDependenciesPresent,
                        desktopManagerPresent = desktopManagerPresent,
                        isRoot = isRoot,
                        hasSudo = hasSudo,
                        sudoPasswordless = sudoPasswordless,
                    )
                if (sudoNeedsPassword && sudoPassword.isNullOrBlank()) {
                    TermLog.i("screen") { "Linux screen dependencies require sudo password" }
                    installing = false
                    uiState.installing = false
                    uiState.needsSudoPassword = true
                    return@launch
                }

                val log = StringBuilder()
                run {
                    val bytes = Res.readBytes("files/termish-screen/${native.filename}")
                    check(bytes.size == native.size) { "Invalid bundled screen service size" }
                    val sftp = withContext(ioDispatcher()) { createSftpSession(connection, callbacks) }
                    try {
                        withContext(ioDispatcher()) {
                            val directory = "${sftp.home()}/Library/Application Support/termish"
                            val mkdir = s.runCommandDetailed("umask 077; mkdir -p ${screenShellQuote(directory)}", 10_000)
                            check(mkdir?.exitCode == 0) { "Unable to create screen service directory" }
                            val stage = "$directory/screen-service.upload-${Random.nextLong().toULong().toString(16)}"
                            stagedBinary = stage
                            var offset = 0
                            sftp.upload(stage, bytes.size.toLong()) {
                                if (offset == bytes.size) {
                                    null
                                } else {
                                    val end = (offset + 64 * 1024).coerceAtMost(bytes.size)
                                    bytes.copyOfRange(offset, end).also { offset = end }
                                }
                            }
                        }
                    } finally {
                        withContext(ioDispatcher() + NonCancellable) { sftp.close() }
                    }
                }
                val nativeEnvironment =
                    "TERMISH_NATIVE_STAGE=${screenShellQuote(checkNotNull(stagedBinary))}\n" +
                        "TERMISH_NATIVE_SHA256=${screenShellQuote(native.sha256)}\n" +
                        "TERMISH_SESSION_TYPE=${screenShellQuote(sessionType.orEmpty())}\n"
                val command =
                    nativeEnvironment +
                        (if (sudoNeedsPassword) "TERMISH_SUDO_STDIN=1\n" else "") +
                        INSTALL_SCRIPT
                val ch = withContext(ioDispatcher()) { s.startExecRaw(screenInstallCommand(command)) }
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

                    // 远端合并 stdout/stderr，读到 EOF 后日志完整，避免提前关闭
                    // 通道丢失最后的错误输出；StringBuilder 也只由一个读取者写入。
                    withContext(ioDispatcher()) {
                        while (true) {
                            val data = ch.read() ?: break
                            log.append(data.decodeToString().replace("\r", ""))
                            onLog(visibleLog())
                        }
                        ch.close()
                    }
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
            } finally {
                stagedBinary?.let { path ->
                    withContext(ioDispatcher() + NonCancellable) {
                        runCatching { s.runCommand("rm -f ${screenShellQuote(path)}", 5_000) }
                    }
                }
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
        const val SCREEN_PORT = ScreenServiceAssets.SCREEN_PORT

        /** relay 内部 TCP 视频端口（仅监听远端回环，由 SSH direct-tcpip 访问）。 */
        const val SCREEN_TCP_PORT = SCREEN_PORT + 2

        /** 刷新 macOS 进程内权限缓存；Linux 不需要重启。 */
        internal val REFRESH_PERMISSIONS_SCRIPT =
            """
            if [ "${'$'}(uname)" = "Darwin" ]; then
              launchctl kickstart -k "gui/${'$'}(id -u)/dev.termish.screen" || exit 1
              for attempt in 1 2 3 4 5 6 7 8 9 10; do
                if lsof -nP -iTCP:$SCREEN_PORT -sTCP:LISTEN >/dev/null 2>&1 \
                  && lsof -nP -iTCP:$SCREEN_TCP_PORT -sTCP:LISTEN >/dev/null 2>&1; then
                  echo SCREEN_PERMISSION_REFRESH_OK
                  exit 0
                fi
                sleep 0.5
              done
              exit 1
            fi
            echo SCREEN_PERMISSION_REFRESH_OK
            """.trimIndent()

        /** 安装前探测 ffmpeg；与读流/安装脚本使用同一组非交互 SSH PATH 兜底。 */
        internal val FFMPEG_PROBE_SCRIPT =
            """
            for cand in ${'$'}(command -v ffmpeg 2>/dev/null) "${'$'}HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
              if [ -n "${'$'}cand" ] && [ -x "${'$'}cand" ]; then echo FFMPEG_OK; exit 0; fi
            done
            """.trimIndent()

        /** Linux 远程控制依赖探测；缺失时与 ffmpeg 共用一次 sudo 授权安装。 */
        internal const val XCLIP_PROBE_SCRIPT = "command -v xclip >/dev/null 2>&1 && echo XLIB_OK"

        internal val SESSION_TYPE_PROBE_SCRIPT =
            """
            if command -v loginctl >/dev/null 2>&1; then
              for sid in ${'$'}(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="${'$'}(id -u)" '${'$'}2 == uid { print ${'$'}1 }'); do
                TYPE="${'$'}(loginctl show-session "${'$'}sid" -p Type --value 2>/dev/null)"
                REMOTE="${'$'}(loginctl show-session "${'$'}sid" -p Remote --value 2>/dev/null)"
                case "${'$'}TYPE:${'$'}REMOTE" in
                  wayland:no|x11:no) echo "${'$'}TYPE"; exit 0 ;;
                esac
              done
            fi
            if pgrep -u "${'$'}(id -u)" -x Xorg >/dev/null 2>&1 || pgrep -u "${'$'}(id -u)" -x X >/dev/null 2>&1; then echo x11; fi
            """.trimIndent()

        /** Wayland 捕获使用系统 RemoteDesktop 门户 + PipeWire，经 GStreamer 转为 Y4M。 */
        internal val WAYLAND_PROBE_SCRIPT =
            """
            command -v gst-launch-1.0 >/dev/null 2>&1 \
              && gst-inspect-1.0 pipewiresrc >/dev/null 2>&1 \
              && gst-inspect-1.0 y4menc >/dev/null 2>&1 \
              && echo WAYLAND_OK
            """.trimIndent()

        /** Linux 缺依赖时是否需要弹出 sudo 密码输入；纯逻辑供各平台一致回归。 */
        internal fun needsScreenSudoPassword(
            os: String?,
            ffmpegPresent: Boolean,
            xlibPresent: Boolean = true,
            waylandDependenciesPresent: Boolean = true,
            desktopManagerPresent: Boolean = true,
            isRoot: Boolean,
            hasSudo: Boolean,
            sudoPasswordless: Boolean,
        ): Boolean =
            os == "Linux" &&
                (!ffmpegPresent || !xlibPresent || !waylandDependenciesPresent || !desktopManagerPresent) &&
                !isRoot &&
                hasSudo &&
                !sudoPasswordless

        /**
         * relay 协议版本：客户端内置安装脚本部署的 relay 与远端已运行 relay
         * 的匹配标识。更新 relay 行为（推流参数/自愈逻辑）时 +1——
         * 读流脚本检测远端版本文件，不匹配时引导重新安装（用户反馈：
         * 客户端脚本应与远端脚本版本匹配，否则旧 relay 跑不起新功能）。
         */
        const val RELAY_VERSION = ScreenServiceAssets.RELAY_VERSION

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
                # 优先以 logind 的本地用户会话类型为准。Wayland 通常同时运行
                # rootless Xwayland，不能仅凭 Xwayland 进程误判为 X11（那样
                # x11grab 只能得到黑色根窗口和鼠标）。
                # 不能把 GDM greeter 自己的 Xwayland 当成当前 SSH 用户桌面：
                # 机器刚重启、用户尚未图形登录时它通常属于 gdm-greeter，当前
                # 用户既读不到 Xauthority，也无法注入控制事件。
                HAS_USER_DISPLAY=0
                USER_SESSION_TYPE=""
                if command -v loginctl >/dev/null 2>&1; then
                  for sid in ${'$'}(loginctl list-sessions --no-legend 2>/dev/null | awk -v uid="${'$'}(id -u)" '${'$'}2 == uid { print ${'$'}1 }'); do
                    SESSION_TYPE="${'$'}(loginctl show-session "${'$'}sid" -p Type --value 2>/dev/null)"
                    SESSION_REMOTE="${'$'}(loginctl show-session "${'$'}sid" -p Remote --value 2>/dev/null)"
                    case "${'$'}SESSION_TYPE:${'$'}SESSION_REMOTE" in
                      wayland:no|x11:no)
                        HAS_USER_DISPLAY=1
                        USER_SESSION_TYPE="${'$'}SESSION_TYPE"
                        break
                        ;;
                    esac
                  done
                fi
                if [ "${'$'}HAS_USER_DISPLAY" != "1" ] && { pgrep -u "${'$'}(id -u)" -x Xorg >/dev/null 2>&1 \
                  || pgrep -u "${'$'}(id -u)" -x X >/dev/null 2>&1; }; then
                  HAS_USER_DISPLAY=1
                  USER_SESSION_TYPE="x11"
                fi
                if [ "${'$'}HAS_USER_DISPLAY" != "1" ]; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
                fi
                if [ "${'$'}USER_SESSION_TYPE" = "wayland" ]; then
                  echo "SCREEN_WAYLAND_ONLY" >&2
                  if ! command -v gst-launch-1.0 >/dev/null 2>&1 \
                    || ! gst-inspect-1.0 pipewiresrc >/dev/null 2>&1 \
                    || ! gst-inspect-1.0 y4menc >/dev/null 2>&1; then
                    echo "SCREEN_WAYLAND_DEPS_MISSING" >&2
                    exit 1
                  fi
                elif ! ls /tmp/.X11-unix/X* >/dev/null 2>&1; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
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
            # 屏幕状态探测仅提示，不阻断推流；Linux 同样查询当前用户图形会话。
            if [ "${'$'}OS" = "Darwin" ]; then
              NATIVE="${'$'}HOME/Library/Application Support/termish/Termish Helper.app/Contents/MacOS/Termish Helper"
            else
              NATIVE="${'$'}HOME/Library/Application Support/termish/screen-service"
            fi
            if [ -x "${'$'}NATIVE" ] && [ "${'$'}(cat "${'$'}HOME/Library/Application Support/termish/screen-service.backend" 2>/dev/null)" = "rust" ]; then
              "${'$'}NATIVE" --display-state 2>&1 | grep -E 'SCREEN_(ASLEEP|LOCKED)' >&2 || true
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
         * 到 ~/bin）→ 校验手机上传的 Rust 可执行文件
         * → 注册图形会话服务 → 验证端口监听。
         * macOS 图形登录会话与屏幕录制/辅助功能授权均需满足。
         */
        val INSTALL_SCRIPT: String
            get() = ScreenServiceAssets.INSTALL_SCRIPT

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

internal fun screenShellQuote(value: String): String = "'" + value.replace("'", "'\"'\"'") + "'"

// SSH exec 默认使用账号登录 shell；zsh 的未匹配 glob 会中断 POSIX 安装脚本。
// 显式选择 sh，保留 stdin 给 sudo，并将错误输出与进度放进同一个流。
internal fun screenInstallCommand(script: String): String = "exec /bin/sh -c ${screenShellQuote(script)} 2>&1"
