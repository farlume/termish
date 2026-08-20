package dev.termish.ui

import dev.termish.data.Host
import kotlin.test.Test
import kotlin.test.assertEquals

/** 终端页会话 tab 标题逻辑：优先自定义名称，未起名回退 user@host。 */
class TerminalScreenTest {

    private fun host(name: String, hostname: String, username: String = "root") = Host(
        id = "h1",
        name = name,
        hostname = hostname,
        username = username,
    )

    @Test
    fun customNameWins() {
        assertEquals("我的服务器", sessionTabTitle(host("我的服务器", "192.168.1.10")))
        assertEquals("web-prod", sessionTabTitle(host("web-prod", "prod.example.com")))
    }

    @Test
    fun unnamedFallsBackToUserAtHost() {
        // 未起名：保存时 name 回退为 hostname → 显示 user@host（信息量更大）
        assertEquals("root@192.168.1.10", sessionTabTitle(host("192.168.1.10", "192.168.1.10")))
        assertEquals("alice@nas.local", sessionTabTitle(host("nas.local", "nas.local", username = "alice")))
    }

    @Test
    fun nameEqualToHostnameButDifferentUserShowsUserAtHost() {
        // 名称与主机名相同但用户不同：name 视为未起名，仍显示 user@host
        assertEquals("root@devbox", sessionTabTitle(host("devbox", "devbox")))
    }

    @Test
    fun blankNameFallsBack() {
        assertEquals("root@server", sessionTabTitle(host("", "server")))
    }

    // ---------- 同主机会话序号（当前 tab 列表位置：1、2、3…删除自动重排） ----------

    @Test
    fun seqShownWhenMultipleSessions() {
        val h = host("我的服务器", "192.168.1.10")
        assertEquals("我的服务器 (1)", sessionTabTitle(h, seq = 1, showSeq = true))
        assertEquals("我的服务器 (2)", sessionTabTitle(h, seq = 2, showSeq = true))
    }

    @Test
    fun seqHiddenForSingleSession() {
        // 同主机只有一个会话时不显示括号（默认参数）
        assertEquals("我的服务器", sessionTabTitle(host("我的服务器", "192.168.1.10"), seq = 1))
        assertEquals("root@server", sessionTabTitle(host("", "server"), seq = 3))
    }

    @Test
    fun seqAppendsAfterFallbackTitle() {
        val h = host("nas.local", "nas.local", username = "alice")
        assertEquals("alice@nas.local (4)", sessionTabTitle(h, seq = 4, showSeq = true))
    }
}
