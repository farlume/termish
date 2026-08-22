package dev.termish.ui

/**
 * Git 可视化面板的数据模型与解析（纯函数，无 Compose 依赖，可单测）。
 *
 * 数据源：通过终端通道执行 `git status --porcelain=v1 --branch` /
 * `git diff --no-color`，解析远端输出为结构化 UI 数据。
 *
 * git status 单文件条目（porcelain=v1 的 XY 行）。
 */

data class GitEntry(
    val path: String,
    /** 暂存区（index）状态码；' ' = 无改动。 */
    val indexStatus: Char,
    /** 工作区（worktree）状态码；' ' = 无改动。 */
    val worktreeStatus: Char,
    /** 未跟踪（??）。 */
    val untracked: Boolean = false,
    /** 合并冲突（UU/AA/DD 等）。 */
    val conflicted: Boolean = false,
    /** 重命名/复制目标路径（R/C 时非空，porcelain 的 `old -> new`）。 */
    val renameTarget: String? = null,
    /** 未跟踪目录（porcelain 输出以 / 结尾，不递归展开）。 */
    val isDir: Boolean = false,
) {
    /** 展示用路径：重命名显示新路径。 */
    val displayPath: String get() = renameTarget ?: path

    /** 暂存区是否有改动（A/M/D/R/C 等）。 */
    val isStaged: Boolean get() = indexStatus != ' '

    /** 是否可查看 diff（未跟踪目录除外）。 */
    val diffable: Boolean get() = !(untracked && isDir)

    /** 状态码文本（徽章显示）。 */
    val statusCode: String get() = if (untracked) "??" else "${indexStatus}$worktreeStatus"
}

/** `git status --porcelain=v1 --branch` 解析结果。 */
data class GitStatusResult(
    /** 当前分支；detached HEAD / 非仓库时为 null。 */
    val branch: String?,
    /** 领先上游提交数（[ahead N]）。 */
    val ahead: Int,
    /** 落后上游提交数（[behind N]）。 */
    val behind: Int,
    val entries: List<GitEntry>,
    /** 原始输出中的错误行（fatal: / command not found 等），无则 null。 */
    val error: String?,
)

/** diff 行类型。 */
enum class GitDiffKind { HEADER, HUNK, CONTEXT, ADD, REMOVE, NO_NEWLINE }

/** 一行解析后的 diff。 */
data class GitDiffLine(
    val kind: GitDiffKind,
    val text: String,
)

/**
 * 解析 `git status --porcelain=v1 --branch` 输出。
 *
 * 终端里执行 git 命令会有命令回显等杂讯，解析只认格式行：
 * - `## branch...upstream [ahead 1, behind 2]` 分支行
 * - `XY path` 条目行（porcelain 的 C 引号路径会做 unescape）
 */
fun parseGitStatus(text: String): GitStatusResult {
    var branch: String? = null
    var ahead = 0
    var behind = 0
    val entries = ArrayList<GitEntry>()
    var error: String? = null

    for (raw in text.lineSequence()) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) continue
        // 错误识别（git 输出到 stderr，已 2>&1 合并）
        if (error == null &&
            (
                line.startsWith("fatal:") ||
                    line.startsWith("error:") ||
                    line.contains("command not found") ||
                    line.contains("not a git repository")
            )
        ) {
            error = line
            continue
        }
        if (line.startsWith("## ")) {
            // `## main...origin/main [ahead 1, behind 2]` / `## main` / `## HEAD (no branch)`
            val rest = line.removePrefix("## ").trim()
            val name = rest.substringBefore("...").trim()
            if (name.isNotEmpty() && name != "HEAD (no branch)") branch = name
            val aheadIdx = rest.indexOf("ahead ")
            if (aheadIdx >= 0) {
                ahead = rest.substring(aheadIdx + 6).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
            val behindIdx = rest.indexOf("behind ")
            if (behindIdx >= 0) {
                behind = rest.substring(behindIdx + 7).takeWhile { it.isDigit() }.toIntOrNull() ?: 0
            }
            continue
        }
        // 条目行：`XY path`（X=index，Y=worktree；` M`=仅工作区修改，
        // `??`=未跟踪，`!!`=忽略，`R  old -> new`=重命名；至少一列非空格）
        if (line.length >= 4 &&
            line[2] == ' ' &&
            (line[0] != ' ' || line[1] != ' ') &&
            (line[0] == ' ' || line[0].isLetter() || line[0] == '?' || line[0] == '!') &&
            (line[1] == ' ' || line[1].isLetter() || line[1] == '?' || line[1] == '!')
        ) {
            val x = line[0]
            val y = line[1]
            var pathPart = line.substring(3)
            val untracked = x == '?' && y == '?'
            val conflicted = (x == 'U' || y == 'U' || (x == 'A' && y == 'A') || (x == 'D' && y == 'D'))
            var renameTarget: String? = null
            // 重命名/复制：`R  old -> new`（引号路径时 `R  "old" -> "new"`）
            val arrow = pathPart.indexOf(" -> ")
            if ((x == 'R' || x == 'C') && arrow >= 0) {
                renameTarget = unescapePorcelainPath(pathPart.substring(arrow + 4))
                pathPart = pathPart.substring(0, arrow)
            }
            val path = unescapePorcelainPath(pathPart)
            if (path.isEmpty()) continue
            entries.add(
                GitEntry(
                    path = path,
                    indexStatus = if (untracked) ' ' else x,
                    worktreeStatus = if (untracked) ' ' else y,
                    untracked = untracked,
                    conflicted = conflicted,
                    renameTarget = renameTarget,
                    isDir = untracked && path.endsWith('/'),
                ),
            )
            continue
        }
        // 其他行（命令回显等杂讯）忽略
    }
    return GitStatusResult(branch, ahead, behind, entries, error)
}

