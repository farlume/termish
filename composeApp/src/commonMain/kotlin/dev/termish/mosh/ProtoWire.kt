package dev.termish.mosh

/**
 * 极简 proto2 wire 编解码：仅覆盖 mosh 用到的 varint / bytes / 嵌入消息。
 * 避免引入 protobuf 依赖（KMP iOS 端 protobuf-lite 接入成本高且没必要）。
 */
internal class ProtoWriter {
    private val out = ArrayList<Byte>()

    fun varint(field: Int, value: ULong) {
        tag(field, 0)
        var v = value
        while (v >= 0x80uL) {
            out.add(((v and 0x7FuL) or 0x80uL).toByte())
            v = v shr 7
        }
        out.add(v.toByte())
    }

    fun bytes(field: Int, data: ByteArray) {
        tag(field, 2)
        varint0(data.size.toULong())
        data.forEach { out.add(it) }
    }

    fun message(field: Int, body: ProtoWriter) {
        bytes(field, body.toByteArray())
    }

    private fun tag(field: Int, wire: Int) = varint0(((field shl 3) or wire).toULong())

    private fun varint0(v0: ULong) {
        var v = v0
        while (v >= 0x80uL) {
            out.add(((v and 0x7FuL) or 0x80uL).toByte())
            v = v shr 7
        }
        out.add(v.toByte())
    }

    fun toByteArray(): ByteArray = out.toByteArray()
}

internal class ProtoReader(data: ByteArray) {
    private val buf = data
    private var pos = 0

    val hasMore: Boolean get() = pos < buf.size

    /** 返回 (fieldNumber, wireType)；无更多数据时 null。 */
    fun nextTag(): Pair<Int, Int>? {
        if (pos >= buf.size) return null
        val v = readVarint()
        return (v shr 3).toInt() to (v and 0x7uL).toInt()
    }

    fun readVarint(): ULong {
        var result = 0uL
        var shift = 0
        while (pos < buf.size) {
            val b = buf[pos++].toInt() and 0xff
            result = result or ((b and 0x7f).toULong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
        }
        throw IllegalArgumentException("truncated varint")
    }

    fun readBytes(): ByteArray {
        val len = readVarint().toInt()
        require(pos + len <= buf.size) { "truncated bytes field" }
        val r = buf.copyOfRange(pos, pos + len)
        pos += len
        return r
    }

    fun skip(wireType: Int) {
        when (wireType) {
            0 -> readVarint()
            1 -> pos += 8
            2 -> readBytes()
            5 -> pos += 4
            else -> throw IllegalArgumentException("unsupported wire type $wireType")
        }
    }
}
