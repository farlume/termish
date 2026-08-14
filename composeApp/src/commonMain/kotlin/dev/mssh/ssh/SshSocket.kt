package dev.mssh.ssh

/**
 * Blocking TCP socket abstraction over expect/actual.
 * Reads/writes are blocking and run on a background dispatcher.
 */
expect class SshSocket() {

    /** Blocking connect; throws [SshException] on failure. */
    fun connect(host: String, port: Int)

    /** Blocking write of all bytes. */
    fun write(data: ByteArray)

    /** Blocking read into [buffer] starting at [offset], returns bytes read or -1 on EOF. */
    fun read(buffer: ByteArray, offset: Int, length: Int): Int

    fun close()
}

class SshException(message: String, cause: Throwable? = null) : Exception(message, cause)
