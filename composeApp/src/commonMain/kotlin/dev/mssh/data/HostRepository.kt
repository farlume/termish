package dev.mssh.data

import com.russhwolf.settings.Settings
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 主机与设置仓库。非秘密数据存 multiplatform-settings（JSON），
 * 秘密数据（密码/私钥）存平台安全存储（[SecretStore]）。
 */
class HostRepository(
    private val settings: Settings = Settings(),
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
) {

    private val hostsKey = "mssh.hosts.v1"
    private val settingsKey = "mssh.settings.v1"

    // ---------- 主机 ----------

    fun listHosts(): List<Host> {
        val raw = settings.getStringOrNull(hostsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<Host>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun getHost(id: String): Host? = listHosts().firstOrNull { it.id == id }

    fun upsertHost(host: Host) {
        val hosts = listHosts().toMutableList()
        val idx = hosts.indexOfFirst { it.id == host.id }
        if (idx >= 0) hosts[idx] = host else hosts.add(host)
        saveHosts(hosts)
    }

    fun deleteHost(id: String) {
        saveHosts(listHosts().filter { it.id != id })
    }

    fun touchConnected(id: String, fingerprint: String?) {
        val hosts = listHosts().map { h ->
            if (h.id == id) h.copy(
                lastConnectedAt = currentTimeMillis(),
                knownHostFingerprint = fingerprint ?: h.knownHostFingerprint,
            ) else h
        }
        saveHosts(hosts)
    }

    private fun saveHosts(hosts: List<Host>) {
        settings.putString(hostsKey, json.encodeToString(hosts))
    }

    // ---------- 设置 ----------

    fun loadSettings(): AppSettings {
        val raw = settings.getStringOrNull(settingsKey) ?: return AppSettings()
        return try {
            json.decodeFromString<AppSettings>(raw)
        } catch (e: Exception) {
            AppSettings()
        }
    }

    fun saveSettings(s: AppSettings) {
        settings.putString(settingsKey, json.encodeToString(s))
    }

    // ---------- 最近会话（连接页列表持久化） ----------

    private val recentSessionsKey = "mssh.recent_sessions.v1"

    /** 最近会话的主机 id 列表（重启后恢复连接页，状态为未连接，点击重连）。 */
    fun loadRecentSessionHostIds(): List<String> {
        val raw = settings.getStringOrNull(recentSessionsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRecentSessionHostIds(ids: List<String>) {
        settings.putString(recentSessionsKey, json.encodeToString(ids))
    }

    private fun currentTimeMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
