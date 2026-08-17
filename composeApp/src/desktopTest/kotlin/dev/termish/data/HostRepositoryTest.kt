package dev.termish.data

import com.russhwolf.settings.PropertiesSettings
import java.util.Properties
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** HostRepository：CRUD、损坏数据备份、最近会话、设置持久化。 */
class HostRepositoryTest {

    private fun repo() = HostRepository(PropertiesSettings(Properties()))

    private fun host(id: String, name: String = "host-$id") =
        Host(id = id, name = name, hostname = "$id.example.com", username = "root")

    @Test
    fun hostsCrud() {
        val r = repo()
        assertTrue(r.listHosts().isEmpty())

        r.upsertHost(host("a"))
        r.upsertHost(host("b"))
        assertEquals(listOf("a", "b"), r.listHosts().map { it.id })

        // 同 id 覆盖更新
        r.upsertHost(host("a", name = "renamed"))
        assertEquals("renamed", r.getHost("a")!!.name)
        assertEquals(2, r.listHosts().size)

        r.deleteHost("a")
        assertNull(r.getHost("a"))
        assertEquals(listOf("b"), r.listHosts().map { it.id })
    }

    @Test
    fun touchConnectedRecordsTimeAndFingerprint() {
        val r = repo()
        r.upsertHost(host("a"))
        val before = r.getHost("a")!!.lastConnectedAt

        r.touchConnected("a", "SHA256:f")
        val after = r.getHost("a")!!
        assertTrue(after.lastConnectedAt >= before)
        assertEquals("SHA256:f", after.knownHostFingerprint)

        // null 指纹不覆盖已有值
        r.touchConnected("a", null)
        assertEquals("SHA256:f", r.getHost("a")!!.knownHostFingerprint)
    }

    @Test
    fun corruptHostsDataBacksUpInsteadOfOverwriting() {
        val settings = PropertiesSettings(Properties())
        settings.putString("termish.hosts.v1", "{not-json")
        val r = HostRepository(settings)

        assertTrue(r.listHosts().isEmpty())
        val corruptKeys = settings.keys.filter { it.startsWith("termish.hosts.v1.corrupt.") }
        assertTrue(corruptKeys.isNotEmpty())
        assertTrue(corruptKeys.map { settings.getStringOrNull(it) }.contains("{not-json"))

        // 后续写入不丢原始损坏串（每次失败解码追加一份备份，上限 3 份）
        r.upsertHost(host("a"))
        assertEquals(1, r.listHosts().size)
        val values = settings.keys.filter { it.startsWith("termish.hosts.v1.corrupt.") }
            .map { settings.getStringOrNull(it) }
        assertTrue(values.contains("{not-json"))
    }

    @Test
    fun corruptSettingsFallBackToDefaults() {
        val settings = PropertiesSettings(Properties())
        settings.putString("termish.settings.v1", "{broken")
        val r = HostRepository(settings)

        assertEquals(AppSettings(), r.loadSettings())
        assertTrue(settings.keys.any { it.startsWith("termish.settings.v1.corrupt.") })
    }

    @Test
    fun settingsRoundtrip() {
        val r = repo()
        assertEquals(AppSettings(), r.loadSettings())

        r.saveSettings(
            AppSettings(
                theme = ThemeMode.LIGHT,
                terminalFontSize = 16,
                autoReconnect = false,
            ),
        )
        val loaded = r.loadSettings()
        assertEquals(ThemeMode.LIGHT, loaded.theme)
        assertEquals(16, loaded.terminalFontSize)
        assertTrue(!loaded.autoReconnect)
    }

    @Test
    fun recentSessionsRoundtrip() {
        val r = repo()
        assertTrue(r.loadRecentSessionHostIds().isEmpty())

        r.saveRecentSessionHostIds(listOf("a", "b"))
        assertEquals(listOf("a", "b"), r.loadRecentSessionHostIds())

        r.saveRecentSessionHostIds(emptyList())
        assertTrue(r.loadRecentSessionHostIds().isEmpty())
    }

    @Test
    fun recentSftpRoundtripWithPath() {
        val r = repo()
        assertTrue(r.loadRecentSftpEntries().isEmpty())

        // 路径随条目持久化：杀 App 重进后恢复到上次浏览目录
        r.saveRecentSftpEntries(
            listOf(
                HostRepository.RecentSftpEntry("host-a", "/var/www"),
                HostRepository.RecentSftpEntry("host-b"), // 默认路径为空
            )
        )
        val loaded = r.loadRecentSftpEntries()
        assertEquals(2, loaded.size)
        assertEquals("host-a", loaded[0].hostId)
        assertEquals("/var/www", loaded[0].path)
        assertEquals("", loaded[1].path)

        r.saveRecentSftpEntries(emptyList())
        assertTrue(r.loadRecentSftpEntries().isEmpty())
    }
}
