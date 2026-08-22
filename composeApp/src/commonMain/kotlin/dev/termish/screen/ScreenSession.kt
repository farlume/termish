package dev.termish.screen

import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshSession
import dev.termish.ssh.createSshSession
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import kotlin.concurrent.Volatile
import kotlinx.datetime.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                // 探测已并入读流脚本内部（lsof 检查端口）：同一连接上先 runCommand
                // 再 startExecRaw 时第二个 exec 通道会立即 EOF（sshj 坑，实测复现）
                val channel = withContext(ioDispatcher()) {
                    session.startExecRaw(READ_STREAM_SCRIPT)
                }
                TermLog.i("screen") { "execRaw=${channel != null}" }
                if (channel == null) {
                    uiState.error = "无法启动远端读流通道"
                    running = false
                    return@launch
                }
                // 播放器接管解码/渲染（ExoPlayer 本地 HTTP 流）；首帧回调清超时
                val p = ScreenPlayer(
                    onReady = {
                        firstFrameDeadline = 0
                        scope.launch {
                            uiState.videoReady = true
                            if (uiState.error == FIRST_FRAME_TIMEOUT_MSG) uiState.error = null
                        }
                    },
                    onError = { msg -> scope.launch { uiState.error = msg } },
                )
                player = p
                uiState.player = p
                p.start()
                uiState.connected = true
                firstFrameDeadline = Clock.System.now().toEpochMilliseconds() + 12_000
                // 首帧超时监控：连接建立但迟迟无帧 → 提示（避免无限黑屏）
                val timeoutJob = scope.launch {
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
                // stderr 独立协程消费：与 stdout 串行阻塞读会永久卡死主循环（黑屏）；
                // 错误文本累计到 lastStderr，断流时透传给用户
                val stderrJob = scope.launch {
                    val sb = StringBuilder()
                    while (running) {
                        val err = withContext(ioDispatcher()) { channel.readErr() } ?: break
                        val text = err.decodeToString()
                        sb.append(text)
                        if (sb.length > 8192) sb.deleteRange(0, sb.length - 8192)
                        if (sb.contains("FFMPEG_MISSING")) {
                            uiState.ffmpegMissing = true
                            uiState.error = "远端未安装 ffmpeg"
                            running = false
                            break
                        }
                        if (sb.contains("SCREEN_SERVICE_MISSING") ||
                            sb.contains("Connection refused", ignoreCase = true)
                        ) {
                            uiState.serviceMissing = true
                            uiState.error = "远端推流服务未运行"
                            running = false
                            break
                        }
                    }
                    lastStderr = sb.toString()
                }
                // 读循环（阻塞读，跑 ioDispatcher）：原始 TS 字节喂播放器
                var bytesRead = 0L
                val readStart = Clock.System.now().toEpochMilliseconds()
                withContext(ioDispatcher()) {
                    while (running) {
                        val data = channel.read() ?: break
                        if (!running) break // close() 后残留数据不再喂
                        bytesRead += data.size
                        p.feed(data)
                    }
                    // 关闭通道：远端 nc 立即退出、relay 收尾释放 avfoundation
                    //（否则 nc 挂在 FIN_WAIT_2 往死通道里写、relay 连接悬置
                    // 到下次连接才被踢，白占抓屏设备）
                    channel.close()
                }
                // 通道 EOF（远端命令退出）：等 stderr 协程收尾，把错误拼进错误信息
                timeoutJob.cancel()
                val readMs = Clock.System.now().toEpochMilliseconds() - readStart
                TermLog.w("screen") {
                    "read loop EOF after ${readMs}ms bytes=$bytesRead stderr=${lastStderr.trim().takeLast(200)}"
                }
                if (running) {
                    stderrJob.join()
                    val detail = lastStderr.trim().takeLast(500)
                    uiState.error =
                        if (detail.isNotEmpty()) "画面流已断开：$detail" else "画面流已断开"
                    running = false
                }
            } catch (e: Exception) {
                TermLog.w("screen") { "screen session error: $e" }
                if (running) {
                    uiState.error = e.message ?: "连接失败"
                    running = false
                }
            }
        }
    }

    /**
     * 一键安装远端推流服务（幂等）：检测/安装 ffmpeg → 写 LaunchAgent plist
     * → bootstrap 到用户 GUI 域并验证端口监听。与 herdr 安装引导同模式：
     * 流式输出进 [onLog]（UI 实时展示），完成后回调 [onComplete]。
     */
    fun installService(onLog: (String) -> Unit, onComplete: (Boolean) -> Unit) {
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
                    val errJob = scope.launch {
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

    fun close() {
        running = false
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
        /** 推流服务监听端口（LaunchAgent 常驻；手机 SSH 会话 nc 读流）。 */
        const val SCREEN_PORT = 17321

        /**
         * 读流脚本：检查推流服务（lsof 探测，不产生连接）→ 缺失报 SCREEN_SERVICE_MISSING
         * （UI 转引导安装）；在则 nc 读流到 stdout。
         *
         * 注意：探测必须放脚本内（不能用 sshj 的 runCommand 预探测）——同一连接上
         * 先 runCommand 再 startExecRaw 时，第二个 exec 通道会立即 EOF（sshj 坑，实测）。
         * < /dev/null：忽略 stdin——exec 通道 stdin 保持打开时 nc 会阻塞在 stdin 读
         * 而不读 socket（实测：sshj 通道下 0 字节立即 EOF）。
         */
        val READ_STREAM_SCRIPT = """
            PORT=$SCREEN_PORT
            FF=${'$'}(command -v ffmpeg 2>/dev/null || echo "${'$'}HOME/bin/ffmpeg")
            if [ ! -x "${'$'}FF" ]; then echo "FFMPEG_MISSING" >&2; exit 1; fi
            if ! lsof -nP -iTCP:${'$'}PORT -sTCP:LISTEN >/dev/null 2>&1; then
              echo "SCREEN_SERVICE_MISSING" >&2; exit 1
            fi
            nc 127.0.0.1 ${'$'}PORT < /dev/null
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
        val INSTALL_SCRIPT = """
            set -e
            PORT=$SCREEN_PORT
            PLIST="${'$'}HOME/Library/LaunchAgents/dev.termish.screen.plist"
            # ---- ffmpeg：缺失时 brew 或静态包安装 ----
            FF=${'$'}(command -v ffmpeg 2>/dev/null || echo "${'$'}HOME/bin/ffmpeg")
            if [ ! -x "${'$'}FF" ]; then
              if command -v brew >/dev/null 2>&1; then
                echo "==> 正在通过 Homebrew 安装 ffmpeg（约几分钟）"
                brew install ffmpeg
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
            fi
            FF_REAL=${'$'}(readlink -f "${'$'}FF" 2>/dev/null || echo "${'$'}FF")
            echo "==> ffmpeg: ${'$'}FF_REAL"
            # ---- Python 转发器（断开自愈 + 无客户端零开销）----
            APP_DIR="${'$'}HOME/Library/Application Support/termish"
            RELAY="${'$'}APP_DIR/screen-relay.py"
            mkdir -p "${'$'}APP_DIR"
            PORT="${'$'}PORT" FF_REAL="${'$'}FF_REAL" cat > "${'$'}RELAY" <<TERMISH_EOF
            #!/usr/bin/env python3
            import socket, subprocess, time, select, os, signal, threading
            PORT = ${'$'}PORT
            FF = "${'$'}FF_REAL"
            ERRLOG = os.path.expanduser("~/Library/Logs/termish-screen.err")
            ARGS = ["-hide_banner", "-loglevel", "error", "-f", "avfoundation", "-framerate", "30",
                    "-capture_cursor", "1", "-pixel_format", "uyvy422", "-i", "1:none",
                    # fps=30 滤镜强制限帧：avfoundation 实际输出 ~120fps（ProMotion），
                    # -framerate 30 无效——120fps 会把手机解码器灌爆（每秒 480 NAL）
                    "-vf", "fps=30,scale=1280:-2", "-c:v", "libx264", "-preset", "ultrafast",
                    # 不用 -tune zerolatency（其 sliced-threads 切碎帧），显式等价参数
                    "-x264opts", "sliced-threads=0:rc-lookahead=0:sync-lookahead=0:keyint=60",
                    "-pix_fmt", "yuv420p", "-g", "60",
                    "-threads", "1",
                    "-f", "mpegts", "-flush_packets", "1", "-"]
            lock = threading.Lock()
            active = [None]  # 当前活跃连接（新连接优先：重连时踢掉旧会话残留）
            active_ff = [None]  # 当前活跃 ffmpeg（kick 时直接 SIGKILL，不等旧线程收尾）

            def handle(conn):
                try:
                    with lock:
                        old = active[0]
                        oldff = active_ff[0]
                        active[0] = conn
                        active_ff[0] = None
                    if old is not None:
                        # 踢掉旧连接：其 handle 检测关闭后 kill ffmpeg，释放 avfoundation 设备
                        try:
                            old.shutdown(socket.SHUT_RDWR)
                            old.close()
                        except Exception:
                            pass
                    if oldff is not None and oldff.poll() is None:
                        # 旧 ffmpeg 可能卡死在 avfoundation（读管道阻塞 → 旧线程
                        # 永远走不到 finally 的 kill）——新线程直接 SIGKILL，
                        # 否则抓屏设备被僵尸进程占死、新 ffmpeg 挂起无输出
                        try:
                            os.kill(oldff.pid, signal.SIGKILL)
                        except Exception:
                            pass
                    if old is not None:
                        time.sleep(3.0)
                    conn.setblocking(False)
                    errf = open(ERRLOG, "a")
                    ff = subprocess.Popen([FF] + ARGS, stdout=subprocess.PIPE, stderr=errf)
                    with lock:
                        active_ff[0] = ff
                    started = time.time()
                    last_data = time.time()
                    sent = 0
                    reason = "eof"
                    try:
                        while True:
                            r, _, _ = select.select([ff.stdout], [], [], 1.0)
                            if r:
                                data = ff.stdout.read(65536)
                                if not data:
                                    reason = "ffmpeg-exit"
                                    break
                                if not send_all(conn, data):
                                    reason = "peer-closed"
                                    break
                                sent += len(data)
                                last_data = time.time()
                            else:
                                # 自愈看门狗：ffmpeg 卡死（设备被占/挂起）时无数据输出——
                                # 首帧 45s / 中途 20s 无数据即放弃本连接，kill ffmpeg
                                # 放客户端重连（否则僵尸 ffmpeg 堆积占死抓屏设备）
                                now = time.time()
                                if (sent == 0 and now - started > 45) or (sent > 0 and now - last_data > 20):
                                    reason = "ffmpeg-stall"
                                    break
                                # 1 秒无数据（ffmpeg 预热/静默期）：探测对端真实状态。
                                # 关键：BSD nc 客户端（脚本用 < /dev/null）在 stdin EOF 时
                                # 立即半关闭写方向（FIN）——recv 返回 b"" 只代表「对端
                                # 不再发送」，不代表「对端已断开」（对端仍在读）。若把
                                # b"" 当断连杀 ffmpeg，预热期（1-3s）的 ffmpeg 会被
                                # 误杀（sent=0 peer-finished，手机端画面流 1 秒即断）。
                                # 真正断连由 RST（recv 抛 OSError）或后续 send 失败检测。
                                try:
                                    conn.recv(1, socket.MSG_PEEK)
                                except (socket.timeout, BlockingIOError):
                                    pass
                                except OSError:
                                    reason = "peer-error"
                                    break
                    except (BrokenPipeError, ConnectionResetError, OSError) as e:
                        reason = "send-err:%r" % (e,)
                    finally:
                        # SIGTERM 后 ffmpeg 可能卡死在 avfoundation 释放（实测最长
                        # ~1 分钟）：期间抓屏设备被占、本线程卡在 wait() 不关连接
                        #（socket 悬置 CLOSE_WAIT）；限时 5s 后 SIGKILL 兜底。
                        try:
                            ff.kill()
                        except Exception:
                            pass
                        try:
                            ff.wait(timeout=5)
                        except Exception:
                            try:
                                os.kill(ff.pid, signal.SIGKILL)
                            except Exception:
                                pass
                            try:
                                ff.wait()
                            except Exception:
                                pass
                        errf.write("[%s] conn closed after %.1fs sent=%d reason=%s\n" % (
                            time.strftime("%H:%M:%S"), time.time() - started, sent, reason))
                        errf.flush()
                        errf.close()
                        with lock:
                            if active[0] is conn:
                                active[0] = None
                            if active_ff[0] is ff:
                                active_ff[0] = None
                        conn.close()
                except Exception:
                    pass

            def send_all(conn, data):
                # 非阻塞发送 + select 等待可写：客户端消费慢（解码慢）时不超时误断；
                # 对端半关闭（FIN，nc stdin EOF 即发）只代表不再发送，不代表断开——
                # recv 返回 b"" 时继续等待，真正断开由后续 send 的 ECONNRESET 检测
                view = memoryview(data)
                while view:
                    try:
                        n = conn.send(view)
                        view = view[n:]
                    except BlockingIOError:
                        r, w, _ = select.select([], [conn], [], 1.0)
                        if w:
                            continue
                        try:
                            conn.recv(1, socket.MSG_PEEK)
                        except (socket.timeout, BlockingIOError):
                            pass
                        except OSError:
                            return False
                    except OSError:
                        return False
                return True

            # 启动时清理一次孤儿 ffmpeg（上次 relay 被强杀后遗留，占用 avfoundation
            # 设备，会导致新拉起的 ffmpeg 挂起无输出）。-x 按进程名精确匹配，
            # 不会误伤 zsh（其命令行含脚本全文）；只在启动时执行——循环内执行
            # 会把正在服务其他连接的 ffmpeg 误杀
            subprocess.run(["pkill", "-9", "-x", "ffmpeg"], capture_output=True)
            # bind/listen 只做一次：循环内重复 bind 会因端口已被 LISTEN 占用而失败，
            # 导致 accept 循环死掉、新连接永远排队无人服务（重连/多次连接即触发）
            srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            srv.bind(("127.0.0.1", PORT))
            srv.listen(4)
            while True:
                conn, _ = srv.accept()
                threading.Thread(target=handle, args=(conn,), daemon=True).start()
            TERMISH_EOF
            # ---- LaunchAgent（GUI 域：拥有屏幕录制权限）----
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
        """.trimIndent()

        /**
         * 测试用推流脚本：lavfi 测试图源（不依赖屏幕录制权限）。
         * 集成测试用它回归「exec raw 通道 + stderr 协程 + NAL 解析」链路。
         */
        val LAVFI_SCRIPT = """
            FF=${'$'}(command -v ffmpeg 2>/dev/null || echo "${'$'}HOME/bin/ffmpeg")
            if [ ! -x "${'$'}FF" ]; then echo "FFMPEG_MISSING" >&2; exit 1; fi
            exec "${'$'}FF" -hide_banner -loglevel error -f lavfi -i testsrc=size=640x360:rate=30 \
              -c:v libx264 -preset ultrafast -tune zerolatency -pix_fmt yuv420p -g 60 \
              -f h264 -flush_packets 1 -
        """.trimIndent()
    }
}
