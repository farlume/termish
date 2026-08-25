package dev.termish.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenReconnectPolicyTest {
    @Test
    fun renderingStallRequiresReadyFrameAndTimeout() {
        assertFalse(isScreenRenderingStalled(20_000, 0, videoReady = true, renderSurfaceAttached = true))
        assertFalse(isScreenRenderingStalled(20_000, 15_000, videoReady = false, renderSurfaceAttached = true))
        assertFalse(isScreenRenderingStalled(20_999, 15_000, videoReady = true, renderSurfaceAttached = true))
        assertFalse(isScreenRenderingStalled(21_000, 15_000, videoReady = true, renderSurfaceAttached = false))
        assertTrue(isScreenRenderingStalled(21_000, 15_000, videoReady = true, renderSurfaceAttached = true))
    }

    @Test
    fun `unstable high resolution stream falls back one level`() {
        assertEquals(2, fallbackScreenQuality(currentQuality = 3, actualWidth = 2560, unstable = true))
        assertEquals(1, fallbackScreenQuality(currentQuality = 2, actualWidth = 1920, unstable = true))
    }

    @Test
    fun `native resolution is not accidentally upscaled during fallback`() {
        assertEquals(3, fallbackScreenQuality(currentQuality = 3, actualWidth = 1280, unstable = true))
        assertEquals(3, fallbackScreenQuality(currentQuality = 3, actualWidth = 2560, unstable = false))
    }

    @Test
    fun `stream becomes stable after reset window`() {
        assertTrue(isUnstableScreenStream(nowMillis = 10_000, videoReadyAtMillis = 0))
        assertTrue(isUnstableScreenStream(nowMillis = 20_000, videoReadyAtMillis = 10_000))
        assertFalse(isUnstableScreenStream(nowMillis = 25_000, videoReadyAtMillis = 10_000))
    }

    @Test
    fun `video liveness distinguishes encoder startup from an established stream stall`() {
        assertFalse(
            isScreenVideoStalled(
                nowMillis = 11_999,
                lastVideoAtMillis = 0,
                startedAtMillis = 1,
                hasReceivedVideo = false,
            ),
        )
        assertTrue(
            isScreenVideoStalled(
                nowMillis = 12_001,
                lastVideoAtMillis = 0,
                startedAtMillis = 1,
                hasReceivedVideo = false,
            ),
        )
        assertFalse(
            isScreenVideoStalled(
                nowMillis = 15_999,
                lastVideoAtMillis = 10_000,
                startedAtMillis = 1,
                hasReceivedVideo = true,
            ),
        )
        assertTrue(
            isScreenVideoStalled(
                nowMillis = 16_000,
                lastVideoAtMillis = 10_000,
                startedAtMillis = 1,
                hasReceivedVideo = true,
            ),
        )
    }
}
