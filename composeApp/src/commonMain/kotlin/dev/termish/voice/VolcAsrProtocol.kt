package dev.termish.voice

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 火山引擎「大模型流式语音识别 API」二进制 WebSocket 协议（纯 Kotlin，零平台依赖）。
 *
 * 文档：https://docs.volcengine.com/docs/6561/2630027 （双向流式语音识别 WebSocket，bigmodel_async）
 *
 * 帧格式（整数一律大端）：
 *   - 客户端请求：Header(4B) + Payload size(4B) + Payload
 *   - 服务端响应：Header(4B) + Sequence(4B) + Payload size(4B) + Payload
 *
 * Header 单字节位域：
 *   byte0: Protocol version(4b)=0001 | Header size(4b)=0001（实际 header 字节数 = 值 × 4）
 *   byte1: Message type(4b) | Message type specific flags(4b)
 *   byte2: Serialization(4b) | Compression(4b)
 *   byte3: Reserved(8b)
 *
 * Message type：0001 = full client request；0010 = audio only request；
 *             1001 = full server response；1111 = 服务端错误。
 * flags：0000 = 普通包；0010 = 最后一包（负包）。
 */
object VolcAsrProtocol {
    /** 双向流式（优化版）接口地址。 */
    const val WSS_URL = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async"

    /** 默认资源 ID：豆包流式语音识别模型 2.0 小时版（新版控制台推荐）。 */
    const val DEFAULT_RESOURCE_ID = "volc.seedasr.sauc.duration"

    /** 所有可用资源 ID（设置页选择用）。 */
    val RESOURCE_IDS =
        listOf(
            "volc.seedasr.sauc.duration",
            "volc.seedasr.sauc.concurrent",
            "volc.bigasr.sauc.duration",
            "volc.bigasr.sauc.concurrent",
        )

    // ---- header 字节 ----

    /** full client request：JSON 序列化、不压缩。 */
    fun fullRequestHeader(): ByteArray = byteArrayOf(0x11, 0x10, 0x10, 0x00)

    /** audio only request；[last] = 最后一包（负包，flags=0010）。 */
    fun audioHeader(last: Boolean): ByteArray = if (last) byteArrayOf(0x11, 0x22, 0x00, 0x00) else byteArrayOf(0x11, 0x20, 0x00, 0x00)

    /** 组帧：header + 大端 payload size + payload。 */
    fun frame(
        header: ByteArray,
        payload: ByteArray,
    ): ByteArray {
        val out = ByteArray(8 + payload.size)
        header.copyInto(out, 0)
        val n = payload.size
        out[4] = (n ushr 24).toByte()
        out[5] = (n ushr 16).toByte()
        out[6] = (n ushr 8).toByte()
        out[7] = n.toByte()
        payload.copyInto(out, 8)
        return out
    }

    /**
     * full client request 的 JSON payload：音频元数据 + 请求参数。
     * 音频统一 pcm_s16le / 16kHz / 单声道（录音端保证该格式）。
     */
    fun fullRequestJson(uid: String): String {
        val params =
            buildJsonObject {
                // enable_punc 默认 true（标点让命令/文本更可读）；enable_itn 默认 true
                put("model_name", "bigmodel")
                put("enable_punc", true)
                put("enable_itn", true)
            }
        return buildString {
            append("{\"user\":{\"uid\":\"")
            append(uid)
            append("\"},\"audio\":{\"format\":\"pcm\",\"rate\":16000,\"bits\":16,\"channel\":1},\"request\":")
            append(params.toString())
            append('}')
        }
    }

    // ---- 响应解析 ----

    /** 消息类型（byte1 高 4 位）。 */
    fun messageType(bytes: ByteArray): Int = (bytes[1].toInt() ushr 4) and 0xF

    private fun be32(
        bytes: ByteArray,
        offset: Int,
    ): Long =
        ((bytes[offset].toLong() and 0xff) shl 24) or
            ((bytes[offset + 1].toLong() and 0xff) shl 16) or
            ((bytes[offset + 2].toLong() and 0xff) shl 8) or
            (bytes[offset + 3].toLong() and 0xff)

