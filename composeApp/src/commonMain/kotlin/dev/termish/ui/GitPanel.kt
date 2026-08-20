package dev.termish.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import dev.termish.herdr.parseHerdrSnapshot
import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.SshSession
import dev.termish.term.TerminalBuffer
import dev.termish.term.TerminalLine
import dev.termish.ui.theme.TerminalTheme
import dev.termish.util.TermLog
import dev.termish.util.ioDispatcher
import dev.termish.util.monospaceFontFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** git 命令超时（本地 git 一般毫秒级；大仓库 status/diff 也远小于此）。 */
private const val GIT_TIMEOUT_MS = 30_000L
/** diff 输出上限（head 截断，防止超大文件刷爆终端回看）。 */
private const val DIFF_MAX_LINES = 600

/** bash 默认 PS1（`user@host:~/path$` / `#`）的工作目录提取。 */
private val PROMPT_CWD_REGEX = Regex("""^[^\s@]+@[^\s:]+:([^\s$#]*)[$#]\s*$""")

/**
 * 工作目录探测脚本（经 exec 通道执行，不碰交互终端）：
 * 1. tmux 焦点 pane 路径（tmux 守护进程可查）
 * 2. agent 进程（pi/codex/claude）的 /proc cwd——全屏 TUI 运行时 shell 的
 *    cwd 就是 agent 启动前的项目目录（agent 是 shell 子进程，shell cwd 不变）
 * 3. 交互 shell（bash/zsh/fish）的 /proc cwd
 * 候选 cwd 必须 `git rev-parse` 验证为仓库才返回（多个 shell 会话时取
 * 真正的工作区）；不用 tail -1（进程退出竞态会拿到死 pid）。
 * 4. 免验证兜底：任何交互 shell 的 cwd——无仓库时让面板给出「非 git
 *    仓库 / git 未安装」的准确错误，而不是探测失败回退到注入终端
 */
private const val WORKDIR_PROBE_SCRIPT =
    "tmux display-message -p '#{pane_current_path}' 2>/dev/null; " +
        "for p in pi pi-coding-agent codex claude; do " +
        "for pid in \$(pgrep -u \$(id -u) -x \$p 2>/dev/null); do " +
        "c=\$(readlink /proc/\$pid/cwd 2>/dev/null); " +
        "[ -n \"\$c\" ] && git -C \"\$c\" rev-parse --git-dir >/dev/null 2>&1 && { echo \"\$c\"; exit 0; }; " +
        "done; done; " +
        "for s in bash zsh fish; do " +
        "for pid in \$(pgrep -u \$(id -u) -x \$s 2>/dev/null); do " +
        "c=\$(readlink /proc/\$pid/cwd 2>/dev/null); " +
        "[ -n \"\$c\" ] && git -C \"\$c\" rev-parse --git-dir >/dev/null 2>&1 && { echo \"\$c\"; exit 0; }; " +
        "done; done; " +
        "for s in bash zsh fish; do " +
        "for pid in \$(pgrep -u \$(id -u) -x \$s 2>/dev/null); do " +
        "c=\$(readlink /proc/\$pid/cwd 2>/dev/null); " +
        "[ -n \"\$c\" ] && { echo \"\$c\"; exit 0; }; " +
        "done; done; echo ''"

/** git 命令执行超时。 */
internal class GitTimeoutException : Exception("git command timed out")

/** git 命令执行失败（非超时，如无法获取工作区）。 */
internal class GitCommandException(message: String) : Exception(message)

/** 无法确定远端工作目录（无可用独立通道）——命令不执行，由面板映射为本地化文案。 */
internal class GitWorkdirUnknownException : Exception()

/**
 * mosh 控制面连接的辅助回调：输出全部忽略（不污染终端缓冲）、
 * 主机密钥信任沿用主连接（同一主机已确认过）、私钥口令不弹窗（无密码场景返回 null）。
 */
private object AuxCallbacks : SshCallbacks {
    override suspend fun onOutput(data: ByteArray) {}
    override suspend fun onStderr(data: ByteArray) {}
    override fun onExitStatus(status: Int) {}
    override fun onClosed(reason: String?) {}
    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null
    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
}

/**
 * 终端 git 命令执行器——**绝不向交互终端注入命令**，按会话模式选独立通道：
 *
 * - **SSH/herdr**：`SshSession.runCommand` 独立 exec 通道（复用已认证连接、
 *   无 PTY、不打扰交互 shell / herdr TUI），输出直接捕获；herdr 的 cwd
 *   取焦点 pane 的工作区。
 * - **mosh**：控制面独立 SSH 连接 `connectAndRun`（mosh 官方架构：
 *   交互走 UDP，控制面走 SSH）。
 *
 * 工作目录由 [fetchWorkdir] 探测；探测不到时抛 [GitWorkdirUnknownException]
 * （面板内报错，终端画面零污染）。
 */
