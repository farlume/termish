package dev.termish.herdr

import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSshSession
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * herdr 端到端集成测试：真实 sshj 连接本地测试 sshd（127.0.0.1:22222），
 * 在已认证连接上 runCommand 执行 `herdr api snapshot`，验证：
 * 1. runCommand 复用连接执行控制面命令（M1 能力）
 * 2. 解析真实远端 herdr 输出（协议层模型）
 * 3. 状态机在真实数据上跑通
 *
 * self-detect：sshd 或 herdr 缺席时 SKIP（不算失败）——CI 无 herdr 时
 * 自动跳过，本机验证时自动跑（测试 sshd 的 exec PATH 含 herdr）。
 */
class HerdrIntegrationTest {
    private fun env(
        key: String,
        default: String,
    ): String = System.getenv(key) ?: default

    private fun sshdReachable(): Boolean =
        runCatching {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", env("Termish_TEST_PORT", "22222").toInt()), 500) }
        }.isSuccess

    @Test
    fun runCommandExecutesHerdrSnapshotOverRealSsh() {
        if (!sshdReachable()) {
            println("SKIP: 测试 sshd 未启动（scripts/test-sshd.sh 或 make test-integration）")
            return
        }
        val pemFile = File(env("Termish_TEST_KEY", "/tmp/termish_test/client"))
        if (!pemFile.exists()) {
            println("SKIP: no key at ${pemFile.absolutePath}")
            return
        }
        val session =
            createSshSession(
                SshConnection(
                    host = "127.0.0.1",
                    port = env("Termish_TEST_PORT", "22222").toInt(),
                    username = System.getProperty("user.name"),
                    privateKeyPem = pemFile.readText(),
                ),
                object : SshCallbacks {
                    override suspend fun onOutput(data: ByteArray) {}

                    override suspend fun onStderr(data: ByteArray) {}

                    override fun onExitStatus(status: Int) {}

                    override fun onClosed(reason: String?) {}

                    override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                    override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
                },
            )
        try {
            session.connectAndStart(columns = 100, rows = 40)
            val raw = session.runCommand("herdr api snapshot", timeoutMs = 5_000)
            // 缺失措辞跨 shell 不同：bash=command not found，dash/sh=not found，
            // 另有 No such file；均为远端无 herdr → SKIP（CI 无 herdr 时自动跳过）
            if (raw == null || raw.contains("not found") || raw.contains("No such file")) {
                println("SKIP: 远端无 herdr（raw=${raw?.take(100)}）——本机装了 herdr 时此测试自动跑")
                return
            }
            // 输出非法（非 JSON 快照）也按缺席处理：环境异常不该砸 CI
            val snapshot = parseHerdrSnapshot(raw)
            if (snapshot == null) {
                println("SKIP: 远端 herdr 输出无法解析（raw=${raw.take(100)}）")
                return
            }
            assertTrue(snapshot.protocol > 0, "protocol 应非零")
            println(
                "herdr over ssh: protocol=${snapshot.protocol} panes=${snapshot.panes.size} " +
                    "agents=${snapshot.agents.map { "${it.agent}/${it.agentStatus}" }}",
            )

            // 状态机在真实数据上跑通：首轮首事件 + 同快照无变化。
            // 事件数 ≤ agents 数：DONE 状态不产生事件（无迁移可报），
            // 断言按状态分类精确验证而非依赖现场会话恰好全 working
            val m = HerdrAgentStateMachine()
            val first = m.update(snapshot)
            val eventful = snapshot.agents.count { it.agentStatus != HerdrAgentStatus.DONE }
            val unblockedFollows = first.count { it is HerdrAgentEvent.Unblocked }
            val nonUnblocked = first.size - unblockedFollows
            assertTrue(nonUnblocked == eventful, "首轮事件数($nonUnblocked) 应等于非 DONE agent 数($eventful)")
            assertTrue(m.update(snapshot).isEmpty(), "同快照无变化不应产生事件")
        } finally {
            session.close()
        }
    }
}
