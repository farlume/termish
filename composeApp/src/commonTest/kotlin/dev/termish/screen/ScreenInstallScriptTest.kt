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
        assertTrue(desktopProbe >= 0)
        assertTrue(relayVersionProbe > desktopProbe)
        assertContains(script.substring(desktopProbe, relayVersionProbe), "SCREEN_NO_DISPLAY")
        assertContains(script, "loginctl list-sessions")
        assertContains(script, "wayland:no|x11:no")
        assertContains(script, "SCREEN_WAYLAND_DEPS_MISSING")
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
    fun `Linux installer only stops verified service processes`() {
        val script = ScreenSession.INSTALL_SCRIPT
        assertContains(script, "stop_termish_relay_pid")
        assertContains(script, "[ -r \"/proc/\$OLD_PID/cmdline\" ] || return 0")
        assertContains(script, "lsof -t -iTCP:\$PORT -sTCP:LISTEN")
        assertContains(script, "*\"\$NATIVE\"*")
        assertFalse(script.lineSequence().any { it.trimStart().startsWith("pkill ") })
    }

    @Test
    fun `Linux relay survives reboot through a user service`() {
        val script = ScreenSession.INSTALL_SCRIPT
        assertContains(script, "dev.termish.screen.service")
        assertContains(script, "systemctl --user enable dev.termish.screen.service")
        assertContains(script, "systemctl --user restart dev.termish.screen.service")
        assertContains(script, "WantedBy=default.target")
        assertContains(script, "dev.termish.screen.desktop")
        assertContains(script, "XAUTHORITY")
        assertContains(script, "export XDG_SESSION_TYPE=wayland")
        assertContains(script, "export WAYLAND_DISPLAY")
        assertContains(script, "exec \"\$NATIVE\"")
    }

    @Test
    fun `desktop dependency probes and installer require no interpreter`() {
        assertContains(ScreenSession.XCLIP_PROBE_SCRIPT, "command -v xclip")
        assertContains(ScreenSession.WAYLAND_PROBE_SCRIPT, "gst-inspect-1.0 pipewiresrc")
        assertContains(ScreenSession.INSTALL_SCRIPT, "Rust service payload verified")
        assertContains(ScreenSession.INSTALL_SCRIPT, "Native payload required")
        assertFalse(ScreenSession.INSTALL_SCRIPT.contains("python3"))
        assertFalse(ScreenSession.READ_STREAM_SCRIPT.contains("python3"))
    }

    @Test
    fun `sudo password is requested only when Linux dependency needs it`() {
        fun needs(
            os: String? = "Linux",
            ffmpegPresent: Boolean = false,
            xlibPresent: Boolean = true,
            waylandDependenciesPresent: Boolean = true,
            desktopManagerPresent: Boolean = true,
            isRoot: Boolean = false,
            hasSudo: Boolean = true,
            passwordless: Boolean = false,
        ) = ScreenSession.needsScreenSudoPassword(
            os = os,
            ffmpegPresent = ffmpegPresent,
            xlibPresent = xlibPresent,
            waylandDependenciesPresent = waylandDependenciesPresent,
            desktopManagerPresent = desktopManagerPresent,
            isRoot = isRoot,
            hasSudo = hasSudo,
            sudoPasswordless = passwordless,
        )
        assertTrue(needs())
        assertTrue(needs(ffmpegPresent = true, xlibPresent = false))
        assertTrue(needs(ffmpegPresent = true, waylandDependenciesPresent = false))
        assertTrue(needs(ffmpegPresent = true, desktopManagerPresent = false))
        assertFalse(needs(os = "Darwin"))
        assertFalse(needs(ffmpegPresent = true))
        assertFalse(needs(isRoot = true))
        assertFalse(needs(hasSudo = false))
        assertFalse(needs(passwordless = true))
    }
}
