package dev.mssh.ssh

import dev.mssh.crypto.Sha256
import dev.mssh.crypto.X25519
import kotlin.random.Random

object SshMsg {
    const val DISCONNECT = 1
    const val IGNORE = 2
    const val UNIMPLEMENTED = 3
    const val DEBUG = 4
    const val SERVICE_REQUEST = 5
    const val SERVICE_ACCEPT = 6
    const val EXT_INFO = 7
    const val KEXINIT = 20
    const val NEWKEYS = 21
    const val KEX_ECDH_INIT = 30
    const val KEX_ECDH_REPLY = 31
    const val USERAUTH_REQUEST = 50
    const val USERAUTH_FAILURE = 51
    const val USERAUTH_SUCCESS = 52
    const val USERAUTH_BANNER = 53
    const val USERAUTH_INFO_REQUEST = 60
    const val USERAUTH_INFO_RESPONSE = 61
    const val GLOBAL_REQUEST = 80
    const val REQUEST_SUCCESS = 81
    const val REQUEST_FAILURE = 82
    const val CHANNEL_OPEN = 90
    const val CHANNEL_OPEN_CONFIRMATION = 91
    const val CHANNEL_OPEN_FAILURE = 92
    const val CHANNEL_WINDOW_ADJUST = 93
    const val CHANNEL_DATA = 94
    const val CHANNEL_EXTENDED_DATA = 95
    const val CHANNEL_EOF = 96
    const val CHANNEL_CLOSE = 97
    const val CHANNEL_REQUEST = 98
    const val CHANNEL_SUCCESS = 99
    const val CHANNEL_FAILURE = 100
}

data class HostKeyInfo(
    val algorithm: String,
    val blob: ByteArray,
    val fingerprintSha256: String,
)

/**
 * SSH transport: version exchange, curve25519-sha256 key exchange,
 * chacha20-poly1305@openssh.com packet encryption, server-initiated rekey.
 */
class SshTransport(private val socket: SshSocket) {

    private val readBuf = ByteArray(64 * 1024)
    private var readPos = 0
    private var readLen = 0

    private var sendCipher: Chacha20Poly1305Ssh? = null
    private var recvCipher: Chacha20Poly1305Ssh? = null

    var remoteVersion: String = ""
        private set
    var hostKey: HostKeyInfo? = null
        private set
    var kexAlgorithms: List<String> = emptyList()
        private set

    private var sessionId: ByteArray? = null
    private var clientKexInitPayload: ByteArray = ByteArray(0)
    private var inKex = false

    fun connect(host: String, port: Int) {
        socket.connect(host, port)
        val clientVersion = "SSH-2.0-MSSH_0.1"
        socket.write((clientVersion + "\r\n").encodeToByteArray())
        remoteVersion = readVersionLine().trim()
        if (!remoteVersion.startsWith("SSH-2.0-") && !remoteVersion.startsWith("SSH-1.99-")) {
            throw SshException("服务器不支持 SSH 2.0: $remoteVersion")
        }
    }

    fun performKex() {
        inKex = true
        try {
            kexRound()
        } finally {
            inKex = false
        }
    }

    private fun sshString(b: ByteArray): ByteArray {
        val out = ByteArray(4 + b.size)
        var v = b.size.toLong()
        for (i in 0 until 4) out[i] = (v ushr (24 - 8 * i)).toByte()
        b.copyInto(out, 4)
        return out
    }

    /** mpint (SSH bignum2): uint32 length + bytes, with a 0x00 sign byte if the top bit is set. */
    private fun sshMpint(b: ByteArray): ByteArray {
        val pad = b.isNotEmpty() && (b[0].toInt() and 0x80) != 0
        val out = ByteArray(4 + b.size + (if (pad) 1 else 0))
        var v = (b.size + (if (pad) 1 else 0)).toLong()
        for (i in 0 until 4) out[i] = (v ushr (24 - 8 * i)).toByte()
        var off = 4
        if (pad) out[off++] = 0
        b.copyInto(out, off)
        return out
    }

    private fun u32(b: Long): ByteArray {
        val out = ByteArray(4)
        for (i in 0 until 4) out[i] = (b ushr (24 - 8 * i)).toByte()
        return out
    }

