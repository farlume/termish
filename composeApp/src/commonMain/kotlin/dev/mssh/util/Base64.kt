package dev.mssh.util

/** RFC 4648 标准 Base64 编码（无换行）。 */
fun base64Encode(data: ByteArray): String {
    val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/"
    val sb = StringBuilder((data.size + 2) / 3 * 4)
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
