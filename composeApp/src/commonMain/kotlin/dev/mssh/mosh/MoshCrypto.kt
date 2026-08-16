package dev.mssh.mosh

import dev.mssh.util.base64Decode
import dev.mssh.util.base64Encode

/**
 * mosh 数据报加密会话：AES-128-OCB，nonce = 4 字节 0 || 8 字节大端序号，
 * 序号最高位为方向位（TO_CLIENT=1）。线上格式：
 *   8B nonce 低位 || OCB 密文 || 16B tag
 * 明文格式：2B timestamp || 2B timestamp_reply || 分片载荷。
 */
internal class MoshCryptoSession(keyBase64: String) {
    private val ocb: Ocb
    /** 加密块计数（mosh 协议实现：达到 2^47 块即终止会话，防 OCB 生日界）。 */
    private var blocksEncrypted = 0L

    init {
        // mosh Base64Key：22 字符（16 字节 key 去掉 == 填充），且要求规范编码，
        // 以拒绝尾部非零 bit 的伪 key（mosh 协议实现 Base64Key 同款校验）
        require(keyBase64.length == 22) { "mosh key 必须为 22 字符 base64" }
        val key = base64Decode(keyBase64)
        require(key.size == 16) { "mosh key 必须解码为 16 字节" }
        require(base64Encode(key).startsWith(keyBase64)) { "mosh key 编码不规范" }
        ocb = Ocb(key)
    }

    class PlainPacket(
        val seq: ULong,
        val timestamp: Int, // uint16
        val timestampReply: Int, // uint16
        val payload: ByteArray,
    )

    /** 加密一个待发数据报（客户端方向，方向位 0）。 */
    fun encrypt(seq: ULong, timestamp: Int, timestampReply: Int, payload: ByteArray): ByteArray {
        require(seq and DIRECTION_BIT == 0uL) { "客户端发往服务器方向位必须为 0" }
        val plain = ByteArray(4 + payload.size)
        plain[0] = (timestamp shr 8).toByte()
        plain[1] = timestamp.toByte()
        plain[2] = (timestampReply shr 8).toByte()
        plain[3] = timestampReply.toByte()
        payload.copyInto(plain, 4)
        val nonce = nonceBytes(seq and SEQUENCE_MASK)
        val ct = ocb.encrypt(nonce, plain)
        blocksEncrypted += (plain.size + 15) / 16
        if (blocksEncrypted shr 47 != 0L) {
            throw IllegalStateException("OCB 加密块数达到 2^47 上限，会话终止")
        }
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
        val raw = (0..7).fold(0uL) { acc, i -> (acc shl 8) or (datagram[i].toInt() and 0xff).toULong() }
        // 客户端只收 TO_CLIENT（最高位=1）；掩掉方向位后才是单调序号。
        // 方向位校验与 mosh recv_one 的 dos_assert(direction==期望方向) 对应，
        // 防止把客户端自己发过的包重放回来当合法数据
        if (raw and DIRECTION_BIT == 0uL) return null
        val seq = raw and SEQUENCE_MASK
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

    companion object {
        /** 数据报序号最高位为方向位（协议常量：TO_SERVER=0 / TO_CLIENT=1）。 */
        private val DIRECTION_BIT: ULong = 1uL shl 63
        private val SEQUENCE_MASK: ULong = ULong.MAX_VALUE xor DIRECTION_BIT
    }
}