internal class GitCommandRunner(private val controller: TerminalController) {

    /** herdr 模式（herdrExec 活跃）：cwd 走 herdr snapshot，无交互 fallback。 */
    val herdrMode: Boolean get() = controller.herdrExec != null

    /** 工作目录（独立 exec 通道用 `git -C` 定位；refresh 时探测，见 [fetchWorkdir]）。 */
    var workdir: String? = null

    /** 复用已认证连接的 exec 通道（SSH/herdr 模式）。 */
    private val execSession: SshSession?
        get() = controller.session?.takeIf { it.isActive() }

    /**
     * 探测 Git 工作目录（独立通道的 `git -C` 定位），按模式分层：
     * 1. herdr：`herdr api snapshot` 焦点 pane 的 cwd（agent 正在改代码的目录）
     * 2. tmux：`tmux display-message -p '#{pane_current_path}'`（tmux 守护进程
     *    可从任意进程查询焦点 pane 路径——agent 工作流标准做法）
     * 3. /proc 进程探测：`readlink /proc/<pid>/cwd` 拿 agent（pi/codex/claude）或
     *    交互 shell 进程的工作目录——**全屏程序（TUI 运行中）场景的关键**：
     *    终端里没有提示符可解析，但 shell 的 cwd 就是 agent 启动前的项目目录
     *    （agent 是 shell 的子进程，shell cwd 不变）
     * 4. bash 提示符：解析终端末行提示符里的 \w（裸 shell 场景；PS1 自定义
     *    格式不匹配则放弃；mosh 下引导通道已关无法解析 ~，仅接受绝对路径）
     * 拿不到返回 null（调用方报错——**不回退到交互终端注入命令**）。
     * mosh 模式（SSH 引导通道已关）：探测走控制面连接（connectAndRun）。
     */
    suspend fun fetchWorkdir(): String? {
        if (herdrMode) {
            return withContext(ioDispatcher()) {
                val bin = controller.herdrBin?.let { shellQuote(it) } ?: "herdr"
                val raw = controller.session?.runCommand("$bin api snapshot", 5_000)
                    ?: return@withContext null
                val snap = parseHerdrSnapshot(raw) ?: return@withContext null
                snap.panes.firstOrNull { it.focused }?.cwd?.takeIf { it.isNotBlank() }
                    ?: snap.agents.firstOrNull { it.focused }?.cwd?.takeIf { it.isNotBlank() }
                    ?: snap.panes.firstOrNull { !it.cwd.isNullOrBlank() }?.cwd
            }
        }
        // 提示符 \w 解析（当前会话最准；全屏程序/自定义 PS1 时不匹配自动跳过）
        val promptPath = parsePromptCwd(controller.buffer)
        if (promptPath != null) {
            val session = execSession
            if (session != null) {
                val home = withContext(ioDispatcher()) {
                    session.runCommand("echo \$HOME", 3_000)
                }?.trim().orEmpty()
                val resolved = when {
                    promptPath == "~" -> home
                    promptPath.startsWith("~/") -> home + promptPath.removePrefix("~")
                    else -> promptPath
                }
                if (resolved.isNotBlank() && resolved.startsWith("/")) {
                    TermLog.d("git") { "workdir via prompt: $resolved" }
                    return resolved
                }
            } else if (promptPath.startsWith("/")) {
                // mosh（引导通道已关）：无法解析 ~，绝对路径直接可用
                TermLog.d("git") { "workdir via prompt: $promptPath" }
                return promptPath
            }
        }
        // tmux → /proc 进程探测（agent 优先，回退交互 shell）
        val session = execSession
        if (session != null) {
            val raw = withContext(ioDispatcher()) {
                session.runCommand(WORKDIR_PROBE_SCRIPT, 5_000)
            }
            TermLog.d("git") { "probe raw=[${raw?.replace("\n", "\\n") ?: "null"}]" }
            val probed = firstPathLine(raw)
            if (probed != null) {
                TermLog.d("git") { "workdir via probe: $probed" }
                return probed
            }
        } else if (controller.moshSession != null) {
            // mosh：SSH 引导通道已关，探测走控制面连接（一次连接）
            val raw = withContext(ioDispatcher()) {
                val aux = newAuxConnection() ?: return@withContext null
                try {
                    aux.connectAndRun(WORKDIR_PROBE_SCRIPT, 5_000).output
                } catch (e: Exception) {
                    TermLog.w("git") { "aux probe failed: ${e.message}" }
                    null
                } finally {
                    try { aux.close() } catch (_: Exception) {}
                }
            }
            val probed = firstPathLine(raw)
            if (probed != null) {
                TermLog.d("git") { "workdir via aux probe: $probed" }
                return probed
            }
        }
        TermLog.d("git") { "workdir probe failed" }
        return null
    }

