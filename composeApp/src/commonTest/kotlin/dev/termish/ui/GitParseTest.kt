package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Git 面板解析器（git status --porcelain / git diff）单元测试。 */
class GitParseTest {
    @Test
    fun statusMixedStates() {
        val out =
            """
            ## main...origin/main [ahead 1, behind 2]
             M app/src/main.kt
            M  app/src/main.kt
            MM app/src/main.kt
            A  new-file.kt
             D old-file.kt
            R  old.kt -> new.kt
            ?? untracked.txt
            ?? notes/
            UU conflict.txt
            """.trimIndent()
        val res = parseGitStatus(out)
        assertEquals("main", res.branch)
        assertEquals(1, res.ahead)
        assertEquals(2, res.behind)
        assertNull(res.error)
        assertEquals(9, res.entries.size)

        // ` M`：仅工作区修改（首字符空格，易漏）
        val wtOnly = res.entries[0]
        assertEquals(' ', wtOnly.indexStatus)
        assertEquals('M', wtOnly.worktreeStatus)
        assertFalse(wtOnly.isStaged)

        // `M `：仅暂存区
        val idxOnly = res.entries[1]
        assertEquals('M', idxOnly.indexStatus)
        assertEquals(' ', idxOnly.worktreeStatus)
        assertTrue(idxOnly.isStaged)

        // `MM`：两区都改 → diff 页可切换
        val both = res.entries[2]
        assertEquals("MM", both.statusCode)
        assertTrue(both.isStaged)

        // 新增
        assertEquals("A ", res.entries[3].statusCode)
        assertTrue(res.entries[3].isStaged)

        // 删除（工作区）
        assertEquals('D', res.entries[4].worktreeStatus)

        // 重命名：displayPath 是新路径
        val rename = res.entries[5]
        assertEquals("new.kt", rename.displayPath)
        assertEquals("old.kt", rename.path)
        assertTrue(rename.isStaged)

        // 未跟踪
        val untracked = res.entries[6]
        assertTrue(untracked.untracked)
        assertEquals("??", untracked.statusCode)
        assertFalse(untracked.isStaged)
        assertTrue(untracked.diffable)

        // 未跟踪目录
        val dir = res.entries[7]
        assertTrue(dir.isDir)
        assertFalse(dir.diffable)

        // 冲突
        assertTrue(res.entries[8].conflicted)
    }

    @Test
    fun statusBranchVariants() {
        // 无 upstream / 无 ahead
        val plain = parseGitStatus("## feature\n M a.kt\n")
        assertEquals("feature", plain.branch)
        assertEquals(0, plain.ahead)
        assertEquals(0, plain.behind)

        // detached HEAD
        val detached = parseGitStatus("## HEAD (no branch)\n")
        assertNull(detached.branch)
        assertTrue(detached.entries.isEmpty())
        assertNull(detached.error)
    }

    @Test
    fun statusQuotedPathAndNoise() {
        val out =
            """
            user@host:~$ git -c color.ui=false status --porcelain=v1 --branch
            ## main
             M "a b.txt"
            ?? "weird\"name.txt"
            some random shell output
            """.trimIndent()
        val res = parseGitStatus(out)
        assertEquals(2, res.entries.size)
        assertEquals("a b.txt", res.entries[0].path)
        assertEquals("weird\"name.txt", res.entries[1].path)
    }

    @Test
    fun statusErrors() {
        val notRepo = parseGitStatus("fatal: not a git repository (or any of the parent directories): .git\n")
        assertTrue(notRepo.error!!.contains("not a git repository"))
        assertTrue(notRepo.entries.isEmpty())

        val notFound = parseGitStatus("git: command not found\n")
        assertTrue(notFound.error!!.contains("command not found"))

        // 干净工作区：只有分支行
        val clean = parseGitStatus("## main\n")
        assertNull(clean.error)
        assertTrue(clean.entries.isEmpty())
    }

    @Test
    fun diffStandard() {
        val out =
            """
            diff --git a/app/src/main.kt b/app/src/main.kt
            index 1234567..89abcde 100644
            --- a/app/src/main.kt
            +++ b/app/src/main.kt
            @@ -10,6 +10,8 @@ public class Main {
             context line
            +added line
            -removed line
            \ No newline at end of file
            """.trimIndent()
        val lines = parseGitDiff(out)
        assertEquals(9, lines.size)
        assertEquals(GitDiffKind.HEADER, lines[0].kind)
        assertEquals(GitDiffKind.HEADER, lines[1].kind)
        assertEquals(GitDiffKind.HEADER, lines[3].kind) // +++ b/...
        assertEquals(GitDiffKind.HUNK, lines[4].kind)
        assertEquals(GitDiffKind.CONTEXT, lines[5].kind)
        assertEquals(GitDiffKind.ADD, lines[6].kind)
        assertEquals("+added line", lines[6].text)
        assertEquals(GitDiffKind.REMOVE, lines[7].kind)
        assertEquals(GitDiffKind.NO_NEWLINE, lines[8].kind)
    }

    @Test
    fun diffBinaryAndNewFile() {
        val bin =
            parseGitDiff("diff --git a/x.bin b/x.bin\nindex 111..222 100644\nBinary files a/x.bin and b/x.bin differ")
        assertEquals(3, bin.size)
        assertTrue(bin.all { it.kind == GitDiffKind.HEADER })

        // --no-index 新文件 diff
        val raw =
            """
            diff --git a//dev/null b/new.kt
            new file mode 100644
            index 0000000..abc1234
            --- /dev/null
            +++ b/new.kt
            @@ -0,0 +1,2 @@
            +fun main() {}
            +println("hi")
            """.trimIndent()
        val newFile = parseGitDiff(raw)
        assertEquals(GitDiffKind.ADD, newFile.last().kind)
        assertTrue(newFile.size == 8, "got ${newFile.map { it.kind.name + "|" + it.text }}")
    }

    @Test
    fun diffIgnoresShellNoise() {
        val out =
            """
            user@host:~/proj$ git diff --no-color -- app/src/main.kt
            diff --git a/app/src/main.kt b/app/src/main.kt
            @@ -1 +1 @@
            -old
            +new
            """.trimIndent()
        val lines = parseGitDiff(out)
        // 回显行被跳过
        assertEquals(4, lines.size)
    }

    @Test
    fun shellQuoteEscapes() {
        assertEquals("'plain'", shellQuote("plain"))
        assertEquals("'a b'", shellQuote("a b"))
        assertEquals("'it'\\''s'", shellQuote("it's"))
    }

    @Test
    fun unescapePorcelain() {
        assertEquals("plain", unescapePorcelainPath("plain"))
        assertEquals("a b.txt", unescapePorcelainPath("\"a b.txt\""))
        assertEquals("tab\there", unescapePorcelainPath("\"tab\\there\""))
        assertEquals("quote\"inside", unescapePorcelainPath("\"quote\\\"inside\""))
        // 八进制转义 \ooo
        assertEquals("a\u001bb", unescapePorcelainPath("\"a\\033b\""))
        assertEquals("", unescapePorcelainPath("\"\""))
    }
}
