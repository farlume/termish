package dev.termish.screen

import dev.termish.ssh.SshExecChannel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

class ScreenTcpSessionBehaviorTest {
    @Test
    fun `explicit busy rejection does not trigger disconnect retry`() =
        runBlocking {
            val closed = CompletableDeferred<Unit>()
            var readCount = 0
            var disconnectCount = 0
            val channel =
                object : SshExecChannel {
                    override fun read(): ByteArray? =
                        if (readCount++ == 0) {
                            byteArrayOf(
                                'T'.code.toByte(),
                                'H'.code.toByte(),
                                'S'.code.toByte(),
                                '1'.code.toByte(),
                                SCREEN_TCP_STATUS_BUSY.toByte(),
                            )
                        } else {
                            null
                        }

                    override fun write(data: ByteArray) = Unit

                    override fun close() {
                        closed.complete(Unit)
                    }
                }
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val session =
                ScreenTcpSession(
                    channel = channel,
                    scope = scope,
                    authToken = "a1".repeat(32),
                    onVideoPacket = {},
                    onDisconnected = { disconnectCount++ },
                    onStatus = { it != SCREEN_TCP_STATUS_BUSY },
                )

            session.start()
            withTimeout(2_000) { closed.await() }

            assertFalse(session.isActive())
            assertEquals(0, disconnectCount)
            scope.cancel()
        }
}
