package dev.mssh.ssh

/** iOS 引擎工厂（libssh2 + OpenSSL 静态链接）。 */
actual fun createSshSession(connection: SshConnection, callbacks: SshCallbacks): SshSession =
    SshSessionLibssh2(connection, callbacks)
