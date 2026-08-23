package dev.termish.mosh

/**
 * SSP 分片层：Fragment 线上格式 = 8B instruction id || 2B (final<<15 | num) || 内容。
 * 线上格式与 mosh 的 SSP 分片协议一致（互操作所必需），实现为独立 Kotlin 重写。
 */
internal class Fragment(
    val id: ULong,
    val num: Int,
    val final: Boolean,
    val contents: ByteArray,
) {
    fun toBytes(): ByteArray {
        val out = ByteArray(10 + contents.size)
        for (i in 0..7) out[i] = (id shr (56 - 8 * i)).toByte()
        val combined = (if (final) 0x8000 else 0) or num
        out[8] = (combined shr 8).toByte()
        out[9] = combined.toByte()
        contents.copyInto(out, 10)
        return out
    }

    companion object {
        const val HEADER_LEN = 10

        fun parse(data: ByteArray): Fragment {
            require(data.size >= HEADER_LEN)
            val id = (0..7).fold(0uL) { acc, i -> (acc shl 8) or (data[i].toInt() and 0xff).toULong() }
            val combined = ((data[8].toInt() and 0xff) shl 8) or (data[9].toInt() and 0xff)
            return Fragment(
                id = id,
                num = combined and 0x7fff,
                final = combined and 0x8000 != 0,
                contents = data.copyOfRange(HEADER_LEN, data.size),
            )
        }
    }
}

/** 收端重组：同一 id 的分片收齐后解压并还原 Instruction。 */
internal class FragmentAssembly {
    private var fragments = arrayOfNulls<Fragment>(0)
    private var currentId: ULong? = null
    private var arrived = 0
    private var total = -1

    fun addFragment(frag: Fragment): Boolean {
        if (currentId != frag.id) {
            fragments = arrayOfNulls(frag.num + 1)
            fragments[frag.num] = frag
            arrived = 1
            total = -1
            currentId = frag.id
        } else {
            if (fragments.size > frag.num && fragments[frag.num] != null) {
                // 重复分片忽略
            } else {
                if (fragments.size < frag.num + 1) {
                    fragments = fragments.copyOf(frag.num + 1)
                }
                fragments[frag.num] = frag
                arrived++
            }
        }
        if (frag.final) {
            total = frag.num + 1
            if (fragments.size > total) fragments = fragments.copyOf(total)
        }
        return total != -1 && arrived == total
    }

    fun assembly(): TransportInstruction = TransportInstruction.parse(assembleBytes())

    /** 通用：返回重组后解压的原始字节（视频流等非 SSP 负载用，由调用方自行解析）。 */
    fun assembleBytes(): ByteArray {
        val encoded = fragments.joinToByteArray()
        fragments = arrayOfNulls(0)
        arrived = 0
        total = -1
        return zlibDecompress(encoded)
    }

    private fun Array<Fragment?>.joinToByteArray(): ByteArray {
        val parts = map { it!!.contents }
        val out = ByteArray(parts.sumOf { it.size })
        var off = 0
        for (p in parts) {
            p.copyInto(out, off)
            off += p.size
        }
        return out
    }
}

/** 发端分片：SSP 用同 header 复用 instruction id；通用负载每次新 id。内容按 MTU 切片。 */
internal class Fragmenter {
    private var nextInstructionId = 0uL
    private var lastInstruction: TransportInstruction? = null
    private var lastMtu = -1

    /** SSP 专用：TransportInstruction 分片（同 header 复用 instruction id，重发去重）。 */
    fun makeFragments(
        inst: TransportInstruction,
        mtu: Int,
    ): List<Fragment> {
        val usable = mtu - Fragment.HEADER_LEN
        val last = lastInstruction
        // SSP 语义：同 old/new 的重发必须携带相同 diff（防重发内容漂移）
        if (last != null && inst.oldNum == last.oldNum && inst.newNum == last.newNum) {
            check(inst.diff.contentEquals(last.diff)) { "同 old/new 的 diff 不一致" }
        }
        if (last == null || !inst.sameHeaderAs(last) || lastMtu != usable) {
            nextInstructionId++
        }
        lastInstruction = inst
        lastMtu = usable
        return sliceToFragments(inst.serialize(), mtu, nextInstructionId)
    }

    /** 通用：任意 payload 分片（每个包分配新 instruction id，无 SSP 复用语义）。 */
    fun makeFragments(
        payload: ByteArray,
        mtu: Int,
    ): List<Fragment> {
        nextInstructionId++
        return sliceToFragments(payload, mtu, nextInstructionId)
    }

    /** 压缩 + 按 MTU 切片（公共逻辑）。 */
    private fun sliceToFragments(
        payload: ByteArray,
        mtu: Int,
        id: ULong,
    ): List<Fragment> {
        val usable = mtu - Fragment.HEADER_LEN
        val compressed = zlibCompress(payload)
        val out = ArrayList<Fragment>()
        var num = 0
        var off = 0
        while (off < compressed.size) {
            check(num < 0x8000) { "分片数超限（fragment_num 高位是 final 标志位）" }
            val end = minOf(off + usable, compressed.size)
            out.add(Fragment(id, num++, end == compressed.size, compressed.copyOfRange(off, end)))
            off = end
        }
        return out
    }
}
