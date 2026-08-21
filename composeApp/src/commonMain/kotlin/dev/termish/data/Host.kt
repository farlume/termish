package dev.termish.data

import kotlinx.serialization.Serializable
import kotlin.random.Random

/** 主机的认证方式。 */
@Serializable
enum class HostAuthMethod {
    PASSWORD,
    PRIVATE_KEY,
    KEY_OR_PASSWORD,
}

/** 连接方式：SSH 终端（默认）或 Mosh（需要远端安装 mosh-server）。 */
@Serializable
enum class ConnectionMode {
    SSH,
    MOSH,
}

@Serializable
data class Host(
    val id: String,
    val name: String,
    val hostname: String,
    val port: Int = 22,
    val username: String = "root",
    val authMethod: HostAuthMethod = HostAuthMethod.PASSWORD,
    val tags: List<String> = emptyList(),
    /**
     * 远端系统标识（头像显示用）：如 ubuntu / debian / macos / windows。
     * 头像图标与颜色按关键词自动映射，未识别时显示通用图标。
     */
    val system: String = "",
    val colorIndex: Int = 0,
    val createdAt: Long = 0L,
    val lastConnectedAt: Long = 0L,
    val knownHostFingerprint: String? = null,
    /** SSH 终端 或 Mosh。 */
    val connectionMode: ConnectionMode = ConnectionMode.SSH,
    /**
     * 连接后自动启动 herdr 工作台（agent 口袋入口）：
     * - Mosh：引导 `mosh-server new -- herdr`，mosh 会话直接跑 herdr TUI；
     *   降级/SSH：连接后向 shell 注入 `herdr` 命令（回 shell 可退出）
     * - 远端未装 herdr 时引导安装（官网脚本，卡片实时日志）
     * - 勾选 = 显式同意 agent 状态监控（探测/安装引导都以此开关驱动）
     */
    val launchHerdr: Boolean = false,
    /** 连接成功后自动执行的命令（如 `tmux new -A -s main` 实现会话现场恢复）。 */
    val startupCommand: String = "",
    /**
     * Mosh 连接建立后，把手机终端主题（前景/背景/16 色调色板）以
     * OSC 应答的形式注入远端输入流。mosh-server 会吞掉远端 TUI
     * （如 herdr）发出的 OSC 10/11 颜色查询，导致远端按宿主机终端
     * 主题渲染而不是按手机主题渲染；开启后 herdr 等应用会像收到终端
     * 应答一样解析并采纳手机配色。
     */
    val moshThemeSync: Boolean = false,
    /**
     * Mosh 固定 UDP 端口；0 表示由 mosh-server 自动选择（60000-61000）。
     * 走 NAS / 路由器端口转发（只放行固定端口）时可固定一个端口，
     * 并把该 UDP 端口转发到远端主机。
     */
    val moshUdpPort: Int = 0,
)

@Serializable
enum class ThemeMode { DARK, LIGHT, SYSTEM }

@Serializable
data class AppSettings(
    val theme: ThemeMode = ThemeMode.DARK,
    /** 界面语言：空 = 跟随系统；"zh" / "en" = 用户显式选择。 */
    val language: String = "",
    val terminalThemeIndex: Int = 0,
    val fontSize: Int = 14,
    val terminalFontSize: Int = 12,
    /** 头像字母/背景色：首次进入设置页随机生成后持久化，不再随切换变化。 */
    val avatarLetter: String = "",
    val avatarColorIndex: Int = -1,
    /** 目标终端列数：>0 时忽略 terminalFontSize，按屏幕宽度自动反算字号（对齐桌面终端 120×30 这类体验）。 */
    val terminalTargetCols: Int = 0,
    /** PTY 终端类型（$TERM）：xterm-256color 默认；xterm/vt100/linux 兼容备选。 */
    val terminalType: String = "xterm-256color",
    /** 终端字体 id（见 TerminalFont）：jetbrains 默认。 */
    val terminalFontId: String = "jetbrains",
    /** 意外断线时自动重连（指数退避，最多 3 次）。 */
    val autoReconnect: Boolean = true,
    val keepaliveSeconds: Int = 30,
    val cursorBlink: Boolean = true,
    val hapticFeedback: Boolean = true,
    /** 首次连接未知主机时提示确认（TOFU）。 */
    val verifyHostKeyOnFirstUse: Boolean = true,
    /** OSC 52：允许远端程序写系统剪贴板（nvim/tmux 复制会同步到本机）。 */
    val osc52Clipboard: Boolean = true,
    /** 通知总开关（后台事件通知，如连接断开/重连失败）。 */
    val notificationEnabled: Boolean = true,
    /** 被关闭的通知事件 id（见 NotificationEvent）；空 = 全部开启。 */
    val notificationDisabledEvents: Set<String> = emptySet(),
    /** 语音输入总开关（终端工具栏麦克风键）。 */
    val voiceInputEnabled: Boolean = false,
    /**
     * 语音识别服务列表（可插拔：火山引擎等，未来可加阿里云/讯飞等）；
     * 每项独立密钥/参数（密钥存 SecretStore）。旧字段 [asrResourceId]
     * 已被列表取代，读取时自动迁移（见 AppRoot）。
     */
    val asrProviders: List<AsrProvider> = emptyList(),
    /** @deprecated 旧版单实例配置：已被 [asrProviders] 取代（迁移用）。 */
    val asrResourceId: String = "",
)

/** 语音识别服务类型（可插拔 provider）。 */
@Serializable
enum class AsrProviderType {
    /** 火山引擎·大模型流式语音识别（bigmodel_async WebSocket）。 */
    VOLC_STREAMING,
}

/** 一个语音识别服务实例配置（密钥单独存 SecretStore：asr.<id>.apiKey）。 */
@Serializable
data class AsrProvider(
    val id: String,
    /** 服务类型（决定使用哪个引擎实现）。 */
    val type: AsrProviderType = AsrProviderType.VOLC_STREAMING,
    /** 显示名称（如「火山引擎·家庭号」）。 */
    val name: String = "",
    /** 类型专属参数：火山流式为资源 ID（模型版本，见 VolcAsrProtocol.RESOURCE_IDS）。 */
    val resourceId: String = "",
    /** 是否启用（终端优先取第一个启用的服务）。 */
    val enabled: Boolean = true,
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
