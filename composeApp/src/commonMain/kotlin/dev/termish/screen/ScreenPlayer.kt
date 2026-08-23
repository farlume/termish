package dev.termish.screen

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 平台视频播放器（远程画面渲染层）：
 * - Android：ExoPlayer + 本地 HTTP 服务（SSH 通道字节流 → 127.0.0.1 → 播放器）。
 *   厂商 MediaCodec 手写管线在 OPPO/MTK 上吞输入不出帧（实测：字节级验证正确、
 *   全部解码器零输出），改为交给业界标准播放器组件处理解码与上屏。
 * - iOS/桌面：未实现（stub；屏幕功能当前仅 Android 可用）。
 *
 * 用法：SSH 读循环 [feed] 原始 TS 字节；首帧渲染回调 [onReady]；
 * 播放错误回调 [onError]；UI 用 [ScreenVideoSurface] 渲染画面。
 */
expect class ScreenPlayer(
    onReady: () -> Unit,
    onError: (String) -> Unit,
) {
    /** 启动本地 HTTP 服务 + 播放器（幂等；重复调用忽略）。 */
    fun start()

    /** 喂入流数据（阻塞式背压：消费慢时调用方挂起）。 */
    fun feed(data: ByteArray)

    /** 释放播放器与服务。 */
    fun stop()
}

/** 视频渲染面（Android = ExoPlayer PlayerView；其余平台黑底占位）。 */
@Composable
expect fun ScreenVideoSurface(
    player: ScreenPlayer?,
    modifier: Modifier = Modifier,
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
