package dev.termish.data

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

    private val hostsKey = "termish.hosts.v1"
    private val settingsKey = "termish.settings.v1"

    // ---------- 主机 ----------

    fun listHosts(): List<Host> {
        val raw = settings.getStringOrNull(hostsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<Host>>(raw)
        } catch (e: Exception) {
            // 解析失败不清空数据：备份原始串供恢复/排查，避免后续 upsert 把全部主机覆盖丢失
            backupCorrupt(hostsKey, raw)
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

    /** 仅记录主机密钥指纹（TOFU 弹窗点信任后立即调用，与后续认证成败解耦）。 */
    fun recordHostKey(id: String, fingerprint: String) {
        val hosts = listHosts().map { h ->
            if (h.id == id) h.copy(knownHostFingerprint = fingerprint) else h
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
            backupCorrupt(settingsKey, raw)
            AppSettings()
        }
    }

    fun saveSettings(s: AppSettings) {
        settings.putString(settingsKey, json.encodeToString(s))
    }

    // ---------- 最近会话（连接页列表持久化） ----------

    private val recentSessionsKey = "termish.recent_sessions.v1"

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

    /** 解析失败时把原始内容备份到独立 key（最多保留 3 份），避免静默丢数据。 */
    private fun backupCorrupt(key: String, raw: String) {
        try {
            settings.putString("$key.corrupt.${currentTimeMillis()}", raw)
            // 超出 3 份时删最旧的（key 含时间戳，字典序即时间序）
            val corruptKeys = settings.keys.filter { it.startsWith("$key.corrupt.") }.sorted()
            corruptKeys.dropLast(3).forEach { settings.remove(it) }
        } catch (_: Exception) {
        }
    }

    private fun currentTimeMillis(): Long = kotlinx.datetime.Clock.System.now().toEpochMilliseconds()
}
