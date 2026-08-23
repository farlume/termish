package dev.termish.screen

/**
 * H.264 Annex-B 字节流解析（纯 Kotlin，零平台依赖）：
 * - 按 start code（00 00 01 / 00 00 00 01）切分 NAL 单元
 * - 提取 SPS（nal_type=7）/ PPS（nal_type=8）——解码器 CSD 必需
 * - 识别关键帧（IDR，nal_type=5）与帧边界
 *
 * 远端（ffmpeg -f h264 -）输出裸 Annex-B：SPS/PPS 周期性重发
 * （ffmpeg 默认每个关键帧前携带），解码器可从流中任意点接入。
 */
object H264Stream {
    /** 一个 NAL 单元：类型 + 载荷（不含 start code）。 */
    data class Nal(
        val type: Int,
        val data: ByteArray,
    )

    /**
     * 从字节流中切出完整 NAL 单元；返回 null = 需更多数据。
     * 内部用字节数组缓冲（推流 read 每块 64KB，逐字节装箱的 ArrayList<Byte>
     * 每秒制造数百万垃圾对象——30fps 推流下 GC 压力会让画面卡顿）。
     */
    class AnnexBParser {
        private var buf = ByteArray(4096)
        private var size = 0
        private var startCodeLen = 0

        /** 追加数据并尝试取出下一个 NAL；空返回 null（剩余留在缓冲，[drain] 继续取）。 */
        fun push(bytes: ByteArray): Nal? {
            ensure(size + bytes.size)
            bytes.copyInto(buf, size)
            size += bytes.size
            return next()
        }

        /** 不追加数据，从现有缓冲继续提取 NAL（提取后剩余缓冲等下次数据）。 */
        fun drain(): Nal? = next()

        private fun ensure(need: Int) {
            if (need <= buf.size) return
            var cap = buf.size
            while (cap < need) cap = cap * 2
            buf = buf.copyOf(cap)
        }

        /** 从缓冲中尝试提取一个 NAL；数据不足返回 null（保留缓冲）。 */
        private fun next(): Nal? {
            // 1. 确保缓冲开头是 start code（丢弃杂散字节）
            while (true) {
                val idx = findStartCode(0)
                if (idx < 0) {
                    // 无完整 start code：保留末尾最多 3 字节（可能是 start code 前缀）
                    trimTail(3)
                    return null
                }
                if (idx > 0) {
                    buf.copyInto(buf, 0, idx, size)
                    size -= idx
                }
                // 4 字节 start code 判定须看 buf[0..2] 全 0：buf[2]==1 时是 3 字节
                // start code 后紧跟 NAL 头（头字节恰为 0x01 时 buf[3] 也为 1，
                // 会被误判成 4 字节、吃掉 NAL 头一字节导致该 NAL 损坏）
                startCodeLen =
                    if (size >= 4 &&
                        buf[0].toInt() == 0 &&
                        buf[1].toInt() == 0 &&
                        buf[2].toInt() == 0
                    ) {
                        4
                    } else {
                        3
                    }
                // 2. 找下一个 start code 作为 NAL 结束
                val nextIdx = findStartCode(startCodeLen)
                if (nextIdx < 0) {
                    // 尚未凑齐完整 NAL：保留全部缓冲（含 start code）等更多数据
                    return null
                }
                if (nextIdx == startCodeLen) {
                    // 连续 start code（空洞）：跳过前一个，重试
                    buf.copyInto(buf, 0, startCodeLen, size)
                    size -= startCodeLen
                    continue
                }
                val nalLen = nextIdx - startCodeLen
                val nal = ByteArray(nalLen)
                buf.copyInto(nal, 0, startCodeLen, nextIdx)
                buf.copyInto(buf, 0, nextIdx, size)
                size -= nextIdx
                return Nal(nal[0].toInt() and 0x1f, nal)
            }
        }

        private fun trimTail(keep: Int) {
            if (size <= keep) return
            buf.copyInto(buf, 0, size - keep, size)
            size = keep
        }

        private fun findStartCode(from: Int): Int {
            var i = from
            while (i + 2 < size) {
                if (buf[i].toInt() == 0 && buf[i + 1].toInt() == 0 && buf[i + 2].toInt() == 1) return i
                if (i + 3 < size &&
                    buf[i].toInt() == 0 &&
                    buf[i + 1].toInt() == 0 &&
                    buf[i + 2].toInt() == 0 &&
                    buf[i + 3].toInt() == 1
                ) {
                    return i
                }
                i++
            }
            return -1
        }
    }

    /** SPS/PPS 字节（Annex-B 形式，喂解码器 CSD）。 */
    data class ParameterSets(
        val sps: ByteArray?,
        val pps: ByteArray?,
    )

    /** 从完整 Annex-B 流中提取 SPS/PPS（含 start code 前缀，供 MediaCodec CSD）。 */
    fun extractParameterSets(annexB: ByteArray): ParameterSets {
        var sps: ByteArray? = null
        var pps: ByteArray? = null
        val parser = AnnexBParser()
        // push 返回值也是 NAL（首个），不能丢；随后 drain 缓冲剩余
        var nal = parser.push(annexB)
        while (nal != null) {
            when (nal.type) {
                7 -> sps = withStartCode(nal.data)
                8 -> pps = withStartCode(nal.data)
                else -> if (sps != null && pps != null) break
            }
            nal = parser.drain()
        }
        return ParameterSets(sps, pps)
    }