    /** 探测输出取第一个绝对路径行（tmux 与 /proc 可能各输出一行，不能拼接）。 */
    private fun firstPathLine(raw: String?): String? =
        raw?.lineSequence()?.firstOrNull { it.trimStart().startsWith("/") }?.trim()

    /** 新建 mosh 控制面连接（mosh 官方架构：交互走 UDP，控制面走独立 SSH）。 */
    private fun newAuxConnection(): SshSession? = try {
        val conn = SshConnection(
            host = controller.host.hostname,
            port = controller.host.port,
            username = controller.host.username,
            password = controller.password,
            privateKeyPem = controller.privateKeyPem,
            connectTimeoutMillis = 10_000,
            keepAliveSeconds = 0,
        )
        controller.sessionFactory(conn, AuxCallbacks)
    } catch (e: Exception) {
        TermLog.w("git") { "aux connection failed: ${e.message}" }
        null
    }

    /** bash 默认 PS1 `user@host:~/path$`（或 `#` root）的 \w 提取；
     *  输入中的行（`$` 后还有内容）不匹配，避免把半行命令当提示符。 */
    private fun parsePromptCwd(buffer: TerminalBuffer): String? {
        val total = buffer.totalLines()
        for (i in (total - 1) downTo maxOf(0, total - 40)) {
            val text = lineText(buffer.absLine(i))
            val m = PROMPT_CWD_REGEX.find(text) ?: continue
            return m.groupValues[1]
        }
        return null
    }

    /**
     * 执行 [command] 并返回完整输出（已 strip ANSI）——**绝不注入交互终端**：
     * 1. **独立 exec 通道**：复用已认证连接（SSH/herdr）跑 `git -C <cwd>`——
     *    命令不进交互终端，画面零污染（业界标准：VS Code Remote-SSH 的 git
     *    操作同样走独立 exec channel）。
     * 2. **mosh 控制面连接**（mosh 的 SSH 引导通道已关）：懒建独立 SSH 连接
     *    `connectAndRun`（mosh 官方架构：交互走 UDP，控制面走 SSH）。
     * 工作目录未知时抛 [GitWorkdirUnknownException]（面板内报错；herdr 模式
     * 抛 [GitCommandException]，禁止注入 herdr/pi 输入框）。
     */
    suspend fun run(command: String, timeoutMs: Long = GIT_TIMEOUT_MS): String {
        val dir = workdir ?: run {
            // herdr 模式报具体原因；其余场景统一「无法确定工作目录」
            if (herdrMode) throw GitCommandException("无法获取 herdr 工作区（snapshot 失败）")
            throw GitWorkdirUnknownException()
        }
        val full = "git -C ${shellQuote(dir)} ${command.removePrefix("git ")}"
        // 1) 独立 exec 通道：复用已认证连接（SSH/herdr）
        val session = execSession
        if (session != null) {
            TermLog.d("git") { "run exec cwd=$dir cmd=$full" }
            return withContext(ioDispatcher()) {
                session.runCommand(full, timeoutMs) ?: throw GitTimeoutException()
            }.let { stripAnsi(it) }
        }
        // 2) mosh 控制面连接：SSH 引导通道已关，懒建独立连接（mosh 官方架构）
        if (controller.moshSession != null) {
            TermLog.d("git") { "run aux-ssh cwd=$dir cmd=$full" }
            return withContext(ioDispatcher()) {
                val aux = newAuxConnection() ?: throw GitTimeoutException()
                try {
                    aux.connectAndRun(full, timeoutMs).output
                } catch (e: Exception) {
                    TermLog.w("git") { "aux ssh failed: ${e.message}" }
                    throw GitTimeoutException()
                } finally {
                    try { aux.close() } catch (_: Exception) {}
                }
            }.let { stripAnsi(it) }
        }
        // 断连竞态等极端情况：exec/mosh 通道都不可用
        throw GitWorkdirUnknownException()
    }

    private fun lineText(line: TerminalLine): String {
        val sb = StringBuilder(line.cols)
        for (c in line.cells) {
            if (c.isWideTail) continue
            sb.append(c.codePoint.toChar())
        }
        var end = sb.length
        while (end > 0 && sb[end - 1] == ' ') end--
        return sb.substring(0, end)
    }

    /** 清除输出中残留的 ANSI 转义（git color.ui 已强制关闭，双保险）。 */
    private fun stripAnsi(s: String): String {
        if ('\u001b' !in s) return s
        return ANSI_ESCAPE_REGEX.replace(s, "")
    }

