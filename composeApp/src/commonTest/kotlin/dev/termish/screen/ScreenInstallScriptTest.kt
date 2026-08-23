package dev.termish.screen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenInstallScriptTest {
    @Test
    fun `headless Linux is rejected before relay upgrade is offered`() {
        val script = ScreenSession.READ_STREAM_SCRIPT
        val desktopProbe = script.indexOf("case \"\$OS\" in")
        val relayVersionProbe = script.indexOf("# relay 版本匹配")

        assertTrue(desktopProbe >= 0, "读流脚本应先识别桌面系统")
        assertTrue(relayVersionProbe > desktopProbe, "无桌面的 Linux 不应被误导去升级 relay")
        assertContains(script.substring(desktopProbe, relayVersionProbe), "SCREEN_NO_DISPLAY")
    }

    @Test
    fun `Linux installer supports root passwordless and password sudo`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(script, "\$(id -u)")
        assertContains(script, "sudo -n true")
        assertContains(script, "TERMISH_SUDO_STDIN")
        assertContains(script, "sudo -S -p ''")
        assertContains(script, "sudo apt-get update && sudo apt-get install -y ffmpeg")
        assertContains(script, "sudo dnf install -y ffmpeg")
        assertContains(script, "sudo pacman -S --needed ffmpeg")
    }

    @Test
    fun `ffmpeg preflight uses the same SSH path fallbacks`() {
        val script = ScreenSession.FFMPEG_PROBE_SCRIPT

        assertContains(script, "\$HOME/bin/ffmpeg")
        assertContains(script, "/opt/homebrew/bin/ffmpeg")
        assertContains(script, "/usr/bin/ffmpeg")
        assertContains(script, "FFMPEG_OK")
    }

    @Test
    fun `sudo password is requested only when Linux dependency needs it`() {
        fun needs(
            os: String? = "Linux",
            ffmpegPresent: Boolean = false,
            isRoot: Boolean = false,
            hasSudo: Boolean = true,
            passwordless: Boolean = false,
        ) = ScreenSession.needsScreenSudoPassword(os, ffmpegPresent, isRoot, hasSudo, passwordless)

        assertTrue(needs())
        assertFalse(needs(os = "Darwin"))
        assertFalse(needs(ffmpegPresent = true))
        assertFalse(needs(isRoot = true))
        assertFalse(needs(hasSudo = false))
        assertFalse(needs(passwordless = true))
    }
}