/**
 * 解析 `git diff --no-color` 输出（含 `--no-index` 的新文件 diff 与二进制提示）。
 * 非 diff 格式行（命令回显等）直接跳过。
 */
fun parseGitDiff(text: String): List<GitDiffLine> {
    val lines = ArrayList<GitDiffLine>()
    for (raw in text.lineSequence()) {
        val line = raw.trimEnd('\r')
        if (line.isEmpty()) {
            lines.add(GitDiffLine(GitDiffKind.CONTEXT, ""))
            continue
        }
        val kind =
            when {
                line.startsWith("diff --git ") ||
                    line.startsWith("index ") ||
                    line.startsWith("new file mode ") ||
                    line.startsWith("deleted file mode ") ||
                    line.startsWith("old mode ") ||
                    line.startsWith("new mode ") ||
                    line.startsWith("similarity ") ||
                    line.startsWith("rename ") ||
                    line.startsWith("copy ") ||
                    line.startsWith("Binary files ") ||
                    line.startsWith("--- ") ||
                    line.startsWith("+++ ") -> GitDiffKind.HEADER
                line.startsWith("@@") -> GitDiffKind.HUNK
                line.startsWith("+") -> GitDiffKind.ADD
                line.startsWith("-") -> GitDiffKind.REMOVE
                line.startsWith("\\ No newline") -> GitDiffKind.NO_NEWLINE
                line.startsWith(" ") -> GitDiffKind.CONTEXT
                else -> continue // 杂讯（命令回显等）跳过
            }
        lines.add(GitDiffLine(kind, line))
    }
    return lines
}

/** shell 单引号转义（用于把 git 输出的路径安全拼进远端命令）。 */
fun shellQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

/**
 * porcelain 输出的 C 引号路径 unescape：`"a\tb"` → `a<TAB>b`。
 * 只有含特殊字符的路径才被引号包裹；普通路径原样返回。
 */
fun unescapePorcelainPath(p: String): String {
    if (p.length < 2 || p.first() != '"' || p.last() != '"') return p
    val inner = p.substring(1, p.length - 1)
    val sb = StringBuilder(inner.length)
    var i = 0
    while (i < inner.length) {
        val c = inner[i]
        if (c != '\\' || i + 1 >= inner.length) {
            sb.append(c)
            i++
            continue
        }
        val n = inner[i + 1]
        when (n) {
            'a' -> {
                sb.append('\u0007')
                i += 2
            }
            'b' -> {
                sb.append('\b')
                i += 2
            }
            't' -> {
                sb.append('\t')
                i += 2
            }
            'n' -> {
                sb.append('\n')
                i += 2
            }
            'v' -> {
                sb.append('\u000b')
                i += 2
            }
            'f' -> {
                sb.append('\u000c')
                i += 2
            }
            'r' -> {
                sb.append('\r')
                i += 2
            }
            '"' -> {
                sb.append('"')
                i += 2
            }
            '\\' -> {
                sb.append('\\')
                i += 2
            }
            in '0'..'7' -> {
                // 八进制转义 \ooo（最多 3 位）
                var v = n - '0'
                var j = i + 2
                var digits = 1
                while (j < inner.length && digits < 3 && inner[j] in '0'..'7') {
                    v = v * 8 + (inner[j] - '0')
                    j++
                    digits++
                }
                sb.append(v.toChar())
                i = j
            }
            else -> {
                sb.append(n)
                i += 2
            }
        }
    }
    return sb.toString()
}
