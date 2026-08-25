package dev.termish.ui

import dev.termish.data.ConnectionMode
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
                connectionMode = ConnectionMode.SSH,
                forceSshReconnect = false,
            ),
        )
        assertEquals(
            BackgroundReconnectAction.RECONNECT,
            backgroundReconnectAction(
                sessionId = "same-host-session-1",
                activeSessionIds = setOf("same-host-session-1"),
                autoReconnect = true,
                status = ConnStatus.CLOSED,
                connectionMode = ConnectionMode.SSH,
                forceSshReconnect = false,
            ),
        )
    }

    @Test
    fun staleSshSocketIsRebuiltWhenPlatformCannotKeepItAlive() {
        assertEquals(
            BackgroundReconnectAction.REBUILD,
            backgroundReconnectAction(
                sessionId = "ssh-1",
                activeSessionIds = setOf("ssh-1"),
                autoReconnect = true,
                status = ConnStatus.CONNECTED,
                connectionMode = ConnectionMode.SSH,
                forceSshReconnect = true,
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
                connectionMode = ConnectionMode.MOSH,
                forceSshReconnect = true,
            ),
        )
    }
}