    /**
     * OpenSSH-compatible exchange hash. Note this deliberately follows the
     * OpenSSH wire behaviour (version strings without CRLF as SSH strings,
     * KEXINIT payloads prefixed with a 4-byte length, host key / ephemeral
     * keys as SSH strings) rather than the RFC 4253 prose.
     */
    private fun exchangeHash(
        clientKex: ByteArray,
        serverKex: ByteArray,
        hostKeyBlob: ByteArray,
        clientPub: ByteArray,
        serverPub: ByteArray,
        k: ByteArray,
    ): ByteArray {
        val vc = sshString("SSH-2.0-MSSH_0.1".encodeToByteArray())
        val vs = sshString(remoteVersion.encodeToByteArray())
        val ic = u32(clientKex.size.toLong()) + clientKex
        val isx = u32(serverKex.size.toLong()) + serverKex
        return Sha256.digest(
            vc + vs + ic + isx + sshString(hostKeyBlob) +
                    sshString(clientPub) + sshString(serverPub) + sshMpint(k)
        )
    }

    private fun kexRound() {
        sendKexInit()
        val serverInit = receiveRaw()
        if ((serverInit[0].toInt() and 0xff) != SshMsg.KEXINIT) {
            throw SshException("协议错误：期望 KEXINIT")
        }
        val algos = parseKexInit(serverInit)
        kexAlgorithms = algos["kex"] ?: emptyList()
        if (algos["kex"]?.none { it == "curve25519-sha256" || it == "curve25519-sha256@libssh.org" } == true) {
            throw SshException("服务器不支持 curve25519-sha256 密钥交换")
        }
        val hostKeyAlgo = algos["hostkey"]?.firstOrNull { it.startsWith("ssh-ed25519") || it.startsWith("ssh-rsa") || it.startsWith("ecdsa") }
            ?: throw SshException("无法识别服务器的 host key 算法")

        val privateKey = ByteArray(32) { Random.nextBytes(1)[0] }
        val (privScalar, pubKey) = X25519.generateKeyPair(privateKey)

        val initW = SshWriter()
        initW.byte(SshMsg.KEX_ECDH_INIT)
        initW.string(pubKey)
        sendRaw(initW.toByteArray())

        val reply = receiveRaw()
        val r = SshReader(reply)
        if (r.readByte() != SshMsg.KEX_ECDH_REPLY) throw SshException("协议错误：期望 KEX_ECDH_REPLY")
        val hostKeyBlob = r.readString()
        val qs = r.readString()
        val signature = r.readString()

        val k = X25519.scalarMult(privScalar, qs)
        if (k.all { it == 0.toByte() }) throw SshException("密钥交换失败：共享密钥为零")

        val h = exchangeHash(clientKexInitPayload, serverInit, hostKeyBlob, pubKey, qs, k)
        hostKey = HostKeyInfo(hostKeyAlgo, hostKeyBlob, "SHA256:" + base64Encode(Sha256.digest(hostKeyBlob)))

        // NEWKEYS is exchanged in the OLD (plaintext) mode; ciphers take effect
        // only for packets that follow it (RFC 4253 §7.2).
        val newSend = deriveCipher(k, h, 'C'.code.toByte())
        val newRecv = deriveCipher(k, h, 'D'.code.toByte())

        sendRaw(byteArrayOf(SshMsg.NEWKEYS.toByte()))
        val nk = receiveRaw()
        if ((nk[0].toInt() and 0xff) != SshMsg.NEWKEYS) throw SshException("协议错误：期望 NEWKEYS")

        sendCipher = newSend
        recvCipher = newRecv
        recvSeq = 0
        sendSeq = 0
    }

    private var sendSeq = 0L
    private var recvSeq = 0L

    /**
     * Builds the chacha20-poly1305 cipher pair for one direction.
     * OpenSSH expands the 32-byte KDF output to 64 bytes via
     *   K2 = SHA256(K || H || K1)
     * The first 32 bytes become the main key, the second the length key.
     */
    private fun deriveCipher(k: ByteArray, h: ByteArray, encLetter: Byte): Chacha20Poly1305Ssh {
        val sid = sessionId ?: h
        sessionId = sid
        val k1 = Sha256.digest(k + h + byteArrayOf(encLetter) + sid)
        val k2 = Sha256.digest(k + h + k1)
        return Chacha20Poly1305Ssh(k1, k2)
    }

