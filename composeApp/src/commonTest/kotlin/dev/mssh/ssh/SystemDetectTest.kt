package dev.mssh.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** 自动探测输出解析（Termius 式系统识别）。 */
class SystemDetectTest {

    @Test
    fun parsesOsReleaseId() {
        assertEquals(
            "ubuntu",
            detectSystemFromOutput(
                """
                NAME="Ubuntu"
                ID=ubuntu
                VERSION_ID="24.04"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun parsesQuotedOsReleaseId() {
        assertEquals(
            "debian",
            detectSystemFromOutput(
                """
                PRETTY_NAME="Debian GNU/Linux 12 (bookworm)"
                ID="debian"
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun prefersOsReleaseOverUname() {
        assertEquals(
            "centos",
            detectSystemFromOutput(
                """
                ID="centos"
                Linux
                """.trimIndent(),
            ),
        )
    }

    @Test
    fun fallsBackToUnameDarwin() {
        assertEquals("macos", detectSystemFromOutput("Darwin"))
    }

    @Test
    fun fallsBackToUnameLinux() {
        // 无 /etc/os-release 的最小系统（alpine 容器、busybox 等）
        assertEquals("linux", detectSystemFromOutput("Linux"))
    }

    @Test
    fun unknownOutputReturnsNull() {
        assertNull(detectSystemFromOutput(""))
        assertNull(detectSystemFromOutput("some weird banner\n"))
    }
}