    internal fun withStartCode(nal: ByteArray): ByteArray {
        val out = ByteArray(nal.size + 4)
        out[0] = 0
        out[1] = 0
        out[2] = 0
        out[3] = 1
        nal.copyInto(out, 4)
        return out
    }

    /**
     * 从 SPS NAL 解析裁剪后宽高（H.264 exp-golomb；失败返回 null）。
     *
     * 同时接受裸 NAL 和 MediaCodec CSD 使用的 Annex-B 形式；读位前移除
     * emulation-prevention byte（00 00 03）。部分 OPPO/MTK 解码器在
     * configure 时要求 MediaFormat 带准确 width/height。
     */
    fun parseSpsDimensions(sps: ByteArray): Pair<Int, Int>? {
        val start =
            when {
                sps.size >= 4 &&
                    sps[0] == 0.toByte() &&
                    sps[1] == 0.toByte() &&
                    sps[2] == 0.toByte() &&
                    sps[3] == 1.toByte() -> 4
                sps.size >= 3 && sps[0] == 0.toByte() && sps[1] == 0.toByte() && sps[2] == 1.toByte() -> 3
                else -> 0
            }
        if (sps.size - start < 4) return null
        val rbsp = ByteArray(sps.size - start)
        var rbspSize = 0
        var zeroCount = 0
        for (i in start until sps.size) {
            val value = sps[i].toInt() and 0xff
            if (zeroCount >= 2 && value == 3) {
                zeroCount = 0
                continue
            }
            rbsp[rbspSize++] = sps[i]
            zeroCount = if (value == 0) zeroCount + 1 else 0
        }
        val data = rbsp.copyOf(rbspSize)
        return try {
            var bitPos = 0

            fun readBit(): Int {
                require(bitPos < data.size * 8)
                val b = data[bitPos / 8].toInt() and 0xff
                val v = (b ushr (7 - bitPos % 8)) and 1
                bitPos++
                return v
            }

            fun readBits(n: Int): Int {
                var v = 0
                repeat(n) { v = (v shl 1) or readBit() }
                return v
            }

            fun readUe(): Int {
                var zeros = 0
                while (readBit() == 0) {
                    zeros++
                    require(zeros < 31)
                }
                return (1 shl zeros) - 1 + readBits(zeros)
            }

            fun readSe(): Int {
                val codeNum = readUe()
                return if (codeNum and 1 == 0) -(codeNum / 2) else (codeNum + 1) / 2
            }

            fun skipScalingList(count: Int) {
                var lastScale = 8
                var nextScale = 8
                repeat(count) {
                    if (nextScale != 0) nextScale = (lastScale + readSe() + 256) % 256
                    if (nextScale != 0) lastScale = nextScale
                }
            }

            readBits(8) // NAL header
            val profile = readBits(8)
            readBits(8) // constraint flags
            readBits(8) // level_idc
            readUe() // seq_parameter_set_id
            var chromaFormat = 1
            var separateColourPlane = 0
            if (profile in setOf(100, 110, 122, 244, 44, 83, 86, 118, 128, 138, 139, 134, 135)) {
                chromaFormat = readUe()
                if (chromaFormat == 3) separateColourPlane = readBit()
                readUe() // bit_depth_luma_minus8
                readUe() // bit_depth_chroma_minus8
                readBit() // qpprime_y_zero_transform_bypass_flag
                if (readBit() == 1) {
                    repeat(if (chromaFormat == 3) 12 else 8) { index ->
                        if (readBit() == 1) skipScalingList(if (index < 6) 16 else 64)
                    }
                }
            }
            readUe() // log2_max_frame_num_minus4
            when (readUe()) { // pic_order_cnt_type
                0 -> readUe()
                1 -> {
                    readBit() // delta_pic_order_always_zero_flag
                    readSe() // offset_for_non_ref_pic
                    readSe() // offset_for_top_to_bottom_field
                    repeat(readUe()) { readSe() }
                }
            }
            readUe() // max_num_ref_frames
            readBit() // gaps_in_frame_num_value_allowed_flag
            val widthMbs = readUe() + 1
            val heightMapUnits = readUe() + 1
            val frameMbsOnly = readBit()
            if (frameMbsOnly == 0) readBit() // mb_adaptive_frame_field_flag
            readBit() // direct_8x8_inference_flag
            var cropLeft = 0
            var cropRight = 0
            var cropTop = 0
            var cropBottom = 0
            if (readBit() == 1) {
                cropLeft = readUe()
                cropRight = readUe()
                cropTop = readUe()
                cropBottom = readUe()
            }
            val chromaArrayType = if (separateColourPlane == 1) 0 else chromaFormat
            val subWidth = if (chromaArrayType == 1 || chromaArrayType == 2) 2 else 1
            val subHeight = if (chromaArrayType == 1) 2 else 1
            val cropUnitX = if (chromaArrayType == 0) 1 else subWidth
            val cropUnitY = if (chromaArrayType == 0) 2 - frameMbsOnly else subHeight * (2 - frameMbsOnly)
            val width = widthMbs * 16 - (cropLeft + cropRight) * cropUnitX
            val height = (2 - frameMbsOnly) * heightMapUnits * 16 - (cropTop + cropBottom) * cropUnitY
            require(width > 0 && height > 0)
            width to height
        } catch (_: Exception) {
            null
        }
    }

    /** nal_type → 名称（调试/日志）。 */
    fun typeName(type: Int): String =
        when (type) {
            1 -> "SLICE"
            5 -> "IDR"
            6 -> "SEI"
            7 -> "SPS"
            8 -> "PPS"
            else -> "T$type"
        }
}
