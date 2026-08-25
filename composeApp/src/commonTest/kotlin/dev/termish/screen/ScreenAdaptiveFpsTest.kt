package dev.termish.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ScreenAdaptiveFpsTest {
    @Test
    fun `sustained decoder pressure lowers one fps step`() {
        val controller = ScreenAdaptiveFpsController()
        var received = 0L
        var dropped = 0L

        repeat(SCREEN_ADAPTIVE_BAD_WINDOWS - 1) {
            received += 30
            dropped += 4
            assertNull(controller.evaluate(metrics(received, dropped), 120, 120, 120, it * 1_000L, 0))
        }
        received += 30
        dropped += 4

        assertEquals(60, controller.evaluate(metrics(received, dropped), 120, 120, 120, 3_000, 0))
    }

    @Test
    fun `a short pressure burst does not restart the stream`() {
        val controller = ScreenAdaptiveFpsController()

        assertNull(controller.evaluate(metrics(60, 10), 60, 60, 60, 1_000, 0))
        assertNull(controller.evaluate(metrics(120, 10), 60, 60, 60, 2_000, 0))
        assertNull(controller.evaluate(metrics(180, 10), 60, 60, 60, 3_000, 0))
    }

    @Test
    fun `sustained delivery below target lowers the requested fps`() {
        val controller = ScreenAdaptiveFpsController()
        var received = 0L
        var recommendation: Int? = null

        repeat(SCREEN_ADAPTIVE_BAD_WINDOWS) { index ->
            received += 30
            recommendation = controller.evaluate(metrics(received, 0), 60, 60, 60, index * 1_000L, 0)
        }

        assertEquals(30, recommendation)
    }

    @Test
    fun `healthy stream recovers gradually up to user preference`() {
        val controller = ScreenAdaptiveFpsController()
        var received = 0L
        var recommendation: Int? = null

        repeat(SCREEN_ADAPTIVE_HEALTHY_WINDOWS) { index ->
            received += 30
            recommendation =
                controller.evaluate(
                    metrics(received, 0),
                    currentFps = 30,
                    preferredFps = 120,
                    decoderMaxFps = 120,
                    nowMillis = 40_000L + index * 1_000L,
                    lastChangeAtMillis = 0,
                )
        }

        assertEquals(60, recommendation)
    }

    @Test
    fun `healthy stream waits for recovery cooldown`() {
        val controller = ScreenAdaptiveFpsController()
        var received = 0L
        var recommendation: Int? = null

        repeat(SCREEN_ADAPTIVE_HEALTHY_WINDOWS) { index ->
            received += 30
            recommendation =
                controller.evaluate(
                    metrics(received, 0),
                    currentFps = 30,
                    preferredFps = 60,
                    decoderMaxFps = 60,
                    nowMillis = 10_000L + index * 1_000L,
                    lastChangeAtMillis = 10_000L,
                )
        }

        assertNull(recommendation)
    }

    @Test
    fun `decoder capability immediately caps an unsupported fps`() {
        val controller = ScreenAdaptiveFpsController()

        assertEquals(
            60,
            controller.evaluate(
                metrics = ScreenPlayerMetrics(),
                currentFps = 120,
                preferredFps = 120,
                decoderMaxFps = 75,
                nowMillis = 0,
                lastChangeAtMillis = 0,
            ),
        )
    }

    private fun metrics(
        received: Long,
        dropped: Long,
    ): ScreenPlayerMetrics =
        ScreenPlayerMetrics(
            receivedFrames = received,
            droppedFrames = dropped,
        )
}
