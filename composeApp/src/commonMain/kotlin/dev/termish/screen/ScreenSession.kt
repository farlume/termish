package dev.termish.screen

import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
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

/**
 * 屏幕推流会话：独立 SSH 连接 + 无 pty exec 通道读远端推流服务，
 * H.264 Annex-B 流逐 NAL 喂硬件解码器，帧回调更新 [ScreenUiState.frame]。
 *
 * 架构（macOS 屏幕录制权限的硬约束决定）：
 * - macOS 的 TCC 只对 GUI 登录会话放行屏幕捕获，SSH/mosh 后台会话无论给
 *   sshd/ffmpeg 授权都无法抓屏（实测：挂起/黑帧/退出）。
 * - 因此推流进程（ffmpeg avfoundation 抓屏 → libx264 → H.264）作为
 *   LaunchAgent 跑在用户 GUI 域（launchctl bootstrap gui/$(id -u)），常驻
 *   监听 127.0.0.1:17321；手机侧 SSH 只做传输（nc 读流），无需录屏权限。
 * - 服务缺失时远端上报 SCREEN_SERVICE_MISSING → uiState.serviceMissing，
 *   UI 引导一键安装（[installService]，与 herdr 安装引导同模式）。
 */
class ScreenSession(
    private val connection: SshConnection,
    private val callbacks: SshCallbacks,
    private val scope: CoroutineScope,
    private val uiState: ScreenUiState,
    /** 非主动关闭的断流回调（EOF/异常）：AppRoot 借此自动重连（用户反馈：
     * relay 重启/会话切换导致「画面流已断开」需手动重连）。 */
    private val onStreamLost: (() -> Unit)? = null,
) {
    private var ssh: SshSession? = null
    private var player: ScreenPlayer? = null
    private var running = false
    private var installing = false

    /** 远端 stderr 最近内容（ffmpeg 报错透传；断流时拼进错误信息供诊断）。 */
    @Volatile
    private var lastStderr = ""

    /** 首帧超时（连接建立后无帧到达视为推流异常，给可见提示）。 */
    private var firstFrameDeadline = 0L

    /** UDP 漫游会话（视频流 + 心跳；断网不显示断开，恢复续传）。 */
    private var udpSession: ScreenStreamUdpSession? = null

    fun start() {
        if (running) return
        running = true
        TermLog.i("screen") { "start ${connection.host}:${connection.port}" }
        scope.launch {
            try {
                val session = withContext(ioDispatcher()) { createSshSession(connection, callbacks) }
                ssh = session
                // 先建立连接 + 认证（否则 startExecRaw 无可用连接直接失败）
                val connected = withContext(ioDispatcher()) { session.connectAuthOnly() }
                TermLog.i("screen") { "connectAuthOnly=${connected != null}" }
                if (connected == null) {
                    uiState.error = "连接失败"
                    running = false
                    return@launch
                }
                // 读流脚本一次性执行：探测 relay 存活 + 版本/ffmpeg/屏幕状态，
                // 并输出 UDP 端口——视频流走 UDP 漫游，不再经 SSH 通道读流
                // （同一连接 runCommand 后不再 startExecRaw，避开 sshj 第二个
                // exec 通道立即 EOF 的坑）
                val result =
                    withContext(ioDispatcher()) {
                        session.runCommandDetailed(READ_STREAM_SCRIPT, 15_000)
                    }
                if (result == null) {
                    uiState.error = "无法启动远端读流通道"
                    running = false
                    return@launch
                }
                // 处理远端探测标记（版本过旧/缺 ffmpeg/无显示/服务未运行/屏幕状态）
                if (!handleReadStreamStderr(result.stderr)) {
                    running = false
                    return@launch
                }
                val udpPort =
                    result.stdout
                        .lineSequence()
                        .firstOrNull { it.startsWith("SCREEN_UDP_PORT:") }
                        ?.substringAfter(":")
                        ?.trim()
                        ?.toIntOrNull()
                // 远端推流参数回读：同步 UI 档位（relay 每连接读 conf，重装 App/
                // 多端写入后远端值可能与本机默认不同——否则 UI 显示 30 实推 120）
                val (cfgFps, cfgScale) = parseStreamCfg(result.stdout)
                cfgFps?.let { uiState.streamFps = it }
                cfgScale?.let { uiState.streamQuality = qualityIndexFor(it) }
                if (udpPort == null) {
                    uiState.error = "无法获取远端 UDP 端口"
                    running = false
                    return@launch
                }
                // 播放器接管解码/渲染（ExoPlayer 本地 HTTP 流）；首帧回调清超时
                val p =
                    ScreenPlayer(
                        onReady = {
                            firstFrameDeadline = 0
                            scope.launch {
                                uiState.videoReady = true
                                // 画面到达：清除超时与息屏/锁屏提示（可恢复状态）
                                if (uiState.error == FIRST_FRAME_TIMEOUT_MSG) uiState.error = null
                                uiState.screenHint = null
                            }
                        },
                        onError = { msg -> scope.launch { uiState.error = msg } },
                    )
                player = p
                uiState.player = p
                p.start()
                uiState.connected = true
                firstFrameDeadline = Clock.System.now().toEpochMilliseconds() + 12_000
                // 首帧超时监控：连接建立但迟迟无帧 → 提示（避免无限黑屏）。
                // 首帧到达后 onReady 把 deadline 清零，本监控自然退出
                scope.launch {
                    while (running && firstFrameDeadline > 0) {
                        if (Clock.System.now().toEpochMilliseconds() > firstFrameDeadline) {
                            if (running && !uiState.videoReady && uiState.error == null) {
                                uiState.error = FIRST_FRAME_TIMEOUT_MSG
                            }
                            break
                        }
                        delay(500)
                    }
                }
                // UDP 漫游会话：视频包喂播放器。断网不显示断开（漫游语义），
                // 链路健康度只做弱网提示，不触发断流重连
                val udp =
                    ScreenStreamUdpSession(
                        ip = connection.host,
                        port = udpPort,
                        scope = scope,
                        onVideoPacket = { data -> p.feed(data) },
                        onLinkStatus = { lostSecs ->
                            if (lostSecs > 0) {
                                if (uiState.screenHint == null) {
                                    uiState.screenHint = "网络不稳定（已断 ${lostSecs}s，恢复后自动续传）"
                                }
                            } else if (uiState.screenHint?.startsWith("网络不稳定") == true) {
                                uiState.screenHint = null
                            }
                        },
                    )
                udpSession = udp
                udp.start()
                TermLog.i("screen") { "UDP 会话已启动 port=$udpPort" }
            } catch (e: Exception) {
                // message 可能为 null（如 NetworkOnMainThreadException），必须记全类名 + 堆栈
                TermLog.w("screen") { "screen session error: ${e::class.qualifiedName}: ${e.message}" }
                TermLog.w("screen") { e.stackTraceToString().take(1500) }
                if (running) {
                    uiState.error = e.message ?: "连接失败"
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
            uiState.error = "远端未安装 ffmpeg"
            return false
        }
        if (err.contains("SCREEN_UNSUPPORTED_OS")) {
            val os =
                err
                    .substringAfter("SCREEN_UNSUPPORTED_OS:")
                    .lineSequence()
                    .first()
                    .trim()
            uiState.error = "屏幕推流仅支持 macOS / Linux 桌面主机（当前远端为 $os）"
            return false
        }
        if (err.contains("SCREEN_RELAY_OLD")) {
            uiState.serviceMissing = true
            uiState.relayNeedsUpgrade = true
            uiState.error = "推流服务需要升级（远端 relay 版本过旧），点安装更新"
            return false
        }
        if (err.contains("SCREEN_NO_DISPLAY")) {
            uiState.error = "屏幕推流需要图形会话（当前远端未检测到 X11 桌面显示）"
            return false
        }
        if (err.contains("SCREEN_SERVICE_MISSING") ||
            err.contains("Connection refused", ignoreCase = true)
        ) {
            uiState.serviceMissing = true
            uiState.error = "远端推流服务未运行"
            return false
        }
        // 提示类（不阻断推流）
        if (err.contains("SCREEN_WAYLAND_ONLY")) {
            uiState.screenHint = "Wayland 桌面：画面仅覆盖 X11 应用窗口（建议改用 Xorg 会话）"
        }
        if (err.contains("SCREEN_ASLEEP")) {
            uiState.screenHint = "Mac 屏幕已关闭，唤醒后画面自动恢复"
        } else if (err.contains("SCREEN_LOCKED")) {
            uiState.screenHint = "Mac 处于锁屏状态，画面为锁屏界面（解锁后恢复桌面）"
        }
        return true
    }

    /**
     * 一键安装远端推流服务（幂等）：检测/安装 ffmpeg → 写 LaunchAgent plist
     * → bootstrap 到用户 GUI 域并验证端口监听。与 herdr 安装引导同模式：
     * 流式输出进 [onLog]（UI 实时展示），完成后回调 [onComplete]。
     */
    fun installService(
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
                val log = StringBuilder()
                val ch = withContext(ioDispatcher()) { s.startExecRaw(INSTALL_SCRIPT) }
                if (ch != null) {
                    // stdout = 安装进度；stderr 错误合并进日志
                    val errJob =
                        scope.launch {
                            while (true) {
                                val err = withContext(ioDispatcher()) { ch.readErr() } ?: break
                                log.append(err.decodeToString().replace("\r", ""))
                                onLog(log.toString().takeLast(4096))
                            }
                        }
                    withContext(ioDispatcher()) {
                        while (true) {
                            val data = ch.read() ?: break
                            log.append(data.decodeToString().replace("\r", ""))
                            onLog(log.toString().takeLast(4096))
                        }
                        ch.close()
                    }
                    errJob.cancel()
                }
                installing = false
                uiState.installing = false
                // 脚本 set -e 失败时通道 EOF 但退出码拿不到：以脚本的成功标记
                // TERMISH_SCREEN_OK 判定，避免装失败也触发重连白转圈
                onComplete(log.contains("TERMISH_SCREEN_OK"))
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
        udpSession?.close()
        udpSession = null
        try {
            player?.stop()
        } catch (_: Exception) {
        }
        player = null
        try {
            ssh?.close()
        } catch (_: Exception) {
        }
        ssh = null
    }

    companion object {
        /** 首帧超时提示文案（帧到达时清除，见 [onFrame]）。 */
        const val FIRST_FRAME_TIMEOUT_MSG = "画面数据未到达（检查远端推流服务 / ffmpeg）"

        /** 推流服务 TCP 端口（LaunchAgent 常驻；读流脚本 lsof 探测存活）。 */
        const val SCREEN_PORT = 17321

        /** 视频流 UDP 端口（relay 分片发送 + 手机心跳，漫游续传）。 */
        const val SCREEN_UDP_PORT = 17322

        /**
         * relay 协议版本：客户端内置安装脚本部署的 relay 与远端已运行 relay
         * 的匹配标识。更新 relay 行为（推流参数/自愈逻辑）时 +1——
         * 读流脚本检测远端版本文件，不匹配时引导重新安装（用户反馈：
         * 客户端脚本应与远端脚本版本匹配，否则旧 relay 跑不起新功能）。
         */
        const val RELAY_VERSION = 7

        /**
         * 读流脚本：检查推流服务（lsof 探测，不产生连接）→ 缺失报 SCREEN_SERVICE_MISSING
         * （UI 转引导安装）；在则 nc 读流到 stdout。
         *
         * 注意：探测必须放脚本内（不能用 sshj 的 runCommand 预探测）——同一连接上
         * 先 runCommand 再 startExecRaw 时，第二个 exec 通道会立即 EOF（sshj 坑，实测）。
         * < /dev/null：忽略 stdin——exec 通道 stdin 保持打开时 nc 会阻塞在 stdin 读
         * 而不读 socket（实测：sshj 通道下 0 字节立即 EOF）。
         */
        val READ_STREAM_SCRIPT =
            """
            PORT=$SCREEN_PORT
            OS=${'$'}(uname)
            # relay 版本匹配：客户端 RELAY_VERSION=7，远端版本文件缺失/不一致
            # → 旧 relay（不支持新协议）→ 引导重新安装（用户反馈：客户端脚本
            # 应与远端脚本版本匹配）
            if [ ! -f "${'$'}HOME/.termish-screen.version" ] || [ "${'$'}(cat "${'$'}HOME/.termish-screen.version" 2>/dev/null)" != "7" ]; then
              echo "SCREEN_RELAY_OLD:have=${'$'}(cat "${'$'}HOME/.termish-screen.version" 2>/dev/null || echo none) expect=7" >&2
              exit 1
            fi
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
                # 纯 Wayland 只能抓 X11 应用窗口，标记提示不阻断（用户反馈：
                # 远端有桌面却报未检测到 X11）
                if ! ls /tmp/.X11-unix/X* >/dev/null 2>&1; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
                fi
                if ! pgrep -x Xorg >/dev/null 2>&1 && ! pgrep -x X >/dev/null 2>&1 && ! pgrep -x Xwayland >/dev/null 2>&1; then
                  echo "SCREEN_NO_DISPLAY" >&2
                  exit 1
                fi
                if ! pgrep -x Xorg >/dev/null 2>&1 && pgrep -x Xwayland >/dev/null 2>&1; then
                  echo "SCREEN_WAYLAND_ONLY" >&2
                fi
                ;;
              *)
                echo "SCREEN_UNSUPPORTED_OS:${'$'}OS" >&2
                exit 1
                ;;
            esac
            # ffmpeg 查找：SSH 非交互会话 PATH 受限（无 brew 目录），command -v 常漏掉
            # brew 安装的 ffmpeg → 误报 FFMPEG_MISSING（v1.5.0 用户反馈：装过还提示安装）
            FF=""
            for cand in ${'$'}(command -v ffmpeg 2>/dev/null) "${'$'}HOME/bin/ffmpeg" /opt/homebrew/bin/ffmpeg /usr/local/bin/ffmpeg /usr/bin/ffmpeg; do
              if [ -n "${'$'}cand" ] && [ -x "${'$'}cand" ]; then FF="${'$'}cand"; break; fi
            done
            if [ -z "${'$'}FF" ]; then echo "FFMPEG_MISSING" >&2; exit 1; fi
            if ! lsof -nP -iTCP:${'$'}PORT -sTCP:LISTEN >/dev/null 2>&1; then
              echo "SCREEN_SERVICE_MISSING" >&2; exit 1
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
            # 视频流走 UDP 漫游：输出 UDP 端口后退出（客户端据此建 UDP 会话）
            echo "SCREEN_UDP_PORT:${'$'}((PORT + 1))"
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
                # Linux：免密 sudo 时自动 apt 安装 ffmpeg；需密码时提示手动
                #（用户反馈：Ubuntu 引导安装失败——ffmpeg 缺失且只提示手动装）
                if sudo -n true 2>/dev/null; then
                  echo "==> 正在通过 apt 安装 ffmpeg（sudo 免密）"
                  sudo apt-get update -qq
                  sudo apt-get install -y -qq ffmpeg
                  FF="${'$'}(command -v ffmpeg 2>/dev/null)"
                  [ -n "${'$'}FF" ] || FF="/usr/bin/ffmpeg"
                else
                  echo "==> Linux 需要 ffmpeg：请先在服务器执行 sudo apt install ffmpeg（或配置免密 sudo 后重试）" >&2
                  exit 1
                fi
              fi
            fi
            FF_REAL=${'$'}(readlink -f "${'$'}FF" 2>/dev/null || echo "${'$'}FF")
            echo "==> ffmpeg: ${'$'}FF_REAL"
            # ---- Python 转发器（断开自愈 + 无客户端零开销）----
            APP_DIR="${'$'}HOME/Library/Application Support/termish"
            RELAY="${'$'}APP_DIR/screen-relay.py"
            mkdir -p "${'$'}APP_DIR"
            PORT="${'$'}PORT" FF_REAL="${'$'}FF_REAL" cat > "${'$'}RELAY" <<TERMISH_EOF
            #!/usr/bin/env python3
            import socket, subprocess, time, select, os, signal, sys, threading, zlib, struct
            RELAY_VERSION = 7
            TCP_PORT = ${'$'}PORT
            UDP_PORT = ${'$'}PORT + 1
            FF = "${'$'}FF_REAL"
            HEARTBEAT_MAGIC = b"THB\x01"
            # macOS 才有 ~/Library/Logs；Linux 用 ~/.termish-screen.err——
            # 目录不存在时 open() 抛异常 → ffmpeg 不会被拉起（用户反馈：
            # Ubuntu 端口监听但推流 0 字节）
            ERRLOG = os.path.expanduser("~/Library/Logs/termish-screen.err" if sys.platform == "darwin" else "~/.termish-screen.err")
            IS_MAC = sys.platform == "darwin"

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
                vf = "fps=" + cfg["fps"] + ",scale=" + cfg["scale"]
                common = ["-hide_banner", "-loglevel", "error", "-framerate", cfg["fps"],
                          "-vf", vf, "-c:v", "libx264", "-preset", "ultrafast",
                          # 不用 -tune zerolatency（其 sliced-threads 切碎帧），显式等价参数
                          # keyint=30：1s 关键帧间隔（30fps）——解码器任何重同步最多等 1s
                          "-x264opts", "sliced-threads=0:rc-lookahead=0:sync-lookahead=0:keyint=30",
                          "-pix_fmt", "yuv420p", "-g", "30",
                          # 显式无 B 帧（ultrafast 默认即 0，写死保险：B 帧需等参考帧，
                          # 会引入编码端重排延迟）
                          "-bf", "0",
                          "-threads", "1",
                          "-f", "mpegts",
                          # muxdelay/muxpreload 0：去掉 mpegts muxer 默认 0.7s 初始解码延迟，
                          # 与 flush_packets 叠加，包到达即写出（低延迟实时画面）
                          "-muxdelay", "0", "-muxpreload", "0",
                          "-flush_packets", "1", "-"]
                if IS_MAC:
                    # macOS：avfoundation 抓屏（LaunchAgent 跑在 GUI 域，TCC 放行）
                    return ["-f", "avfoundation", "-capture_cursor", "1",
                            "-pixel_format", "uyvy422", "-i", "1:none"] + common
                # Linux X11：x11grab 抓屏（DISPLAY 由服务启动时注入，默认 :0）
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
            STREAM = [None]  # 当前 UdpStream（单 ffmpeg，发到一个客户端地址）
            CLIENT = [None]  # 当前客户端地址（心跳更新，漫游时变）
            LAST_HB = [0.0]

            class UdpStream:
                # ffmpeg 抓屏 + 分片 UDP 发送。漫游（地址变化）只更新 addr 不重启
                # ffmpeg，画面连续；心跳超时才停（释放抓屏设备）。
                def __init__(self, udp, addr):
                    self.udp = udp
                    self.addr = addr
                    self.stopped = False
                    self.errf = open(ERRLOG, "a")
                    self.ff = subprocess.Popen([FF] + make_args(read_stream_cfg()), stdout=subprocess.PIPE, stderr=self.errf)
                    self.frag_id = 0
                    self.started = time.time()
                    self.last_data = time.time()
                    self.sent = 0

                def start(self):
                    threading.Thread(target=self.pump, daemon=True).start()

                def pump(self):
                    try:
                        while not self.stopped:
                            r, _, _ = select.select([self.ff.stdout], [], [], 1.0)
                            if not r:
                                # 自愈看门狗：ffmpeg 卡死无输出（首帧 45s / 中途 20s）
                                now = time.time()
                                if (self.sent == 0 and now - self.started > 45) or (self.sent > 0 and now - self.last_data > 20):
                                    if display_asleep():
                                        continue
                                    break
                                continue
                            data = self.ff.stdout.read(65536)
                            if not data:
                                break
                            for i, f in enumerate(make_fragments(data, 1200, self.frag_id)):
                                self.frag_id += 1
                                self.udp.sendto(f, self.addr)
                                # 发送节奏：每 8 片歇 1ms，避免 55 片突发打满
                                # 对端接收缓冲导致内核丢包（WiFi 实测丢 ~60%，
                                # 整块丢弃后 TS 流全是洞，播放器永卡 BUFFERING）
                                if i % 8 == 7:
                                    time.sleep(0.001)
                            self.sent += len(data)
                            self.last_data = time.time()
                    except Exception:
                        pass
                    finally:
                        self.errf.write("[%s] udp stream closed after %.1fs sent=%d\n" % (
                            time.strftime("%H:%M:%S"), time.time() - self.started, self.sent))
                        self.errf.flush()
                        # 自愈：ffmpeg 退出/看门狗触发后清空当前 stream，让主循环在
                        # 下次心跳时重新拉 ffmpeg（否则手机持续心跳会不断刷新 LAST_HB，
                        # 60s 超时永不触发，视频流会永久卡死）
                        with LOCK:
                            if STREAM[0] is self:
                                STREAM[0] = None
                                CLIENT[0] = None

                def stop(self):
                    self.stopped = True
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

            # 启动时清理一次孤儿 ffmpeg（上次 relay 被强杀后遗留，占用抓屏设备）
            subprocess.run(["pkill", "-9", "-x", "ffmpeg"], capture_output=True)

            # UDP：视频流 + 心跳（手机直连 UDP，必须绑定 0.0.0.0 收所有接口；
            # 旧 TCP 版是 SSH 内 nc 127.0.0.1 本机转发，UDP 版手机直连内网 IP）
            udp = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            udp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            udp.bind(("0.0.0.0", UDP_PORT))

            # TCP：仅 bind+listen 用于读流脚本 lsof 探测 relay 存活，不服务视频
            tcp = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            tcp.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            tcp.bind(("127.0.0.1", TCP_PORT))
            tcp.listen(4)

            while True:
                r, _, _ = select.select([udp], [], [], 1.0)
                now = time.time()
                if r:
                    try:
                        data, addr = udp.recvfrom(64)
                    except Exception:
                        continue
                    if data.startswith(HEARTBEAT_MAGIC):
                        with LOCK:
                            if STREAM[0] is None:
                                CLIENT[0] = addr
                                s = UdpStream(udp, addr)
                                s.start()
                                STREAM[0] = s
                            elif CLIENT[0] != addr:
                                # 漫游：地址变化，更新目标地址（ffmpeg 不重启，画面连续）
                                CLIENT[0] = addr
                                STREAM[0].addr = addr
                            LAST_HB[0] = now
                # 心跳超时（60s）→ 停 stream 释放抓屏设备；断网恢复后心跳重新拉 ffmpeg
                with LOCK:
                    if STREAM[0] is not None and now - LAST_HB[0] > 60:
                        STREAM[0].stop()
                        STREAM[0] = None
                        CLIENT[0] = None
            TERMISH_EOF
            # ---- 服务启动：macOS 用 LaunchAgent（GUI 域录屏权限）；
            # Linux 用 nohup 后台 + DISPLAY=:0（X11 抓屏，SSH 断开不受影响）----
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
            # 清理旧 relay 强杀后遗留的孤儿 ffmpeg（会占用 avfoundation 抓屏设备，
            # 导致新 relay 拉起的 ffmpeg 拿不到设备 → 无帧断开）。
            # 用 -x 按进程名精确匹配：正则匹配命令行会命中安装脚本自身
            #（sshd 经 zsh -c 执行，zsh 命令行含脚本全文，跨段组合即可匹配）
            pkill -x ffmpeg 2>/dev/null || true
            sleep 0.5
            launchctl bootstrap gui/${'$'}(id -u) "${'$'}PLIST"
            sleep 1
            # 验证用 launchctl（不碰连接：探测连接-断开会打断 relay 的当前服务周期；
            # 也不用 pgrep：安装脚本自身的 zsh 命令行含脚本文本会误匹配）
            if launchctl print gui/${'$'}(id -u)/dev.termish.screen 2>/dev/null | grep -q "state = running"; then
              echo "==> TERMISH_SCREEN_OK"
            else
              echo "==> 服务未启动（检查 ~/Library/Logs/termish-screen.err）" >&2
              exit 1
            fi
            else
            # Linux：重启 relay（nohup，脱离 SSH 会话存活），DISPLAY 指向图形会话。
            # ⚠️ 不用 pkill -f screen-relay.py：安装脚本自身（sh -c）命令行含
            # 脚本文本，-f 全匹配会把自己杀掉（macOS 分支同款坑，用户反馈：
            # Ubuntu 引导安装失败）——用 PID 文件精确清理
            RELAY_PID="${'$'}HOME/.termish-screen.pid"
            if [ -f "${'$'}RELAY_PID" ]; then
              kill "${'$'}(cat "${'$'}RELAY_PID")" 2>/dev/null || true
              rm -f "${'$'}RELAY_PID"
            fi
            sleep 0.5
            # 探测 X display：Xwayland 的 display 号从进程参数取（Wayland 会话
            # 可能是 :1024 等非 0 号，写死 :0 会连不上）
            XDISP=":0"
            if pgrep -x Xwayland >/dev/null 2>&1; then
              XDISP="${'$'}(pgrep -x Xwayland -a 2>/dev/null | head -1 | grep -oE ':[0-9]+' | head -1)"
              [ -n "${'$'}XDISP" ] || XDISP=":0"
            fi
            LOG="${'$'}HOME/.termish-screen.log"
            DISPLAY="${'$'}XDISP" nohup /usr/bin/python3 "${'$'}RELAY" >> "${'$'}LOG" 2>&1 &
            echo ${'$'}! > "${'$'}RELAY_PID"
            sleep 1.5
            # 验证端口监听（lsof 或 ss）；不碰连接
            if lsof -nP -iTCP:${'$'}PORT -sTCP:LISTEN >/dev/null 2>&1 || ss -ltn 2>/dev/null | grep -q ":${'$'}PORT "; then
              echo "==> TERMISH_SCREEN_OK"
            else
              echo "==> 服务未启动（检查 ${'$'}LOG）" >&2
              exit 1
            fi
            fi
            # 版本文件：客户端读流脚本检测 relay 版本匹配
            echo 7 > "${'$'}HOME/.termish-screen.version"
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

        /** scale 字符串 → 画质档位 index（960=0 / 1280=1 / 1920=2；未知按标清）。 */
        internal fun qualityIndexFor(scale: String): Int =
            when (scale) {
                "960:-2" -> 0
                "1920:-2" -> 2
                else -> 1
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
              -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 60 \
              -f h264 -flush_packets 1 -
            """.trimIndent()
    }
}
