package dev.termish.ui

import dev.termish.data.ConnectionMode
import dev.termish.data.Host
import dev.termish.data.HostAuthMethod
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

/** 会话凭据签名：任一连接参数变化即失效；敏感字段只保留哈希。 */
class CredentialSignatureTest {
    private fun host() =
        Host(
            id = "h1",
            name = "dev",
            hostname = "example.com",
            port = 2222,
            username = "root",
            authMethod = HostAuthMethod.KEY_OR_PASSWORD,
            startupCommand = "tmux new -A -s main",
            connectionMode = ConnectionMode.MOSH,
            moshUdpPort = 60001,
            moshThemeSync = true,
        )

    @Test
    fun deterministicForSameInputs() {
        assertEquals(credentialSignature(host(), "pw", "pem"), credentialSignature(host(), "pw", "pem"))
    }

    @Test
    fun changesWhenPasswordChanges() {
        assertNotEquals(credentialSignature(host(), "pw1", "pem"), credentialSignature(host(), "pw2", "pem"))
    }

    @Test
    fun changesWhenPrivateKeyChanges() {
        assertNotEquals(credentialSignature(host(), "pw", "pem1"), credentialSignature(host(), "pw", "pem2"))
    }

    @Test
    fun changesWhenConnectionParametersChange() {
        assertNotEquals(
            credentialSignature(host(), "pw", "pem"),
            credentialSignature(host().copy(moshUdpPort = 60002), "pw", "pem"),
        )
        assertNotEquals(
            credentialSignature(host(), "pw", "pem"),
            credentialSignature(host().copy(startupCommand = "tmux attach"), "pw", "pem"),
        )
        assertNotEquals(
            credentialSignature(host(), "pw", "pem"),
            credentialSignature(host().copy(connectionMode = ConnectionMode.SSH), "pw", "pem"),
        )
    }

    @Test
    fun doesNotLeakSecrets() {
        val signature = credentialSignature(host(), "super-secret-pw", "super-secret-pem")
        assertFalse(signature.contains("super-secret-pw"))
        assertFalse(signature.contains("super-secret-pem"))
    }

    @Test
    fun nullAndEmptyCredentialsDiffer() {
        assertNotEquals(credentialSignature(host(), null, null), credentialSignature(host(), "", ""))
    }
}
