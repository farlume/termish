package dev.mssh.ssh

/** Reader over an SSH-format byte array (RFC 4251). */
class SshReader(val data: ByteArray) {
    var pos: Int = 0

    fun remaining(): Int = data.size - pos

    fun readByte(): Int {
        if (pos >= data.size) throw SshException("SSH 数据包截断 (byte)")
        return data[pos++].toInt() and 0xff
    }

    fun readBoolean(): Boolean = readByte() != 0

    fun readUInt32(): Long {
        if (pos + 4 > data.size) throw SshException("SSH 数据包截断 (uint32)")
        var v = 0L
        for (i in 0 until 4) v = (v shl 8) or (data[pos++].toLong() and 0xff)
        return v
    }

    fun readString(): ByteArray {
        val len = readUInt32()
        if (len > Int.MAX_VALUE || pos + len > data.size) throw SshException("SSH 数据包截断 (string)")
        val out = data.copyOfRange(pos, pos + len.toInt())
        pos += len.toInt()
        return out
    }

    fun readStringUtf8(): String = readString().decodeToString()

    /** mpint — big-endian two's complement; returns byte array (unsigned, minimal). */
    fun readMpint(): ByteArray {
        val raw = readString()
        if (raw.isEmpty()) return ByteArray(0)
        var start = 0
        if (raw[0].toInt() == 0) start = 1 // strip leading zero
        return raw.copyOfRange(start, raw.size)
    }

    fun readBytes(n: Int): ByteArray {
        if (n < 0 || pos + n > data.size) throw SshException("SSH 数据包截断 (raw)")
        val out = data.copyOfRange(pos, pos + n)
        pos += n
        return out
    }

    fun readNameList(): List<String> = readStringUtf8().split(",").filter { it.isNotEmpty() }
}

/** Writer for SSH-format data. */
class SshWriter {
    private val out = ArrayList<Byte>(512)

    fun byte(v: Int): SshWriter {
        out.add(v.toByte())
        return this
    }

    fun boolean(v: Boolean): SshWriter = byte(if (v) 1 else 0)

    fun uint32(v: Long): SshWriter {
        byte((v ushr 24).toInt())
        byte((v ushr 16).toInt())
        byte((v ushr 8).toInt())
        byte(v.toInt())
        return this
    }

    fun string(s: ByteArray): SshWriter {
        uint32(s.size.toLong())
        for (b in s) out.add(b)
        return this
    }

    fun stringUtf8(s: String): SshWriter = string(s.encodeToByteArray())

    fun raw(b: ByteArray): SshWriter {
        for (x in b) out.add(x)
        return this
    }

    fun toByteArray(): ByteArray = ByteArray(out.size) { out[it] }

    fun size(): Int = out.size
}

/** Convert an unsigned big-endian byte array into a signed mpint byte array. */
fun toMpint(bytes: ByteArray): ByteArray {
    if (bytes.isEmpty() || bytes[0].toInt() and 0x80 == 0) return bytes
    return ByteArray(bytes.size + 1).also { it[0] = 0; bytes.copyInto(it, 1) }
}
