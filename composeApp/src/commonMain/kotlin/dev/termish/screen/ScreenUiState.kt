package dev.termish.screen

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * 屏幕会话 UI 状态（挂在会话条目上，切 tab 保留）。
 */
class ScreenUiState {
    /** 视频播放器（ExoPlayer 渲染面绑定用；null = 未建立）。 */
    var player by mutableStateOf<ScreenPlayer?>(null)
    /** 播放器已渲染首帧。 */
    var videoReady by mutableStateOf(false)
    /** 画面尺寸描述（如 1280×720）。 */
    var frameSize by mutableStateOf("")
    /** 帧率估算。 */
    var fps by mutableStateOf(0)
    /** 是否已连通并推流。 */
    var connected by mutableStateOf(false)
    /** 连接错误。 */
    var error by mutableStateOf<String?>(null)
    /** 远端缺 ffmpeg（引导安装信号）。 */
    var ffmpegMissing by mutableStateOf(false)
    /** 远端推流服务未运行（引导一键安装信号）。 */
    var serviceMissing by mutableStateOf(false)
    /** 服务安装中。 */
    var installing by mutableStateOf(false)
    /** 安装日志（实时展示）。 */
    var installLog by mutableStateOf("")
}
