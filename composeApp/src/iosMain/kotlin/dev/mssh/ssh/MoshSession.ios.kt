package dev.mssh.ssh

/**
 * iOS Mosh 集成待完成：需要先交叉编译 iOS 版 mosh-client 并走 posix_spawn + openpty。
 * 目前占位，选择 Mosh 模式时抛出明确错误。
 */
suspend actual fun createMoshClient(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    onOutput: (ByteArray) -> Unit,
    onExit: () -> Unit,
): MoshSession = throw UnsupportedOperationException("iOS Mosh 尚未集成（需要 iOS 版 mosh-client）")
