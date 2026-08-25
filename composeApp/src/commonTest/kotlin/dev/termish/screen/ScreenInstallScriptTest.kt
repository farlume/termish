package dev.termish.screen

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScreenInstallScriptTest {
    @Test
    fun `linux relay wakes a sleeping display before capture`() {
        val script = ScreenSession.INSTALL_SCRIPT

        val definition = script.indexOf("def wake_linux_display():")
        val call = script.indexOf("wake_linux_display()", definition + "def wake_linux_display():".length)
        val ffmpegStart = script.indexOf("self.ff = subprocess.Popen")

        assertTrue(definition >= 0)
        assertTrue(script.contains("Monitor is Off"))
        assertTrue(script.contains("dpms\", \"force\", \"on"))
        assertTrue(script.contains("display, \"s\", \"reset"))
        assertTrue(script.contains("now - stream.last_display_keepalive >= 15.0"))
        assertTrue(call > definition && call < ffmpegStart)
    }

    @Test
    fun `headless Linux is rejected before relay upgrade is offered`() {
        val script = ScreenSession.READ_STREAM_SCRIPT
        val desktopProbe = script.indexOf("case \"\$OS\" in")
        val relayVersionProbe = script.indexOf("# relay 版本匹配")

        assertTrue(desktopProbe >= 0, "读流脚本应先识别桌面系统")
        assertTrue(relayVersionProbe > desktopProbe, "无桌面的 Linux 不应被误导去升级 relay")
        assertContains(script.substring(desktopProbe, relayVersionProbe), "SCREEN_NO_DISPLAY")
        assertContains(script, "loginctl list-sessions")
        assertContains(script, "SESSION_TYPE:${'$'}SESSION_REMOTE")
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
    fun `relay preserves desktop detail in high quality presets`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(script, ":flags=lanczos")
        assertContains(script, "if scale != \"native\":")
        assertContains(script, "base_mbps = 24")
        assertContains(script, "bitrate_mbps = base_mbps * 2")
        assertContains(script, "bitrate_mbps = base_mbps * 3 // 2")
        assertContains(script, "crf = \"16\"")
        assertContains(script, "\"-crf\", crf")
    }

    @Test
    fun `relay uses native events for virtual mouse dragging`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(script, "elif typ == 10:  # 虚拟左键拖动开始")
        assertContains(script, "elif typ == 11:  # 虚拟左键拖动移动")
        assertContains(script, "elif typ == 12:  # 虚拟左键拖动结束")
        assertContains(script, "Quartz.kCGEventLeftMouseDragged")
        assertContains(script, "_VIRTUAL_MOUSE_ORIGIN[0] = None")
        assertContains(script, "release_virtual_mouse(conn)")
        assertContains(script, "_XLIB_X.ButtonRelease, 1")
    }

    @Test
    fun `relay prevents stale channels from replacing active tcp stream`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(script, "if TCP_CLIENT[0] is not None:")
        assertContains(script, "conn.sendall(STATUS_MAGIC + bytes([3]))")
        assertContains(script, "relay_log(\"tcp busy peer=%s:%s\" % peer)")
        assertContains(script, "TCP_OWNER_LEASE_SECONDS = 6.0")
        assertContains(script, "stream.last_client_heartbeat = time.time()")
        assertContains(script, "if data[4] == HEARTBEAT_TYPE:")
        assertContains(script, "replace_reason = \"stale tcp lease replaced\"")
        assertContains(script, "old_stream.stop(replace_reason)")
        assertContains(script, "s.stop(\"udp lost ownership\")")
        assertContains(script, "transport=%s reason=%s")
        assertContains(script, "self.tcp_conn.shutdown(socket.SHUT_RDWR)")
        assertFalse(script.contains("old_conn = TCP_CLIENT[0]"))
    }

    @Test
    fun `Linux upgrade replaces legacy relay by validated listener pid`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(script, "stop_termish_relay_pid")
        assertContains(script, "[ -r \"/proc/${'$'}OLD_PID/cmdline\" ] || return 0")
        assertContains(script, "lsof -t -iTCP:${'$'}PORT -sTCP:LISTEN")
        assertContains(script, "*\"${'$'}RELAY\"*) kill")
        assertContains(script, "def stop_legacy_relay_listener():")
        assertContains(script, "stop_legacy_relay_listener()")
        assertContains(script, "if relay_path not in cmdline:")
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
        assertContains(script, ".termish-screen-launch.sh")
        assertContains(script, "XAUTHORITY")
        assertContains(script, "GRAPHICAL=0")
        assertContains(script, "[ \"${'$'}GRAPHICAL\" = \"1\" ]")
        assertContains(script, "export XDG_SESSION_TYPE=wayland")
        assertContains(script, "export WAYLAND_DISPLAY")
    }

    @Test
    fun `Linux control dependency reuses interactive sudo`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(script, "run_admin apt-get install -y -qq python3-xlib")
        assertContains(script, "run_admin dnf install -y -q python3-xlib")
        assertContains(script, "python-xlib: 安装完成")
        assertContains(ScreenSession.XLIB_PROBE_SCRIPT, "import Xlib")
    }

    @Test
    fun `Wayland relay uses consented portal capture and input`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(ScreenSession.WAYLAND_PROBE_SCRIPT, "import dbus")
        assertContains(ScreenSession.WAYLAND_PROBE_SCRIPT, "gst-inspect-1.0 pipewiresrc")
        assertContains(script, "org.freedesktop.portal.RemoteDesktop")
        assertContains(script, "org.freedesktop.portal.ScreenCast")
        assertContains(script, "OpenPipeWireRemote")
        assertContains(script, "pipewiresrc")
        assertContains(script, "keepalive-time=%d")
        assertFalse(script.contains("drop-only=true"))
        assertContains(script, "y4menc")
        assertContains(script, "NotifyPointerMotionAbsolute")
        assertContains(script, "NotifyPointerButton")
        assertContains(script, "NotifyKeyboardKeysym")
        assertContains(script, "org.freedesktop.portal.Session")
        assertContains(script, "self.capture.terminate()")
        assertContains(script, "[\"-f\", \"yuv4mpegpipe\", \"-i\", \"pipe:0\"]")
        assertFalse(script.contains("\"kmsgrab\""))
        assertFalse(script.contains("/dev/dri/"))
    }

    @Test
    fun `Linux control reads live root geometry after display resize`() {
        val script = ScreenSession.INSTALL_SCRIPT

        assertContains(script, "def _x_root_size():")
        assertContains(script, "_XDISPLAY.screen().root.get_geometry()")
        assertContains(script, "W, H = screen_size")
        assertFalse(script.contains("_XDISPLAY.screen().width_in_pixels"))
        assertFalse(script.contains("_XDISPLAY.screen().height_in_pixels"))
    }

    @Test
    fun `sudo password is requested only when Linux dependency needs it`() {
        fun needs(
            os: String? = "Linux",
            ffmpegPresent: Boolean = false,
            xlibPresent: Boolean = true,
            waylandDependenciesPresent: Boolean = true,
            isRoot: Boolean = false,
            hasSudo: Boolean = true,
            passwordless: Boolean = false,
        ) = ScreenSession.needsScreenSudoPassword(
            os = os,
            ffmpegPresent = ffmpegPresent,
            xlibPresent = xlibPresent,
            waylandDependenciesPresent = waylandDependenciesPresent,
            isRoot = isRoot,
            hasSudo = hasSudo,
            sudoPasswordless = passwordless,
        )

        assertTrue(needs())
        assertTrue(needs(ffmpegPresent = true, xlibPresent = false))
        assertTrue(needs(ffmpegPresent = true, waylandDependenciesPresent = false))
        assertFalse(needs(os = "Darwin"))
        assertFalse(needs(ffmpegPresent = true))
        assertFalse(needs(isRoot = true))
        assertFalse(needs(hasSudo = false))
        assertFalse(needs(passwordless = true))
    }
}
