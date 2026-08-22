package dev.termish.data

import com.russhwolf.settings.Settings
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * 主机与设置仓库。非秘密数据存 multiplatform-settings（JSON），
 * 秘密数据（密码/私钥）存平台安全存储（[SecretStore]）。
 */
class HostRepository(
    private val settings: Settings = Settings(),
    private val json: Json =
        Json {
            ignoreUnknownKeys = true
            encodeDefaults = true
        },
) {
    private val hostsKey = "termish.hosts.v1"
    private val settingsKey = "termish.settings.v1"
    private val tagGroupsKey = "termish.tag_groups.v1"
    private val snippetsKey = "termish.snippets.v1"

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

    fun touchConnected(
        id: String,
        fingerprint: String?,
    ) {
        patchHost(id) {
            it.copy(
                lastConnectedAt = currentTimeMillis(),
                knownHostFingerprint = fingerprint ?: it.knownHostFingerprint,
            )
        }
    }

    /** 仅记录主机密钥指纹（TOFU 弹窗点信任后立即调用，与后续认证成败解耦）。 */
    fun recordHostKey(
        id: String,
        fingerprint: String,
    ) {
        patchHost(id) { it.copy(knownHostFingerprint = fingerprint) }
    }

    /**
     * 部分字段更新：以仓库最新值为基础变换，不整条覆盖——控制器持有的
     * host 是创建时快照，连接期间其它字段可能已更新（TOFU 指纹、
     * lastConnectedAt），用快照 upsert 会把它们抹掉（新主机指纹丢失
     * → 每次重连重复弹确认窗的根因）。
     */
    fun patchHost(
        id: String,
        transform: (Host) -> Host,
    ) {
        val hosts = listHosts().map { h -> if (h.id == id) transform(h) else h }
        saveHosts(hosts)
    }

    private fun saveHosts(hosts: List<Host>) {
        settings.putString(hostsKey, json.encodeToString(hosts))
    }

    // ---------- 标签组（全局，片段引用） ----------

    fun listTagGroups(): List<TagGroup> {
        val raw = settings.getStringOrNull(tagGroupsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<TagGroup>>(raw)
        } catch (e: Exception) {
            backupCorrupt(tagGroupsKey, raw)
            emptyList()
        }
    }

    fun upsertTagGroup(group: TagGroup) {
        val groups = listTagGroups().toMutableList()
        val idx = groups.indexOfFirst { it.id == group.id }
        if (idx >= 0) groups[idx] = group else groups.add(group)
        settings.putString(tagGroupsKey, json.encodeToString(groups))
    }

    /** 删除标签组：级联清理所有片段对该标签的引用（不删片段本身）。 */
    fun deleteTagGroup(id: String) {
        settings.putString(tagGroupsKey, json.encodeToString(listTagGroups().filter { it.id != id }))
        // 级联清理片段引用（仅当确有引用被清理时才回写，避免无谓写入）
        val before = listSnippets()
        val cleaned = before.map { s -> if (id in s.tagIds) s.copy(tagIds = s.tagIds - id) else s }
        if (cleaned != before) {
            settings.putString(snippetsKey, json.encodeToString(cleaned))
        }
    }

    // ---------- 命令片段（全局库） ----------

    fun listSnippets(): List<Snippet> {
        val raw = settings.getStringOrNull(snippetsKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<Snippet>>(raw)
        } catch (e: Exception) {
            backupCorrupt(snippetsKey, raw)
            emptyList()
        }
    }

    fun getSnippet(id: String): Snippet? = listSnippets().firstOrNull { it.id == id }

    fun upsertSnippet(snippet: Snippet) {
        val snippets = listSnippets().toMutableList()
        val idx = snippets.indexOfFirst { it.id == snippet.id }
        if (idx >= 0) snippets[idx] = snippet else snippets.add(snippet)
        settings.putString(snippetsKey, json.encodeToString(snippets))
    }

    fun deleteSnippet(id: String) {
        settings.putString(snippetsKey, json.encodeToString(listSnippets().filter { it.id != id }))
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

    // ---------- 目录收藏（SFTP 文件管理器，按主机持久化） ----------

    fun loadFavorites(hostId: String): List<String> {
        val raw = settings.getStringOrNull("termish.favorites.$hostId") ?: return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveFavorites(
        hostId: String,
        paths: List<String>,
    ) {
        settings.putString("termish.favorites.$hostId", json.encodeToString(paths))
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

    /** 最近 SFTP 会话：主机 id + 浏览路径（v2 起带路径，进程重启后恢复到上次目录）。 */
    @Serializable
    data class RecentSftpEntry(
        val hostId: String,
        val path: String = "",
    )

    private val recentSftpKey = "termish.recent_sftp.v2"

    fun loadRecentSftpEntries(): List<RecentSftpEntry> {
        val raw = settings.getStringOrNull(recentSftpKey) ?: return emptyList()
        return try {
            json.decodeFromString<List<RecentSftpEntry>>(raw)
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveRecentSftpEntries(entries: List<RecentSftpEntry>) {
        settings.putString(recentSftpKey, json.encodeToString(entries))
    }

    /** 解析失败时把原始内容备份到独立 key（最多保留 3 份），避免静默丢数据。 */
    private fun backupCorrupt(
        key: String,
        raw: String,
    ) {
        try {
            settings.putString("$key.corrupt.${currentTimeMillis()}", raw)
            // 超出 3 份时删最旧的（key 含时间戳，字典序即时间序）
            val corruptKeys = settings.keys.filter { it.startsWith("$key.corrupt.") }.sorted()
            corruptKeys.dropLast(3).forEach { settings.remove(it) }
        } catch (_: Exception) {
        }
    }

    private fun currentTimeMillis(): Long =
        kotlinx.datetime.Clock.System
            .now()
            .toEpochMilliseconds()
}