    companion object {
        // SGR/CSI 与 OSC 序列（双保险：终端渲染前可能未完全消费的残片）
        private val ANSI_ESCAPE_REGEX = Regex("\u001b\\[[0-9;?]*[a-zA-Z]|\u001b\\][^\u0007]*\u0007")
    }
}

/** Git 状态徽章配色：贴合终端 ANSI 语义（修改=黄，新增=绿，删除=红…）。 */
private fun statusColor(code: String, theme: TerminalTheme): Color {
    val c = code.firstOrNull() ?: return theme.ansi(7)
    return when {
        c == '?' || c == '!' -> theme.ansi(6) // 未跟踪/忽略：青
        c == 'A' -> theme.ansi(2) // 新增：绿
        c == 'D' -> theme.ansi(1) // 删除：红
        c == 'R' || c == 'C' -> theme.ansi(5) // 重命名/复制：紫
        c == 'U' || code.contains('U') -> theme.ansi(1) // 冲突：红
        else -> theme.ansi(3) // 修改：黄
    }
}

/**
 * Git 悬浮面板：终端画布右侧悬浮按钮（可拖动）+ 状态/diff 面板 + 提交对话框。
 *
 * 仅 SSH/mosh 已连接时显示。git 命令只走独立通道（SSH 复用已认证连接的
 * exec channel / mosh 控制面连接），**绝不注入交互终端**；探测不到工作
 * 目录时面板内报错。herdr 模式命令走独立 exec 通道（不注入 herdr/pi 输入框）。
 * [open]/[onOpenChange] 状态提升：键盘工具栏「⋯」溢出面板也提供入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GitOverlay(
    controller: TerminalController,
    theme: TerminalTheme,
    /** 面板打开状态（由调用方持有，供 FAB 与工具栏溢出面板共用）。 */
    open: Boolean,
    onOpenChange: (Boolean) -> Unit,
    /** 底部让出高度（键盘工具栏 + 间距）：FAB 悬浮在工具栏上方。 */
    bottomInset: Dp,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    val scope = rememberCoroutineScope()
    val runner = remember(controller) { GitCommandRunner(controller) }

    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var branch by remember { mutableStateOf<String?>(null) }
    var ahead by remember { mutableIntStateOf(0) }
    var behind by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<GitEntry>>(emptyList()) }
    var selected by remember { mutableStateOf<GitEntry?>(null) }
    var diffLines by remember { mutableStateOf<List<GitDiffLine>>(emptyList()) }
    var diffLoading by remember { mutableStateOf(false) }
    var diffError by remember { mutableStateOf<String?>(null) }
    var stagedOverride by remember { mutableStateOf<Boolean?>(null) }
    var commitOpen by remember { mutableStateOf(false) }
    var commitMsg by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    /** 画布区域尺寸（FAB 拖拽钳制边界）。 */
    var overlaySize by remember { mutableStateOf(IntSize.Zero) }
    /** FAB 拖拽偏移（会话内保持：面板开关不重置）。 */
    var fabDrag by remember { mutableStateOf(Offset.Zero) }

    val connected = controller.status == ConnStatus.CONNECTED
    if (!connected) return

    // 面板打开时拦截系统返回：先关面板，不直接退回首页。
    // 注意：ModalBottomSheet 是 Dialog 窗口，BACK 由 Dialog 消费（关闭面板），
    // 不会到这里；diff 页返回由页内 ← 按钮处理（回状态页）
    PlatformBackHandler(enabled = open) { onOpenChange(false) }

    // Git 命令走独立 exec 通道（复用已认证连接 / mosh 控制面连接），不注入
    // 交互终端——vim/tmux 全屏程序下也可用（命令不进 TUI）。探测不到工作
    // 目录时面板内报错（全屏程序中提示先退出）。
    val inAltScreen = controller.buffer.altScreen

    fun friendly(raw: String): String = when {
        raw.contains("not a git repository") -> s.git.notRepo
        raw.contains("command not found") -> s.git.notInstalled
        raw.contains("nothing to commit") -> s.git.nothingToCommit
        else -> raw.take(240)
    }

    /** 提取输出中的错误行并转友好文案；无错误返回 null。 */
    fun extractError(out: String): String? {
        for (raw in out.lineSequence()) {
            val l = raw.trim()
            if (l.startsWith("fatal:") || l.startsWith("error:") ||
                l.contains("command not found") || l.contains("nothing to commit")
            ) {
                return friendly(l)
            }
        }
        return null
    }

    /** 执行命令（busy 防重入），返回错误文案或 null。 */
    suspend fun exec(cmd: String): String? {
        if (busy) return null
        busy = true
        try {
            return extractError(runner.run(cmd))
        } catch (e: GitCommandException) {
            return e.message
        } catch (e: GitWorkdirUnknownException) {
            return s.git.workdirUnknown
        } catch (e: GitTimeoutException) {
            return s.git.timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return e.message
        } finally {
            busy = false
        }
    }

    /**
     * 探测 Git 工作目录（独立通道定位）。herdr 走 snapshot；SSH/mosh 走
     * 提示符解析 / tmux / /proc 进程探测。探测不到时命令不执行、面板内
     * 报错——绝不回退到交互终端注入命令。
     */
    suspend fun probeWorkdir() {
        runner.workdir = runner.fetchWorkdir()
        TermLog.d("git") { "workdir=${runner.workdir}" }
    }

    suspend fun refresh() {
        if (busy) return
        busy = true
        loading = true
        error = null
        try {
            probeWorkdir()
            val out = runner.run(
                "git -c color.ui=false -c core.quotepath=false status --porcelain=v1 --branch 2>&1 | head -n 2001",
            )
            val res = parseGitStatus(out)
            error = res.error?.let { friendly(it) }
            branch = res.branch
            ahead = res.ahead
            behind = res.behind
            entries = res.entries
        } catch (e: GitWorkdirUnknownException) {
            error = s.git.workdirUnknown
        } catch (e: GitTimeoutException) {
            error = s.git.timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message
        } finally {
            loading = false
            busy = false
        }
    }

    suspend fun loadDiff(entry: GitEntry, staged: Boolean) {
        if (busy || !entry.diffable) return
        busy = true
        diffLoading = true
        diffError = null
        try {
            val cmd = when {
                entry.untracked ->
                    "git -c color.ui=false -c core.quotepath=false diff --no-index --no-color -- /dev/null ${shellQuote(entry.path)}"
                staged ->
                    "git -c color.ui=false -c core.quotepath=false diff --cached --no-color -- ${shellQuote(entry.path)}"
                else ->
                    "git -c color.ui=false -c core.quotepath=false diff --no-color -- ${shellQuote(entry.path)}"
            }
            val out = runner.run("$cmd 2>&1 | head -n ${DIFF_MAX_LINES + 1}")
            diffLines = parseGitDiff(out)
            diffError = extractError(out)
        } catch (e: GitWorkdirUnknownException) {
            diffError = s.git.workdirUnknown
        } catch (e: GitTimeoutException) {
            diffError = s.git.timeout
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            diffError = e.message
        } finally {
            diffLoading = false
            busy = false
        }
    }

    suspend fun doCommit() {
        val msg = commitMsg.trim()
        if (msg.isEmpty()) return
        commitOpen = false
        val err = exec("git add -A 2>&1 && git commit -m ${shellQuote(msg)} 2>&1")
        if (err == null) {
            onToast(s.git.committed)
            commitMsg = ""
        } else {
            error = err
        }
        refresh()
    }

    // 打开面板即刷新状态；关闭时清空视图
    LaunchedEffect(open) {
        if (open) refresh() else selected = null
    }
    // 选中文件：重置 staged 偏好并加载 diff
    LaunchedEffect(selected) {
        stagedOverride = null
    }
    val stagedOnly = selected?.let { it.isStaged && it.worktreeStatus == ' ' } ?: false
    val showStaged = stagedOverride ?: stagedOnly
    val canToggleStaged = selected?.isStaged == true && selected?.worktreeStatus != ' '
    LaunchedEffect(selected, showStaged) {
        val e = selected ?: return@LaunchedEffect
        if (e.diffable) loadDiff(e, showStaged)
    }

    Box(modifier.onSizeChanged { overlaySize = it }) {
        // ---- 悬浮入口按钮：画布右侧中间，可拖动（长按拖动到任意位置）----
        if (!open) {
            val density = LocalDensity.current
            val fabSizePx = with(density) { 44.dp.toPx() }
            val fabPaddingPx = with(density) { 10.dp.toPx() }
            // 组合阶段读取 state：拖动更新 → 重组 → offset 参数更新（lambda 版在
            // 布局阶段读取，实测不触发重绘，改用 Dp 参数版）
            val fabOffsetX = with(density) { fabDrag.x.toDp() }
            val fabOffsetY = with(density) { fabDrag.y.toDp() }

            // 拖拽偏移钳制：按钮实际位置（初始 CenterEnd + 偏移）不超出画布
            // （画布尺寸由外层 Box 的 onSizeChanged 提供，FAB 自身尺寸量不到画布）
            fun clampFabDrag(drag: Offset): Offset {
                if (overlaySize == IntSize.Zero) return drag
                val baseLeft = (overlaySize.width - fabSizePx - fabPaddingPx)
                    .toFloat()
                    .coerceAtLeast(0f)
                val baseTop = ((overlaySize.height - fabSizePx) / 2f).coerceAtLeast(0f)
                return Offset(
                    drag.x.coerceIn(-baseLeft, overlaySize.width - fabSizePx - baseLeft),
                    drag.y.coerceIn(-baseTop, overlaySize.height - fabSizePx - baseTop),
                )
            }

            Box(
                Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 10.dp)
                    .offset(x = fabOffsetX, y = fabOffsetY)
                    .size(44.dp)
                    .shadow(6.dp, CircleShape)
                    .clip(CircleShape)
                    .background(theme.cursor())
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDrag = { change, drag ->
                                change.consume()
                                fabDrag = clampFabDrag(fabDrag + drag)
                            },
                        )
                    }
                    .clickable { onOpenChange(true) },
                contentAlignment = Alignment.Center,
            ) {
                GitBranchIcon(theme.background(), Modifier.size(21.dp))
            }
        }
    }

    // ---- 面板：全屏底部弹出（同快捷命令面板），盖过键盘工具栏 ----
    if (open) {
        ModalBottomSheet(
            onDismissRequest = { onOpenChange(false) },
            containerColor = theme.background(),
            contentColor = theme.foreground(),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.72f)
                    .imePadding(),
            ) {
                // 全屏程序（vim/tmux）且探测不到工作目录：只提示不执行
                //（不注入 TUI）；探测成功（独立 exec 通道）则正常使用
                if (inAltScreen && runner.workdir == null && !loading) {
                    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                s.git.fullscreenHint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = theme.foreground(),
                            )
                            Spacer(Modifier.height(16.dp))
                            TextButton(onClick = { onOpenChange(false) }) {
                                Text(s.terminalCancel)
                            }
                        }
                    }
                    } else if (selected == null) {
                        GitStatusHeader(
                            theme = theme,
                            branch = branch,
                            ahead = ahead,
                            behind = behind,
                            entryCount = entries.size,
                            loading = loading,
                            onRefresh = { scope.launch { refresh() } },
                            onClose = { onOpenChange(false) },
                        )
                        HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                        when {
                            loading -> GitCenteredHint(theme, s.git.loading, spinner = true, modifier = Modifier.weight(1f))
                            error != null -> GitCenteredHint(
                                theme, error!!,
                                retry = { scope.launch { refresh() } },
                                onClose = { onOpenChange(false) },
                                modifier = Modifier.weight(1f),
                            )
                            entries.isEmpty() -> GitCenteredHint(theme, s.git.empty, modifier = Modifier.weight(1f))
                            else -> LazyColumn(
                                Modifier.weight(1f),
                            ) {
                                items(entries, key = { it.path }) { entry ->
                                    GitFileRow(entry, theme, s) { selected = entry }
                                }
                            }
                        }
                        // 底部操作：暂存 / 取消暂存 / 提交
                        HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            TextButton(
                                onClick = {
                                    scope.launch {
                                        exec("git add -A 2>&1")
                                        refresh()
                                    }
                                },
                                enabled = entries.isNotEmpty() && !busy,
                            ) {
                                Text(s.git.stageAll, color = theme.ansi(2))
                            }
                            if (entries.any { it.isStaged }) {
                                TextButton(
                                    onClick = {
                                        scope.launch {
                                            exec("git reset -q 2>&1")
                                            refresh()
                                        }
                                    },
                                    enabled = !busy,
                                ) {
                                    Text(s.git.unstageAll, color = theme.ansi(3))
                                }
                            }
                            Spacer(Modifier.weight(1f))
                            TextButton(
                                onClick = { commitOpen = true },
                                enabled = entries.isNotEmpty() && !busy,
                            ) {
                                Text(s.git.commit, color = theme.ansi(2), fontWeight = FontWeight.SemiBold)
                            }
                        }
                    } else {
                        // ---- Diff 页 ----
                        val entry = selected!!
                        GitDiffHeader(
                            theme = theme,
                            entry = entry,
                            staged = showStaged,
                            canToggle = canToggleStaged,
                            lines = diffLines,
                            onBack = { selected = null },
                            onToggleStaged = { stagedOverride = it },
                        )
                        HorizontalDivider(color = theme.foreground().copy(alpha = 0.2f))
                        when {
                            !entry.diffable -> GitCenteredHint(theme, s.git.dirHint, modifier = Modifier.weight(1f))
                            diffLoading -> GitCenteredHint(theme, s.git.diffLoading, spinner = true, modifier = Modifier.weight(1f))
                            diffError != null -> GitCenteredHint(
                                theme, diffError!!,
                                retry = {
                                    scope.launch { loadDiff(entry, showStaged) }
                                },
                                onClose = { selected = null },
                                modifier = Modifier.weight(1f),
                            )
                            diffLines.isEmpty() -> GitCenteredHint(theme, s.git.noDiff, modifier = Modifier.weight(1f))
                            else -> LazyColumn(Modifier.weight(1f)) {
                                items(diffLines) { line -> GitDiffRow(line, theme) }
                                if (diffLines.size >= DIFF_MAX_LINES) {
                                    item { GitCenteredHint(theme, s.git.diffTruncated) }
                                }
                            }
                        }
                    }
                }
            }
        }

    // ---- 提交对话框 ----
    if (commitOpen) {
        AlertDialog(
            onDismissRequest = { commitOpen = false },
            title = { Text(s.git.commitTitle) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = commitMsg,
                        onValueChange = { commitMsg = it },
                        label = { Text(s.git.commitMessage) },
                        minLines = 2,
                    )
                    Text(
                        s.git.commitHint,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { scope.launch { doCommit() } },
                    enabled = commitMsg.isNotBlank() && !busy,
                ) {
                    Text(s.git.commitConfirm)
                }
            },
            dismissButton = {
                TextButton(onClick = { commitOpen = false }) {
                    Text(s.terminalCancel)
                }
            },
        )
    }
}

