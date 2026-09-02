package dev.termish.ui

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 页面离开后短暂保留昂贵资源；超时仍未重新获取才释放。
 *
 * 所有调用都由同一个 UI scope 串行执行，不需要额外加锁。签名用于在主机配置或
 * 凭据变化时立即淘汰旧资源，避免复用过期连接。
 */
internal class IdleResourcePool<K : Any, S, V : Any>(
    private val scope: CoroutineScope,
    private val idleTimeoutMillis: Long,
    private val closeResource: (V) -> Unit,
    private val canCloseResource: (V) -> Boolean = { true },
) {
    private data class Entry<S, V>(
        val signature: S,
        val value: V,
        var closeJob: Job? = null,
    )

    private val entries = mutableMapOf<K, Entry<S, V>>()

    fun acquire(
        key: K,
        signature: S,
        create: () -> V,
    ): V {
        val existing = entries[key]
        if (existing != null && existing.signature == signature) {
            existing.closeJob?.cancel()
            existing.closeJob = null
            return existing.value
        }
        existing?.let(::closeEntry)
        return create().also { entries[key] = Entry(signature, it) }
    }

    fun release(key: K) {
        val entry = entries[key] ?: return
        entry.closeJob?.cancel()
        entry.closeJob =
            scope.launch {
                delay(idleTimeoutMillis)
                while (entries[key] === entry && !canCloseResource(entry.value)) {
                    delay(idleTimeoutMillis)
                }
                if (entries[key] === entry) {
                    entries.remove(key)
                    closeEntry(entry)
                }
            }
    }

    /** 显式结束某个资源（连接页关闭、删除主机），不等待闲置计时。 */
    fun remove(key: K) {
        entries.remove(key)?.let(::closeEntry)
    }

    fun closeAll() {
        val current = entries.values.toList()
        entries.clear()
        current.forEach(::closeEntry)
    }

    private fun closeEntry(entry: Entry<S, V>) {
        entry.closeJob?.cancel()
        entry.closeJob = null
        runCatching { closeResource(entry.value) }
    }
}
