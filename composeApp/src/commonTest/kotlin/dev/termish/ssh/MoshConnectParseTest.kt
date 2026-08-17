package dev.termish.ssh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** mosh-server 引导输出的 MOSH CONNECT 解析。 */
class MoshConnectParseTest {

    @Test
    fun parsesStandardMoshConnectLine() {
        assertEquals(60001 to "AbCdEf==", parseMoshConnect("MOSH CONNECT 60001 AbCdEf=="))
    }

    @Test
    fun parsesFixedPort() {
        assertEquals(12345 to "key", parseMoshConnect("MOSH CONNECT 12345 key"))
    }

    @Test
    fun findsMoshLineInsideBootstrapOutput() {
        val output = "NAME=\"Ubuntu\"\nVERSION=\"24.04\"\nLinux\nMOSH CONNECT 60042 dG9rZW4="
        assertEquals(60042 to "dG9rZW4=", parseMoshConnect(output))
    }

    @Test
    fun rejectsNonNumericPort() {
        assertNull(parseMoshConnect("MOSH CONNECT abc key"))
    }

    @Test
    fun rejectsMissingKey() {
        assertNull(parseMoshConnect("MOSH CONNECT 60001"))
    }

    @Test
    fun rejectsGarbage() {
        assertNull(parseMoshConnect(""))
        assertNull(parseMoshConnect("hello world"))
        assertNull(parseMoshConnect("MOSH CONNECT"))
    }
}