    fun sessionIdBytes(): ByteArray = sessionId ?: throw SshException("会话尚未建立")

    // ---------- public packet API ----------

    fun sendPacket(payload: ByteArray) = sendRaw(payload)

    /**
     * Reads the next packet, transparently handling rekey and transport
     * messages. Returns the payload of non-transport packets.
     */
    fun receivePacket(): ByteArray {
        while (true) {
            val payload = receiveRaw()
            when (payload[0].toInt() and 0xff) {
                SshMsg.DISCONNECT -> {
                    val r = SshReader(payload)
                    r.readByte()
                    val code = r.readUInt32()
                    val desc = r.readStringUtf8()
                    throw SshException("服务器断开连接 ($code): $desc")
                }
                SshMsg.IGNORE, SshMsg.DEBUG, SshMsg.UNIMPLEMENTED -> {
                    // silently ignore
                }
                SshMsg.KEXINIT -> {
                    // server-initiated rekey
                    if (!inKex) {
                        kexRound()
                    }
                }
                else -> return payload
            }
        }
    }

    // ---------- framing ----------

    private fun sendKexInit() {
        val w = SshWriter()
        w.byte(SshMsg.KEXINIT)
        w.raw(ByteArray(16) { Random.nextBytes(1)[0] })
        w.stringUtf8("kex-strict-c-v00@openssh.com,ext-info-c,curve25519-sha256,curve25519-sha256@libssh.org")
        w.stringUtf8("ssh-ed25519,rsa-sha2-512,rsa-sha2-256,ssh-rsa,ecdsa-sha2-nistp256")
        w.stringUtf8("chacha20-poly1305@openssh.com")
        w.stringUtf8("chacha20-poly1305@openssh.com")
        w.stringUtf8("hmac-sha2-256,hmac-sha1")
        w.stringUtf8("hmac-sha2-256,hmac-sha1")
        w.stringUtf8("none")
        w.stringUtf8("none")
        w.stringUtf8("")
        w.stringUtf8("")
        w.boolean(false)
        w.uint32(0)
        clientKexInitPayload = w.toByteArray()
        sendRaw(clientKexInitPayload)
    }

    private fun parseKexInit(payload: ByteArray): Map<String, List<String>> {
        val r = SshReader(payload)
        r.readByte()
        r.readBytes(16)
        return mapOf(
            "kex" to r.readNameList(),
            "hostkey" to r.readNameList(),
            "enc" to (r.readNameList() + r.readNameList()).distinct(),
            "mac" to (r.readNameList() + r.readNameList()).distinct(),
        )
    }

    private fun sendRaw(payload: ByteArray) {
        val c = sendCipher
        if (c == null) {
            sendPlain(payload)
            return
        }
        // AEAD framing (OpenSSH): packet_length % block_size == 0
        var padLen = (8 - (payload.size + 1) % 8) % 8
        if (padLen < 4) padLen += 8
        val total = payload.size + padLen + 1
        val seq = c.nextSeq()
        val encLen = c.encryptLength(total.toLong())
        val plain = ByteArray(total)
        plain[0] = padLen.toByte()
        payload.copyInto(plain, 1)
        val padding = Random.nextBytes(padLen)
        padding.copyInto(plain, 1 + payload.size)
        val out = ByteArray(4 + total + 16)
        encLen.copyInto(out, 0)
        val tag = c.encryptAndTag(seq, encLen, plain, out, 4)
        tag.copyInto(out, 4 + total)
        socket.write(out)
        sendSeq++
    }

    private fun sendPlain(payload: ByteArray) {
        var padLen = (8 - (payload.size + 5) % 8) % 8
        if (padLen < 4) padLen += 8
        val total = payload.size + padLen + 1
        val w = ByteArray(4 + total)
        var v = total.toLong()
        for (i in 0 until 4) w[i] = (v ushr (24 - 8 * i)).toByte()
        w[4] = padLen.toByte()
        payload.copyInto(w, 5)
        val padding = Random.nextBytes(padLen)
        padding.copyInto(w, 5 + payload.size)
        socket.write(w)
    }

