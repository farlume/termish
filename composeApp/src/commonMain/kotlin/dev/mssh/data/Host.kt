package dev.mssh.data

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** 主机的认证方式。 */
@Serializable
enum class HostAuthMethod {
    PASSWORD,
    PRIVATE_KEY,
    KEY_OR_PASSWORD,
}

@Serializable
data class QuickCommand(
    val id: String,
    val label: String,
    val command: String,
)

/** 一个 SSH 主机配置（不含秘密信息）。 */
@Serializable
data class Host(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "root",
    val authMethod: HostAuthMethod = HostAuthMethod.PASSWORD,
    val tags: List<String> = emptyList(),
    val quickCommands: List<QuickCommand> = emptyList(),
    val colorIndex: Int = 0,
    val createdAt: Long = 0L,
    val lastConnectedAt: Long = 0L,
    val knownHostFingerprint: String? = null,
)

@Serializable
enum class ThemeMode { DARK, LIGHT, SYSTEM }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    val terminalThemeIndex: Int = 0,
    val fontSize: Int = 14,
    val terminalFontSize: Int = 8,
    /** 目标终端列数：>0 时忽略 terminalFontSize，按屏幕宽度自动反算字号（对齐桌面终端 120×30 这类体验）。 */
    val terminalTargetCols: Int = 0,
    val keyboardToolbarVisible: Boolean = true,
    val keepaliveSeconds: Int = 30,
    val cursorBlink: Boolean = true,
    val hapticFeedback: Boolean = true,
    /** 首次连接未知主机时提示确认（TOFU）。 */
    val verifyHostKeyOnFirstUse: Boolean = true,
)

/** 生成一个随机 ID（UUID v4 风格）。 */
internal fun newId(): String {
    val bytes = ByteArray(16) { Random.nextBytes(1)[0] }
    // version 4, variant 10
    bytes[6] = ((bytes[6].toInt() and 0x0f) or 0x40).toByte()
    bytes[8] = ((bytes[8].toInt() and 0x3f) or 0x80).toByte()
    val hex = "0123456789abcdef"
    val sb = StringBuilder(36)
    for (i in bytes.indices) {
        val b = bytes[i].toInt() and 0xff
        sb.append(hex[b ushr 4]).append(hex[b and 0xf])
        if (i == 3 || i == 5 || i == 7 || i == 9) sb.append('-')
    }
    return sb.toString()
}
