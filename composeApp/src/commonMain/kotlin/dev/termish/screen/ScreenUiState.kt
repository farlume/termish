package dev.termish.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 屏幕会话 UI 状态（挂在会话条目上，切 tab 保留）。
 */
class ScreenUiState {
    /** 视频播放器（渲染面绑定用；null = 未建立）。 */
    var player by mutableStateOf<ScreenPlayer?>(null)

    /** 播放器已渲染首帧。 */
    var videoReady by mutableStateOf(false)

    /** 画面尺寸描述（如 1280×720）。 */
    var frameSize by mutableStateOf("")

    /** 帧率估算。 */
    var fps by mutableStateOf(0)

    /** 最近统计窗口的接收码率。 */
    var bitrateKbps by mutableStateOf(0)

    /** 基于发送/到达间隔差估算的网络抖动。 */
    var jitterMillis by mutableStateOf(0)

    /** 网络推断丢帧 + 播放器主动丢帧，千分比。 */
    var droppedPermille by mutableStateOf(0)

    /** 是否已连通并推流。 */
    var connected by mutableStateOf(false)

    /** 连接错误。 */
    var error by mutableStateOf<String?>(null)

    /** 屏幕状态提示（非错误，可恢复）：Mac 息屏/锁屏等，画面到达自动清除。 */
    var screenHint by mutableStateOf<String?>(null)

    /** 当前推流帧率（全屏右上角档位显示）。 */
    var streamFps by mutableStateOf(30)

    /** 用户选择的帧率上限；自动降档恢复时不会超过它（0 = 等待远端配置回读）。 */
    var preferredStreamFps: Int = 0

    /** 最近一次自适应调档时间；跨重连保留，提供恢复迟滞。 */
    var adaptiveFpsChangedAtMillis: Long = 0

    /** 当前画质档位（全屏右上角档位显示）。 */
    var streamQuality by mutableStateOf(1)

    /** 解码能力探测的帧率上限（0 = 未知；档位菜单据此隐藏超出项）。 */
    var decoderMaxFps by mutableStateOf(0)

    /** 当前画面首次出帧时间；用于区分稳定运行后的偶发断线与刚连接即失败。 */
    var videoReadyAtMillis: Long = 0

    /** 连续自动重连次数；稳定播放后归零，防止失败会话无限循环替换。 */
    var streamReconnectAttempts: Int = 0

    /** 远程操作模式（触摸→鼠标/滚轮，默认开）；关闭 = 纯观看（本地缩放平移，无键盘）。 */
    var controlMode by mutableStateOf(true)

    /** 被控端缺辅助功能权限（relay 状态包上报，引导用户授权）。 */
    var controlPermissionMissing by mutableStateOf(false)

    /** 被控端当前图形会话没有可用的控制后端（relay 状态 2 上报）。 */
    var controlUnsupported by mutableStateOf(false)

    /** 远程操作发送器（ScreenSession 注入：触摸事件 → TCP 控制包）。 */
    var controlSender: ((type: Int, x: Float, y: Float, extra: Int) -> Unit)? by mutableStateOf(null)

    /** 远程键盘发送器：keyCode + 修饰掩码 + 文本（文本非空时优先）。 */
    var keySender: ((keyCode: Int, mods: Int, text: String) -> Unit)? by mutableStateOf(null)

    /** 远端缺 ffmpeg（引导安装信号）。 */
    var ffmpegMissing by mutableStateOf(false)

    /** 远端推流服务未运行（引导一键安装信号）。 */
    var serviceMissing by mutableStateOf(false)

    /** 远端 relay 版本过旧（引导升级而非首次安装）。 */
    var relayNeedsUpgrade by mutableStateOf(false)

    /** 服务安装中。 */
    var installing by mutableStateOf(false)

    /** Linux 安装 ffmpeg 需要 sudo 密码（仅本次安装使用，不保存）。 */
    var needsSudoPassword by mutableStateOf(false)

    /** 安装日志（实时展示）。 */
    var installLog by mutableStateOf("")
}
