package dev.termish.screen

internal const val SCREEN_ADAPTIVE_BAD_WINDOWS = 3
internal const val SCREEN_ADAPTIVE_HEALTHY_WINDOWS = 20
internal const val SCREEN_ADAPTIVE_RECOVERY_COOLDOWN_MS = 30_000L

/**
 * 根据播放器累计指标做帧率调档。连续压力才降档，长时间健康才逐级恢复，
 * 避免瞬时抖动导致远端编码器频繁重启。
 */
internal class ScreenAdaptiveFpsController {
    private var previous = ScreenPlayerMetrics()
    private var badWindows = 0
    private var healthyWindows = 0

    fun evaluate(
        metrics: ScreenPlayerMetrics,
        currentFps: Int,
        preferredFps: Int,
        decoderMaxFps: Int,
        nowMillis: Long,
        lastChangeAtMillis: Long,
    ): Int? {
        val received = (metrics.receivedFrames - previous.receivedFrames).coerceAtLeast(0)
        val dropped = (metrics.droppedFrames - previous.droppedFrames).coerceAtLeast(0)
        val busy = (metrics.decoderBusyFrames - previous.decoderBusyFrames).coerceAtLeast(0)
        previous = metrics

        val decoderLimit = decoderMaxFps.takeIf { it > 0 } ?: Int.MAX_VALUE
        val cappedFps = fpsStepAtMost(minOf(preferredFps, decoderLimit))
        if (currentFps > cappedFps) {
            resetWindows()
            return cappedFps
        }
        if (received < 10) return null

        val deliveryBelowTarget = currentFps > 30 && received * 100 < currentFps * 70L
        val pressured = deliveryBelowTarget || busy >= 2 || dropped * 100 >= received * 12
        if (pressured) {
            badWindows++
            healthyWindows = 0
        } else {
            badWindows = 0
            healthyWindows++
        }

        if (badWindows >= SCREEN_ADAPTIVE_BAD_WINDOWS && currentFps > 30) {
            resetWindows()
            return fpsStepBelow(currentFps)
        }

        val canRecover =
            currentFps < cappedFps &&
                healthyWindows >= SCREEN_ADAPTIVE_HEALTHY_WINDOWS &&
                nowMillis - lastChangeAtMillis >= SCREEN_ADAPTIVE_RECOVERY_COOLDOWN_MS
        if (canRecover) {
            resetWindows()
            return minOf(fpsStepAbove(currentFps), cappedFps)
        }
        return null
    }

    private fun resetWindows() {
        badWindows = 0
        healthyWindows = 0
    }
}

private fun fpsStepAtMost(fps: Int): Int =
    when {
        fps >= 120 -> 120
        fps >= 60 -> 60
        else -> 30
    }

private fun fpsStepBelow(fps: Int): Int = if (fps > 60) 60 else 30

private fun fpsStepAbove(fps: Int): Int = if (fps < 60) 60 else 120
