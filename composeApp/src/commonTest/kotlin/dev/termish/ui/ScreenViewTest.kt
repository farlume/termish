package dev.termish.ui

import kotlin.test.Test
import kotlin.test.assertEquals

class ScreenViewTest {
    @Test
    fun ultraQualityCapsInitialSelectionAt60Fps() {
        assertEquals(60, fpsForQualitySelection(currentFps = 120, qualityIndex = 3))
        assertEquals(60, fpsForQualitySelection(currentFps = 60, qualityIndex = 3))
        assertEquals(30, fpsForQualitySelection(currentFps = 30, qualityIndex = 3))
    }

    @Test
    fun otherQualityPresetsKeepCurrentFps() {
        assertEquals(120, fpsForQualitySelection(currentFps = 120, qualityIndex = 0))
        assertEquals(120, fpsForQualitySelection(currentFps = 120, qualityIndex = 1))
        assertEquals(120, fpsForQualitySelection(currentFps = 120, qualityIndex = 2))
    }
}
