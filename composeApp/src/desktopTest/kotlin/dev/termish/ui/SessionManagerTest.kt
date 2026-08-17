package dev.termish.ui

import com.russhwolf.settings.PropertiesSettings
import dev.termish.data.Host
import dev.termish.data.HostAuthMethod
import dev.termish.data.HostRepository
import dev.termish.data.ConnectionMode
import java.util.Properties
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain

/** SessionManager：会话增删、最近会话持久化与恢复、主机删除连带清理。 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManagerTest {

    private val mainDispatcher = StandardTestDispatcher()

    @BeforeTest
    fun setupMain() {
        Dispatchers.setMain(mainDispatcher)
    }

    @AfterTest
    fun tearDownMain() {
        Dispatchers.resetMain()
    }

    private fun repo() = HostRepository(PropertiesSettings(Properties()))

    private fun host(id: String) =
        Host(id = id, name = id, hostname = "$id.example.com", username = "root")

    @Test
    fun openAddsSessionAndPersistsRecent() {
        val r = repo()
        val m = SessionManager(r)

        m.open(host("a"), autoReconnect = true)

        assertEquals(1, m.sessions.size)
        assertEquals(listOf("a"), r.loadRecentSessionHostIds())
        m.sessions.forEach { it.destroy() }
    }

    @Test
    fun restoreRecentRebuildsDisconnectedSessions() {
        val r = repo()
        r.upsertHost(host("a"))
        val m1 = SessionManager(r)
        m1.open(host("a"), autoReconnect = true)
        m1.sessions.forEach { it.destroy() }

        val m2 = SessionManager(r)
        m2.restoreRecent(r.listHosts(), autoReconnect = false)

        assertEquals(1, m2.sessions.size)
        assertEquals(ConnStatus.IDLE, m2.sessions.single().status)
        assertEquals("a", m2.sessions.single().host.id)
        m2.sessions.forEach { it.destroy() }
    }

    @Test
    fun removeDestroysController() {
        val r = repo()
        val m = SessionManager(r)
        val c = m.open(host("a"), autoReconnect = true)
        assertEquals(1, m.sessions.size)

        m.remove(c)

        assertTrue(m.sessions.isEmpty())
        assertTrue(r.loadRecentSessionHostIds().isEmpty())
    }

    @Test
    fun closeForHostDestroysAllSessions() {
        val r = repo()
        val m = SessionManager(r)
        m.open(host("a"), autoReconnect = true)
        m.open(host("a"), autoReconnect = true)
        assertEquals(2, m.sessions.size)

        m.closeForHost("a")

        assertTrue(m.sessions.isEmpty())
        assertTrue(r.loadRecentSessionHostIds().isEmpty())
    }

    @Test
    fun signatureForChangesWhenConfigChanges() {
        val m = SessionManager(repo())
        val h = host("a")
        val base = m.signatureFor(h)

        // 连接参数任一变化 → 签名变化（旧会话应判为过期）
        assertNotEquals(base, m.signatureFor(h.copy(hostname = "other.example.com")))
        assertNotEquals(base, m.signatureFor(h.copy(username = "other")))
        assertNotEquals(base, m.signatureFor(h.copy(port = 2222)))
        assertNotEquals(base, m.signatureFor(h.copy(authMethod = HostAuthMethod.PRIVATE_KEY)))
        assertNotEquals(base, m.signatureFor(h.copy(startupCommand = "tmux new -A")))
        assertNotEquals(base, m.signatureFor(h.copy(connectionMode = ConnectionMode.MOSH)))
    }

    @Test
    fun signatureForIsDeterministic() {
        val m = SessionManager(repo())
        val h = host("a")
        assertEquals(m.signatureFor(h), m.signatureFor(h))
    }
}
