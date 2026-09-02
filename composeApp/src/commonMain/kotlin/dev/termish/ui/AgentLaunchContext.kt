package dev.termish.ui

private const val MAX_AGENT_HANDOFF_TEXT_CHARS = 12_000
private const val MAX_AGENT_HANDOFF_PATHS = 100

/** 从终端/SFTP 交给 Agent 的一次性上下文，不写入主机或 Agent 默认配置。 */
data class AgentLaunchContext(
    val prompt: String,
    val directory: String? = null,
)

/**
 * 终端输出通常尾部最有价值（错误栈、退出状态），超长时保留末尾并明确标记截断。
 * [wrapPrompt] 负责本地化，同时应声明内容是不可信数据，避免把远端输出当作指令。
 */
internal fun terminalAgentLaunchContext(
    selection: String,
    directory: String?,
    wrapPrompt: (String) -> String,
): AgentLaunchContext? {
    val normalized = selection.trim()
    if (normalized.isEmpty()) return null
    val excerpt =
        if (normalized.length <= MAX_AGENT_HANDOFF_TEXT_CHARS) {
            normalized
        } else {
            "…\n" + normalized.takeLast(MAX_AGENT_HANDOFF_TEXT_CHARS)
        }
    return AgentLaunchContext(
        prompt = wrapPrompt(excerpt),
        directory = directory?.trim()?.takeIf(String::isNotEmpty),
    )
}

/**
 * SFTP 文件位于 Agent 所在的同一远端主机，只传绝对路径引用，不经手机下载再上传。
 * 去重和数量/文本上限避免一次多选制造超大 prompt。
 */
internal fun sftpAgentLaunchContext(
    paths: List<String>,
    directory: String,
    wrapPrompt: (String) -> String,
): AgentLaunchContext? {
    val unique =
        paths
            .asSequence()
            .map(String::trim)
            .filter(String::isNotEmpty)
            .distinct()
            .take(MAX_AGENT_HANDOFF_PATHS)
            .toList()
    if (unique.isEmpty()) return null
    val pathList = unique.joinToString("\n") { "- $it" }.take(MAX_AGENT_HANDOFF_TEXT_CHARS)
    return AgentLaunchContext(
        prompt = wrapPrompt(pathList),
        directory = directory.trim().takeIf(String::isNotEmpty),
    )
}
