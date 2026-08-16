package dev.mssh.mosh

import dev.mssh.util.base64Decode

/**
 * mosh 数据报加密会话：AES-128-OCB，nonce = 4 字节 0 || 8 字节大端序号，
 * 序号最高位为方向位（TO_CLIENT=1）。线上格式：
 *   8B nonce 低位 || OCB 密文 || 16B tag
 * 明文格式：2B timestamp || 2B timestamp_reply || 分片载荷。
 */
internal class MoshCryptoSession(keyBase64: String) {
    private val ocb = Ocb(base64Decode(keyBase64))

    class PlainPacket(
        val seq: ULong,
        val timestamp: Int, // uint16
        val timestampReply: Int, // uint16
        val payload: ByteArray,
    )

    /** 加密一个待发数据报（客户端方向，方向位 0）。 */
    fun encrypt(seq: ULong, timestamp: Int, timestampReply: Int, payload: ByteArray): ByteArray {
        val plain = ByteArray(4 + payload.size)
        plain[0] = (timestamp shr 8).toByte()
        plain[1] = timestamp.toByte()
        plain[2] = (timestampReply shr 8).toByte()
        plain[3] = timestampReply.toByte()
        payload.copyInto(plain, 4)
        val nonce = nonceBytes(seq)
        val ct = ocb.encrypt(nonce, plain)
        val out = ByteArray(8 + ct.size)
        nonce.copyInto(out, 0, 4, 12) // 低 8 字节序号
        ct.copyInto(out, 8)
        return out
    }

    /** 解密一个收到的数据报；tag 校验失败返回 null。 */
    fun decrypt(datagram: ByteArray): PlainPacket? {
        if (datagram.size < 24) return null
        val nonce = ByteArray(12)
        datagram.copyInto(nonce, 4, 0, 8)
        val plain = ocb.decrypt(nonce, datagram.copyOfRange(8, datagram.size)) ?: return null
        if (plain.size < 4) return null
        val seq = (0..7).fold(0uL) { acc, i -> (acc shl 8) or (datagram[i].toInt() and 0xff).toULong() }
        return PlainPacket(
            seq = seq,
            timestamp = ((plain[0].toInt() and 0xff) shl 8) or (plain[1].toInt() and 0xff),
            timestampReply = ((plain[2].toInt() and 0xff) shl 8) or (plain[3].toInt() and 0xff),
            payload = plain.copyOfRange(4, plain.size),
        )
    }

    private fun nonceBytes(seq: ULong): ByteArray {
        val n = ByteArray(12)
        for (i in 0..7) {
            n[4 + i] = (seq shr (56 - 8 * i)).toByte()
        }
        return n
    }
}
