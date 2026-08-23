package dev.termish.screen

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * 远端推流参数回读解析：SCREEN_CFG_FPS / SCREEN_CFG_SCALE 行 → UI 档位。
 * conf 缺失/脏值不覆盖客户端本地默认档位。
 */
class StreamCfgParseTest {
    @Test
    fun parsesFpsAndScale() {
        val out = "SCREEN_CFG_FPS:120\nSCREEN_CFG_SCALE:1920:-2\nSCREEN_UDP_PORT:17322\n"
        val (fps, scale) = ScreenSession.parseStreamCfg(out)
        assertEquals(120, fps)
        assertEquals("1920:-2", scale)
        assertEquals(2, ScreenSession.qualityIndexFor(scale!!))
    }

    @Test
    fun missingLinesReturnNulls() {
        val (fps, scale) = ScreenSession.parseStreamCfg("SCREEN_UDP_PORT:17322\n")
        assertNull(fps)
        assertNull(scale)
    }

    @Test
    fun garbageValuesIgnored() {
        // fps 非数字 / scale 空值：不覆盖（null），档位保持本地默认
        val out = "SCREEN_CFG_FPS:abc\nSCREEN_CFG_SCALE:\n"
        val (fps, scale) = ScreenSession.parseStreamCfg(out)
        assertNull(fps)
        assertNull(scale)
    }

    @Test
    fun qualityIndexMapping() {
        assertEquals(0, ScreenSession.qualityIndexFor("960:-2"))
        assertEquals(1, ScreenSession.qualityIndexFor("1280:-2"))
        assertEquals(2, ScreenSession.qualityIndexFor("1920:-2"))
        assertEquals(1, ScreenSession.qualityIndexFor("1600:-2")) // 未知按标清
    }
}
