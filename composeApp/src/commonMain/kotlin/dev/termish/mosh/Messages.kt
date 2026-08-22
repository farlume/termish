package dev.termish.mosh

/**
 * mosh 1.4.0 线上消息的编解码（transportinstruction.proto / userinput.proto /
 * hostinput.proto 的手工对应物，见 proto 文件注释）。
 */

internal const val MOSH_PROTOCOL_VERSION = 2

/** TransportBuffers.Instruction：一个 SSP 指令（加密数据报的载荷单元）。 */
internal class TransportInstruction(
    var protocolVersion: Int = MOSH_PROTOCOL_VERSION,
    var oldNum: ULong = 0u,
    var newNum: ULong = 0u,
    var ackNum: ULong = 0u,
    var throwawayNum: ULong = 0u,
    var diff: ByteArray = ByteArray(0),
    var chaff: ByteArray = ByteArray(0),
) {
    fun serialize(): ByteArray {
        val w = ProtoWriter()
        w.varint(1, protocolVersion.toULong())
        w.varint(2, oldNum)
        w.varint(3, newNum)
        w.varint(4, ackNum)
        w.varint(5, throwawayNum)
        w.bytes(6, diff)
        w.bytes(7, chaff)
        return w.toByteArray()
    }

    /** 与协议 id 递增判定保持一致：比较除 diff 外的全部字段。 */
    fun sameHeaderAs(other: TransportInstruction): Boolean =
        oldNum == other.oldNum &&
            newNum == other.newNum &&
            ackNum == other.ackNum &&
            throwawayNum == other.throwawayNum &&
            protocolVersion == other.protocolVersion &&
            chaff.contentEquals(other.chaff)

    companion object {
        fun parse(data: ByteArray): TransportInstruction {
            val inst = TransportInstruction(protocolVersion = 0)
            val r = ProtoReader(data)
            while (true) {
                val (field, wire) = r.nextTag() ?: break
                when (field) {
                    1 -> inst.protocolVersion = r.readVarint().toInt()
                    2 -> inst.oldNum = r.readVarint()
                    3 -> inst.newNum = r.readVarint()
                    4 -> inst.ackNum = r.readVarint()
                    5 -> inst.throwawayNum = r.readVarint()
                    6 -> inst.diff = r.readBytes()
                    7 -> inst.chaff = r.readBytes()
                    else -> r.skip(wire)
                }
            }
            return inst
        }
    }
}

/** ClientBuffers.UserMessage 里的一条指令。 */
internal sealed class UserEventOut {
    class Keystrokes(
        val keys: ByteArray,
    ) : UserEventOut()

    class Resize(
        val width: Int,
        val height: Int,
    ) : UserEventOut()
}

/** 编码 ClientBuffers.UserMessage（keystroke 扩展相邻合并，与 mosh 一致）。 */
internal fun encodeUserMessage(events: List<UserEventOut>): ByteArray {
    val outer = ProtoWriter()
    var pendingKeys: ByteArray? = null

    fun flushKeys() {
        val keys = pendingKeys ?: return
        pendingKeys = null
        val stroke = ProtoWriter()
        stroke.bytes(4, keys) // Keystroke.keys = 4
        val inst = ProtoWriter()
        inst.message(2, stroke) // Instruction.keystroke 扩展 = 2
        outer.message(1, inst) // UserMessage.instruction = 1
    }

    for (e in events) {
        when (e) {
            is UserEventOut.Keystrokes -> {
                pendingKeys = (pendingKeys ?: ByteArray(0)) + e.keys
            }
            is UserEventOut.Resize -> {
                flushKeys()
                val resize = ProtoWriter()
                resize.varint(5, e.width.toULong()) // ResizeMessage.width = 5
                resize.varint(6, e.height.toULong()) // ResizeMessage.height = 6
                val inst = ProtoWriter()
                inst.message(3, resize) // Instruction.resize 扩展 = 3
                outer.message(1, inst)
            }
        }
    }
    flushKeys()
    return outer.toByteArray()
}

/** HostBuffers.HostMessage 解析后的一条指令。 */
internal sealed class HostEventIn {
    class HostBytes(
        val bytes: ByteArray,
    ) : HostEventIn()

    class Resize(
        val width: Int,
        val height: Int,
    ) : HostEventIn()

    class EchoAck(
        val echoAckNum: ULong,
    ) : HostEventIn()
}

internal fun decodeHostMessage(data: ByteArray): List<HostEventIn> {
    val out = ArrayList<HostEventIn>()
    val r = ProtoReader(data)
    while (true) {
        val (field, wire) = r.nextTag() ?: break
        if (field != 1 || wire != 2) {
            r.skip(wire)
            continue
        }
        val instBytes = r.readBytes()
        val ir = ProtoReader(instBytes)
        while (true) {
            val (f, w) = ir.nextTag() ?: break
            when (f) {
                2 -> { // HostBytes 扩展
                    val hb = ProtoReader(ir.readBytes())
                    while (true) {
                        val (hf, hw) = hb.nextTag() ?: break
                        if (hf == 4) out.add(HostEventIn.HostBytes(hb.readBytes())) else hb.skip(hw)
                    }
                }
                3 -> { // Resize 扩展
                    var width = 0
                    var height = 0
                    val rs = ProtoReader(ir.readBytes())
                    while (true) {
                        val (rf, rw) = rs.nextTag() ?: break
                        when (rf) {
                            5 -> width = rs.readVarint().toInt()
                            6 -> height = rs.readVarint().toInt()
                            else -> rs.skip(rw)
                        }
                    }
                    out.add(HostEventIn.Resize(width, height))
                }
                7 -> { // EchoAck 扩展
                    val ea = ProtoReader(ir.readBytes())
                    while (true) {
                        val (ef, ew) = ea.nextTag() ?: break
                        if (ef == 8) out.add(HostEventIn.EchoAck(ea.readVarint())) else ea.skip(ew)
                    }
                }
                else -> ir.skip(w)
            }
        }
    }
    return out
}
