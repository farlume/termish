package dev.termish.screen

import dev.termish.ssh.AuthPrompt
import dev.termish.ssh.HostKeyInfo
import dev.termish.ssh.SshCallbacks
import dev.termish.ssh.SshConnection
import dev.termish.ssh.createSftpSession
import dev.termish.ssh.createSshSession
import java.io.File
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assume.assumeTrue

/** Actual mobile SFTP + SSH direct-tcpip engines against the bundled Rust service. */
class ScreenNativeServiceIntegrationTest {
    @Test
    fun `uploaded native service streams packets accepted by the mobile parser`() {
        val port = System.getenv("Termish_TEST_PORT")?.toInt() ?: 22222
        assumeTrue(
            "SKIP: local test sshd unavailable",
            runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) } }.isSuccess,
        )
        val key = File(System.getenv("Termish_TEST_KEY") ?: "/tmp/termish_test/client")
        assumeTrue("SKIP: ephemeral SSH key unavailable", key.exists())
        val callbacks =
            object : SshCallbacks {
                override suspend fun onOutput(data: ByteArray) {}

                override suspend fun onStderr(data: ByteArray) {}

                override fun onExitStatus(status: Int) {}

                override fun onClosed(reason: String?) {}

                override suspend fun onPrompt(prompt: AuthPrompt): List<String>? = null

                override fun verifyHostKey(hostKey: HostKeyInfo): Boolean = true
            }
        val connection =
            SshConnection(
                host = "127.0.0.1",
                port = port,
                username = checkNotNull(System.getProperty("user.name")),
                privateKeyPem = key.readText(),
            )
        val ssh = createSshSession(connection, callbacks)
        var directory: String? = null
        try {
            assertNotNull(ssh.connectAuthOnly())
            val os = ssh.runCommand("uname -s")?.trim()
            val arch = ssh.runCommand("uname -m")?.trim()
            val payload = ScreenServiceAssets.binaryFor(os, arch)
            assumeTrue("SKIP: no bundled Rust payload for host", payload != null)
            val binary =
                listOf(File("screenService/build/binaries"), File("../screenService/build/binaries"))
                    .map { File(it, checkNotNull(payload).filename) }
                    .firstOrNull { it.exists() }
            assertNotNull(binary, "bundled native payload must exist")
            assertEquals(checkNotNull(payload).size.toLong(), binary.length())
            val ffmpeg = ssh.runCommand("command -v ffmpeg")?.trim()
            assumeTrue("SKIP: synthetic encoder unavailable", !ffmpeg.isNullOrBlank())
            directory = ssh.runCommand("umask 077; mktemp -d /tmp/termish-native-ssh.XXXXXX")?.trim()
            val root = checkNotNull(directory)
            val remoteBinary = "$root/screen-service"
            val sftp = createSftpSession(connection, callbacks)
            try {
                val bytes = binary.readBytes()
                var offset = 0
                sftp.upload(remoteBinary, bytes.size.toLong()) {
                    if (offset == bytes.size) {
                        null
                    } else {
                        val end = (offset + 64 * 1024).coerceAtMost(bytes.size)
                        bytes.copyOfRange(offset, end).also { offset = end }
                    }
                }
            } finally {
                sftp.close()
            }
            val probePort = ServerSocket(0).use { it.localPort - 2 }
            val token = "a".repeat(64)
            val config =
                buildJsonObject {
                    put("port", probePort)
                    put("ffmpeg", "$root/encoder")
                    put("token_file", "$root/token")
                    put("log_file", "$root/log")
                    put("encoder_pid_file", "$root/encoder.pid")
                    put("stream_config_file", "$root/quality")
                }.toString()
            val encoder =
                "#!/bin/sh\nexec ${screenShellQuote(checkNotNull(ffmpeg))} " +
                    "-hide_banner -loglevel error -re -f lavfi -i testsrc2=size=320x180:rate=30 " +
                    "-c:v libx264 -preset ultrafast -tune zerolatency -x264-params aud=1:keyint=15 " +
                    "-pix_fmt yuv420p -f h264 -\n"
            val setup =
                "chmod 700 ${screenShellQuote(remoteBinary)}; " +
                    "printf '%s' ${screenShellQuote(token)} > ${screenShellQuote("$root/token")}; " +
                    "printf '%s' ${screenShellQuote(config)} > ${screenShellQuote("$root/screen-service.json")}; " +
                    "printf '%s' ${screenShellQuote(encoder)} > ${screenShellQuote("$root/encoder")}; " +
                    "chmod 700 ${screenShellQuote("$root/encoder")}; " +
                    "${screenShellQuote(remoteBinary)} --check-config >/dev/null && " +
                    "${screenShellQuote(remoteBinary)} --version"
            assertEquals(ScreenSession.RELAY_VERSION.toString(), ssh.runCommand(setup)?.trim())
            val service = ssh.startExecRaw("echo ${'$'}${'$'} > ${screenShellQuote("$root/service.pid")}; exec ${screenShellQuote(remoteBinary)}")
            assertNotNull(service)
            try {
                val deadline = System.currentTimeMillis() + 5_000
                var ready = false
                while (!ready && System.currentTimeMillis() < deadline) {
                    ready = runCatching { Socket("127.0.0.1", probePort).close() }.isSuccess
                    if (!ready) Thread.sleep(30)
                }
                assertTrue(ready, "uploaded Rust service must start")
                val channel = ssh.openDirectTcpip("127.0.0.1", probePort + 2)
                assertNotNull(channel)
                try {
                    channel.write(frameScreenTcpAuth(token))
                    val packets = mutableListOf<ScreenVideoPacket>()
                    val parser = ScreenTcpFrameParser({}, { packets += parseScreenVideoPacket(it) })
                    while (packets.size < 10) {
                        channel.write(
                            frameScreenTcpControl(
                                ByteArray(17).also { data ->
                                    "THC1".encodeToByteArray().copyInto(data)
                                    data[4] = 13
                                },
                            ),
                        )
                        val data = channel.read()
                        assertNotNull(data, "native video must continue")
                        parser.push(data)
                    }
                    assertEquals(0, packets.first().sequence)
                    assertTrue(packets.first().isKeyframe)
                    assertTrue(packets.zipWithNext().all { (a, b) -> b.sequence == a.sequence + 1 })
                    val nalParser = H264Stream.AnnexBParser()
                    val types = mutableSetOf<Int>()
                    for (packet in packets) {
                        var nal = nalParser.push(packet.data)
                        while (nal != null) {
                            types += nal.type
                            nal = nalParser.drain()
                        }
                    }
                    assertTrue(types.containsAll(listOf(5, 7, 8, 9)), "complete H264 access units required")
                } finally {
                    channel.close()
                }
                val stopped = System.currentTimeMillis() + 3_000
                while (System.currentTimeMillis() < stopped) {
                    if (ssh.runCommand("test ! -f ${screenShellQuote("$root/encoder.pid")} && echo STOPPED")?.contains("STOPPED") == true) break
                    Thread.sleep(30)
                }
                assertTrue(ssh.runCommand("test ! -f ${screenShellQuote("$root/encoder.pid")} && echo STOPPED")?.contains("STOPPED") == true)
            } finally {
                // Only terminate the isolated uploaded service, never the user's installed one.
                val pid = ssh.runCommand("cat ${screenShellQuote("$root/service.pid")}")?.trim()?.toIntOrNull()
                if (pid != null) ssh.runCommand("kill -TERM $pid")
                service.close()
            }
        } finally {
            directory?.let { ssh.runCommand("rm -rf ${screenShellQuote(it)}") }
            ssh.close()
        }
    }
}
