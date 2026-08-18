package dev.termish.data

import kotlinx.serialization.Serializable

/**
 * 标签组：全局预定义标签（片段创建时从标签组中选择，不自由输入——
 * 自由字符串会碎片化：同一语义多个写法、无法统一管理）。
 * 标签改名后所有引用它的片段自动跟随；删除时级联清理片段引用。
 */
@Serializable
data class TagGroup(
    val id: String,
    val name: String,
)

/**
 * 命令片段：跨主机复用的常用命令库。
 * 终端键盘工具栏「{}」一键插入当前输入行（不带回车，补参数后手动回车）；
 * 标签通过 [tagIds] 引用 [TagGroup]。
 */
@Serializable
data class Snippet(
    val id: String,
    val name: String,
    val content: String,
    val tagIds: List<String> = emptyList(),
    /** 创建/更新时间戳（毫秒）：列表按此倒序，最新编辑在前。 */
    val updatedAt: Long = 0,
)
