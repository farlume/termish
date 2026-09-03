package dev.termish.ui

import dev.termish.util.ForegroundSshRecovery
import kotlin.test.Test
import kotlin.test.assertEquals

class SessionRecoveryTest {
    @Test
    fun onlyTheExactSessionActiveAtBackgroundCanReconnect() {
        assertEquals(
            BackgroundReconnectAction.NONE,
            backgroundReconnectAction(
                sessionId = "same-host-session-2",
                activeSessionIds = setOf("same-host-session-1"),
                autoReconnect = true,
                status = ConnStatus.CLOSED,
                usesMoshTransport = false,
                recovery = ForegroundSshRecovery.KEEP,
            ),
        )
        assertEquals(
            BackgroundReconnectAction.RECONNECT,
            backgroundReconnectAction(
                sessionId = "same-host-session-1",
                activeSessionIds = setOf("same-host-session-1"),
                autoReconnect = true,
                status = ConnStatus.CLOSED,
                usesMoshTransport = false,
                recovery = ForegroundSshRecovery.KEEP,
            ),
        )
    }

    @Test
    fun androidServiceLossVerifiesSshBeforeReconnecting() {
        assertEquals(
            BackgroundReconnectAction.VERIFY,
            backgroundReconnectAction(
                sessionId = "ssh-1",
                activeSessionIds = setOf("ssh-1"),
                autoReconnect = true,
                status = ConnStatus.CONNECTED,
                usesMoshTransport = false,
                recovery = ForegroundSshRecovery.VERIFY,
            ),
        )
    }

    @Test
    fun suspendedPlatformRebuildsSshDirectly() {
        assertEquals(
            BackgroundReconnectAction.REBUILD,
            backgroundReconnectAction(
                sessionId = "ssh-1",
                activeSessionIds = setOf("ssh-1"),
                autoReconnect = true,
                status = ConnStatus.CONNECTED,
                usesMoshTransport = false,
                recovery = ForegroundSshRecovery.REBUILD,
            ),
        )
    }

    @Test
    fun moshKeepsItsRoamingStateOnForeground() {
        assertEquals(
            BackgroundReconnectAction.NONE,
            backgroundReconnectAction(
                sessionId = "mosh-1",
                activeSessionIds = setOf("mosh-1"),
                autoReconnect = true,
                status = ConnStatus.CONNECTED,
                usesMoshTransport = true,
                recovery = ForegroundSshRecovery.REBUILD,
            ),
        )
    }

    @Test
    fun disabledAutoReconnectNeverRecoversInBackground() {
        assertEquals(
            BackgroundReconnectAction.NONE,
            backgroundReconnectAction(
                sessionId = "ssh-1",
                activeSessionIds = setOf("ssh-1"),
                autoReconnect = false,
                status = ConnStatus.CLOSED,
                usesMoshTransport = false,
                recovery = ForegroundSshRecovery.REBUILD,
            ),
        )
    }
}
