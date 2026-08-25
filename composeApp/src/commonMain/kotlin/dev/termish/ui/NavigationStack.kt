package dev.termish.ui

import androidx.compose.runtime.mutableStateListOf

/**
 * 小型、可观察的页面栈。
 *
 * AppRoot 只通过这里进入/退出一级页面，避免子页面返回时把目标写死成首页。
 * 连续导航到同一页面会去重，防止异步连接回调重复压栈。
 */
internal class NavigationStack<T>(
    initial: T,
) {
    private val entries = mutableStateListOf(initial)

    val current: T
        get() = entries.last()

    val canPop: Boolean
        get() = entries.size > 1

    val size: Int
        get() = entries.size

    fun push(value: T) {
        if (entries.last() != value) entries.add(value)
    }

    fun replace(value: T) {
        entries[entries.lastIndex] = value
    }

    fun pop(): Boolean {
        if (!canPop) return false
        entries.removeAt(entries.lastIndex)
        return true
    }

    fun reset(value: T) {
        entries.clear()
        entries.add(value)
    }

    internal fun snapshot(): List<T> = entries.toList()
}
