package dev.termish.screen

internal const val SCREEN_STABLE_WINDOW_MS = 15_000L
internal const val MAX_SCREEN_RECONNECT_ATTEMPTS = 3
internal const val SCREEN_FIRST_VIDEO_TIMEOUT_MS = 12_000L
internal const val SCREEN_WAYLAND_FIRST_VIDEO_TIMEOUT_MS = 60_000L
internal const val SCREEN_VIDEO_STALL_TIMEOUT_MS = 6_000L
internal const val SCREEN_RENDER_STALL_TIMEOUT_MS = 6_000L

/**
 * TCP/SSH 本身可能保持 ESTABLISHED，但 direct-tcpip 的消费协程或中间链路已经
 * 停止推进。此时 EOF 永远不会到达，不能只靠 socket close 判定断线。
 *
 * 首帧给远端编码器更长的启动时间；一旦收到过视频，30fps 的正常帧间隔远小于
 * 1 秒，连续 6 秒无完整帧即可确定是假连接。
 */
internal fun isScreenVideoStalled(
    nowMillis: Long,
    lastVideoAtMillis: Long,
    startedAtMillis: Long,
    hasReceivedVideo: Boolean,
    firstVideoTimeoutMillis: Long = SCREEN_FIRST_VIDEO_TIMEOUT_MS,
): Boolean {
    val baseline = if (hasReceivedVideo) lastVideoAtMillis else startedAtMillis
    val timeout = if (hasReceivedVideo) SCREEN_VIDEO_STALL_TIMEOUT_MS else firstVideoTimeoutMillis
    return baseline > 0L && nowMillis - baseline >= timeout
}

/** 网络持续收到视频但播放器没有再提交画面时，不能把 TCP 活跃误判为可用。 */
internal fun isScreenRenderingStalled(
    nowMillis: Long,
    lastRenderedAtMillis: Long,
    videoReady: Boolean,
    renderSurfaceAttached: Boolean,
): Boolean =
    videoReady &&
        renderSurfaceAttached &&
        lastRenderedAtMillis > 0L &&
        nowMillis - lastRenderedAtMillis >= SCREEN_RENDER_STALL_TIMEOUT_MS

internal fun isUnstableScreenStream(
    nowMillis: Long,
    videoReadyAtMillis: Long,
): Boolean = videoReadyAtMillis == 0L || nowMillis - videoReadyAtMillis < SCREEN_STABLE_WINDOW_MS

internal fun fallbackScreenQuality(
    currentQuality: Int,
    actualWidth: Int,
    unstable: Boolean,
): Int =
    when {
        !unstable -> currentQuality
        currentQuality == 3 && actualWidth > 1920 -> 2
        currentQuality == 2 -> 1
        else -> currentQuality
    }