    @Serializable
    data class AsrResponse(
        /** 成功时字段缺失（实测 bigmodel_async 无 code）；非 0 为错误。 */
        val code: Int? = null,
        val event: Int? = null,
        @SerialName("is_last_package") val isLastPackage: Boolean = false,
        @SerialName("payload_sequence") val payloadSequence: Int = 0,
        @SerialName("audio_info") val audioInfo: JsonElement? = null,
        /**
         * 实测 bigmodel_async：result 为**顶层对象** {text, utterances, additions}；
         * 老接口（bigmodel）在 payload_msg.result（list）——两者都兼容。
         */
        val result: JsonElement? = null,
        @SerialName("payload_msg") val payloadMsg: AsrPayloadMsg? = null,
    )

    @Serializable
    data class AsrPayloadMsg(
        val message: String? = null,
        /** 老接口（bigmodel）的分句 list；JsonElement 兼容对象/list 两种结构。 */
        val result: JsonElement? = null,
    )

    /** 解析结果：最终包标记（帧 flags=3 或响应 is_last_package）。 */
    data class ParsedServerFrame(
        val isLastPackage: Boolean,
        val response: AsrResponse,
    )

    /**
     * 解析完整服务端帧（type=1001）。
     * 帧布局：Header(4B) + [Sequence(4B)] + Payload size(4B) + Payload。
     * sequence 是否出现由 header flags 决定：flags=0 无 sequence（首个应答），
     * flags=1/3 有 sequence（后续包）——实测 bigmodel_async 两种都发。
     * 最终包帧 flags=0x3（byte1=0x93），响应体里没有 is_last_package 字段。
     */
    fun parseServerFrame(bytes: ByteArray): ParsedServerFrame {
        val flags = bytes[1].toInt() and 0x0f
        val hasSequence = flags == 1 || flags == 3
        val sizeOffset = if (hasSequence) 8 else 4
        val payloadOffset = sizeOffset + 4
        val size = be32(bytes, sizeOffset).toInt().coerceAtMost(bytes.size - payloadOffset)
        val jsonStr = bytes.decodeToString(payloadOffset, payloadOffset + size)
        val resp = Json { ignoreUnknownKeys = true }.decodeFromString<AsrResponse>(jsonStr)
        return ParsedServerFrame(isLastPackage = flags == 3 || resp.isLastPackage, response = resp)
    }

    /**
     * 从识别响应中取最终文本。
     * 实测结构：顶层 result 为对象 {text: "..."}（bigmodel_async）；老接口为
     * payload_msg.result 分句 list——两者都兼容（list 按顺序拼接全部元素）。
     */
    fun finalText(resp: AsrResponse): String? {
        val result = resp.result ?: resp.payloadMsg?.result ?: return null
        val text =
            when (result) {
                is JsonObject -> result["text"]?.jsonPrimitive?.contentOrNull
                is JsonArray ->
                    result
                        .mapNotNull { el ->
                            (el as? JsonObject)?.get("text")?.jsonPrimitive?.contentOrNull
                        }.joinToString("")
                else -> null
            }
        return text?.takeIf { it.isNotBlank() }
    }

    /**
     * 解析服务端错误帧（type=1111）为可读文案。
     * 帧布局：Header(4B) + Error code(4B) + Error size(4B) + Error message(UTF8)；
     * 部分实现为 code + payload，这里两种都容错。
     */
    fun parseErrorFrame(bytes: ByteArray): String {
        val code = be32(bytes, 4)
        var message = ""
        if (bytes.size >= 12) {
            val size = be32(bytes, 8).toInt()
            if (size in 1..(bytes.size - 12)) {
                message = bytes.decodeToString(12, 12 + size)
            } else {
                message = bytes.decodeToString(8, bytes.size)
            }
        }
        return if (message.isBlank()) "server error code=$code" else "$message (code=$code)"
    }
}