    private fun receiveRaw(): ByteArray {
        val c = recvCipher
        if (c == null) {
            // plaintext framing
            ensure(4)
            val len = ((readBuf[readPos].toInt() and 0xff) shl 24) or
                    ((readBuf[readPos + 1].toInt() and 0xff) shl 16) or
                    ((readBuf[readPos + 2].toInt() and 0xff) shl 8) or
                    (readBuf[readPos + 3].toInt() and 0xff)
            if (len < 5 || len > 35000) throw SshException("无效的数据包长度: $len")
            readPos += 4
            val payload = readExact(len)
            return payload
        }
        ensure(4)
        val encLen = readBuf.copyOfRange(readPos, readPos + 4)
        readPos += 4
        val seq = c.nextSeq()
        val len = c.decryptLength(encLen).toInt()
        if (len < 5 || len > 35000) throw SshException("无效的数据包长度: $len")
        ensure(4 + len + 16)
        val ciphertext = readBuf.copyOfRange(readPos, readPos + len)
        readPos += len
        val tag = readBuf.copyOfRange(readPos, readPos + 16)
        readPos += 16
        val plain = c.decryptAndVerify(seq, encLen, ciphertext, tag)
        recvSeq++
        val padLen = plain[0].toInt() and 0xff
        if (padLen < 4 || padLen > plain.size - 1) throw SshException("无效的 padding 长度")
        return plain.copyOfRange(1, plain.size - padLen)
    }

    private fun readExact(totalLen: Int): ByteArray {
        val payload = ByteArray(totalLen)
        var off = 0
        while (off < totalLen) {
            ensure(1)
            val n = minOf(readLen - readPos, totalLen - off)
            readBuf.copyInto(payload, off, readPos, readPos + n)
            readPos += n
            off += n
        }
        val padLen = payload[0].toInt() and 0xff
        if (padLen < 4 || padLen > payload.size - 1) throw SshException("无效的 padding 长度")
        return payload.copyOfRange(1, payload.size - padLen)
    }

    private fun readVersionLine(): String {
        val sb = StringBuilder()
        while (true) {
            val b = readByteRaw()
            if (b < 0) throw SshException("连接被服务器关闭")
            sb.append(b.toChar())
            if (b == '\n'.code) break
            if (sb.length > 255) throw SshException("无效的 SSH 版本行")
        }
        return sb.toString()
    }

    private fun readByteRaw(): Int {
        ensure(1)
        return readBuf[readPos++].toInt() and 0xff
    }

    private fun ensure(n: Int) {
        while (readLen - readPos < n) {
            if (readPos > 0) {
                readBuf.copyInto(readBuf, 0, readPos, readLen)
                readLen -= readPos
                readPos = 0
            }
            if (readBuf.size - readLen == 0) throw SshException("内部缓冲区溢出")
            val r = socket.read(readBuf, readLen, readBuf.size - readLen)
            if (r < 0) throw SshException("连接已关闭")
            readLen += r
        }
    }

    fun disconnect() {
        try {
            socket.close()
        } catch (_: Exception) {
        }
    }

    fun isConnected(): Boolean = true

    private fun base64Encode(data: ByteArray): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
        val sb = StringBuilder()
        var i = 0
        while (i < data.size) {
            val b0 = data[i].toInt() and 0xff
            val b1 = if (i + 1 < data.size) data[i + 1].toInt() and 0xff else 0
            val b2 = if (i + 2 < data.size) data[i + 2].toInt() and 0xff else 0
            sb.append(chars[b0 ushr 2])
            sb.append(chars[((b0 and 0x3) shl 4) or (b1 ushr 4)])
            sb.append(if (i + 1 < data.size) chars[((b1 and 0xf) shl 2) or (b2 ushr 6)] else '=')
            sb.append(if (i + 2 < data.size) chars[b2 and 0x3f] else '=')
            i += 3
        }
        return sb.toString()
    }
}