/** 面板头部：分支 + ahead/behind + 变更数 + 刷新 + 关闭。 */
@Composable
private fun GitStatusHeader(
    theme: TerminalTheme,
    branch: String?,
    ahead: Int,
    behind: Int,
    entryCount: Int,
    loading: Boolean,
    onRefresh: () -> Unit,
    onClose: () -> Unit,
) {
    val s = LocalAppStrings.current
    Row(
        Modifier.fillMaxWidth().padding(start = 14.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GitBranchIcon(theme.foreground(), Modifier.size(16.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            branch ?: s.git.noBranch,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = theme.foreground(),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (ahead > 0 || behind > 0) {
            Spacer(Modifier.width(6.dp))
            Text(
                "↑$ahead ↓$behind",
                style = MaterialTheme.typography.labelSmall,
                color = theme.ansi(4),
            )
        }
        Spacer(Modifier.weight(1f))
        Text(
            s.git.changes(entryCount),
            style = MaterialTheme.typography.labelSmall,
            color = theme.foreground().copy(alpha = 0.6f),
        )
        IconButton(onClick = onRefresh, enabled = !loading, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Refresh,
                contentDescription = s.git.refresh,
                tint = theme.foreground().copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
        IconButton(onClick = onClose, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = s.git.close,
                tint = theme.foreground().copy(alpha = 0.8f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

/** Diff 页头部：返回 + 文件路径 + 暂存切换 + 行数统计。 */
@Composable
private fun GitDiffHeader(
    theme: TerminalTheme,
    entry: GitEntry,
    staged: Boolean,
    canToggle: Boolean,
    lines: List<GitDiffLine>,
    onBack: () -> Unit,
    onToggleStaged: (Boolean) -> Unit,
) {
    val s = LocalAppStrings.current
    val adds = lines.count { it.kind == GitDiffKind.ADD }
    val removes = lines.count { it.kind == GitDiffKind.REMOVE }
    Row(
        Modifier.fillMaxWidth().padding(start = 2.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack, modifier = Modifier.size(34.dp)) {
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                contentDescription = s.navBack,
                tint = theme.foreground(),
                modifier = Modifier.size(20.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayPath,
                style = MaterialTheme.typography.labelMedium,
                color = theme.foreground(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (adds + removes > 0) {
                Text(
                    "+$adds -$removes",
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.foreground().copy(alpha = 0.6f),
                )
            }
        }
        if (canToggle) {
            // 已暂存 / 未暂存 切换
            Row(
                Modifier.clip(RoundedCornerShape(8.dp))
                    .background(theme.foreground().copy(alpha = 0.08f)),
            ) {
                DiffTab(s.git.stagedTab, selected = staged, theme) { onToggleStaged(true) }
                DiffTab(s.git.worktreeTab, selected = !staged, theme) { onToggleStaged(false) }
            }
        } else if (entry.untracked) {
            Text(
                s.git.untracked,
                style = MaterialTheme.typography.labelSmall,
                color = theme.ansi(6),
                modifier = Modifier.padding(end = 4.dp),
            )
        }
    }
}

@Composable
private fun DiffTab(label: String, selected: Boolean, theme: TerminalTheme, onClick: () -> Unit) {
    Text(
        label,
        style = MaterialTheme.typography.labelSmall,
        color = if (selected) theme.background() else theme.foreground().copy(alpha = 0.7f),
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) theme.cursor() else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp, vertical = 5.dp),
    )
}

/** 文件行：状态徽章 + 路径（重命名显示新路径）。 */
@Composable
private fun GitFileRow(entry: GitEntry, theme: TerminalTheme, s: AppStrings, onClick: () -> Unit) {
    val badgeColor = statusColor(entry.statusCode, theme)
    Row(
        Modifier.fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            entry.statusCode.trim(),
            style = MaterialTheme.typography.labelSmall,
            fontFamily = monospaceFontFamily(),
            color = badgeColor,
            modifier = Modifier
                .width(30.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(badgeColor.copy(alpha = 0.14f))
                .padding(horizontal = 4.dp, vertical = 2.dp),
            maxLines = 1,
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.displayPath,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = monospaceFontFamily(),
                color = theme.foreground(),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.renameTarget != null && entry.renameTarget != entry.path) {
                Text(
                    entry.path,
                    style = MaterialTheme.typography.labelSmall,
                    color = theme.foreground().copy(alpha = 0.45f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (entry.conflicted) {
            Text(
                s.git.conflicted,
                style = MaterialTheme.typography.labelSmall,
                color = theme.ansi(1),
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

/** 单行 diff 渲染：+ 绿 / - 红 / @@ 蓝 / 上下文灰，等宽字体。 */
@Composable
private fun GitDiffRow(line: GitDiffLine, theme: TerminalTheme) {
    val (color, bg) = when (line.kind) {
        GitDiffKind.ADD -> theme.ansi(2) to theme.ansi(2).copy(alpha = 0.10f)
        GitDiffKind.REMOVE -> theme.ansi(1) to theme.ansi(1).copy(alpha = 0.10f)
        GitDiffKind.HUNK -> theme.ansi(4) to Color.Transparent
        GitDiffKind.HEADER, GitDiffKind.NO_NEWLINE ->
            theme.foreground().copy(alpha = 0.5f) to Color.Transparent
        GitDiffKind.CONTEXT -> theme.foreground().copy(alpha = 0.8f) to Color.Transparent
    }
    Text(
        line.text,
        style = MaterialTheme.typography.labelSmall,
        fontFamily = monospaceFontFamily(),
        color = color,
        modifier = Modifier
            .fillMaxWidth()
            .background(bg)
            .padding(horizontal = 12.dp, vertical = 1.dp),
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 面板内居中提示（加载 / 错误 / 空态）。modifier 由调用方提供 weight 撑满剩余空间。 */
@Composable
private fun GitCenteredHint(
    theme: TerminalTheme,
    text: String,
    spinner: Boolean = false,
    retry: (() -> Unit)? = null,
    onClose: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val s = LocalAppStrings.current
    Box(
        modifier.fillMaxWidth().padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (spinner) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    strokeWidth = 2.dp,
                    color = theme.foreground().copy(alpha = 0.7f),
                )
                Spacer(Modifier.height(10.dp))
            }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = theme.foreground().copy(alpha = 0.7f),
            )
            if (retry != null) {
                Spacer(Modifier.height(12.dp))
                TextButton(onClick = retry) { Text(s.git.retry, color = theme.ansi(4)) }
            }
            if (onClose != null) {
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onClose) { Text(s.git.close) }
            }
        }
    }
}

/** 手绘 git 分支图标（三节点 + 两段曲线，24 viewport 归一化坐标）。 */
@Composable
fun GitBranchIcon(tint: Color, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val w = size.width
        val h = size.height
        val stroke = w * 0.11f
        fun p(fx: Float, fy: Float) = Offset(w * fx, h * fy)
        val curve = Path().apply {
            // 左上 tip → 右侧分叉点
            moveTo(p(0.333f, 0.292f).x, p(0.333f, 0.292f).y)
            cubicTo(
                p(0.333f, 0.45f).x, p(0.333f, 0.45f).y,
                p(0.52f, 0.45f).x, p(0.52f, 0.45f).y,
                p(0.708f, 0.5f).x, p(0.708f, 0.5f).y,
            )
            // 右侧分叉点 → 左下 main
            cubicTo(
                p(0.52f, 0.55f).x, p(0.52f, 0.55f).y,
                p(0.333f, 0.55f).x, p(0.333f, 0.55f).y,
                p(0.333f, 0.708f).x, p(0.333f, 0.708f).y,
            )
        }
        drawPath(curve, color = tint, style = Stroke(width = stroke, cap = StrokeCap.Round))
        drawCircle(tint, w * 0.085f, p(0.333f, 0.292f))
        drawCircle(tint, w * 0.085f, p(0.333f, 0.708f))
        drawCircle(tint, w * 0.085f, p(0.708f, 0.5f))
    }
}
