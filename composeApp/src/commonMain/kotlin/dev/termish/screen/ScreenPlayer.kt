package dev.termish.screen

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier

/** 平台解码器失败类型：平台层只上报结构化原因，用户文案由 UI 语言包决定。 */
sealed interface ScreenPlayerFailure {
    data class Initialization(
        val detail: String?,
    ) : ScreenPlayerFailure

    data class NoOutput(
        val fedFrames: Int,
    ) : ScreenPlayerFailure

    data class Decoding(
        val detail: String,
    ) : ScreenPlayerFailure

    data object Unsupported : ScreenPlayerFailure
}

/**
 * 平台视频播放器（远程画面渲染层）：
 * - Android：MediaCodec 直解 + SurfaceView 直上屏（ToDesk 式直通管线）。
 * - iOS：AVSampleBufferDisplayLayer + VideoToolbox 硬解直上屏。
 * - desktop：未实现（stub）。
 *
 * 用法：TCP 协议切出的完整 H.264 帧 [feed]；首帧渲染回调 [onReady]；
 * 播放错误回调 [onError]；UI 用 [ScreenVideoSurface] 渲染画面。
 */
expect class ScreenPlayer(
    onReady: () -> Unit,
    onError: (ScreenPlayerFailure) -> Unit,
) {
    /** 启动解码与渲染管线（幂等；重复调用忽略）。 */
    fun start()

    /** 喂入一个完整帧；平台播放器使用有界队列，消费慢时丢旧保新。 */
    fun feed(data: ByteArray)

    /** 释放播放器与服务。 */
    fun stop()

    /** 视频实际尺寸（解码器上报，UI 按宽高比布局 + 远程操作坐标映射）。 */
    val videoDims: MutableState<Pair<Int, Int>?>

    /** 最近一帧成功提交到显示层的时间；用于区分网络有帧与解码渲染卡死。 */
    val lastRenderedAtMillis: Long

    /** 当前是否有实际渲染面；切到其它会话时无 Surface 不属于播放器卡死。 */
    val renderSurfaceAttached: Boolean
}

/** 视频渲染面（Android = SurfaceView；iOS = AVSampleBufferDisplayLayer）。 */
@Composable
expect fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier = Modifier,
    /** 视频贴底部对齐（远程键盘弹出时：视频底部贴工具栏，黑边留顶部）。 */
    alignBottom: Boolean = false,
)

/**
 * 解码能力探测：指定分辨率下硬件解码器支持的帧率上限（0 = 未知/失败）。
 * 用于钳制推流档位——编码帧率超过解码能力只会浪费带宽 + 解码器满载排队
 * （延迟升高），业界（scrcpy/Parsec）均按解码能力限制编码帧率。
 */
expect fun probeDecoderMaxFps(
    width: Int,
    height: Int,
): Int
