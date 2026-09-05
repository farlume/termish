package dev.termish.ssh

/** JVM（Android）引擎工厂。 */
actual fun createSshSession(
    connection: SshConnection,
    callbacks: SshCallbacks,
): SshSession = SshSessionSshj(connection, callbacks)
