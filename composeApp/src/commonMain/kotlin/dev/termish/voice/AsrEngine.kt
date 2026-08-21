package dev.termish.voice

import dev.termish.data.AsrProvider
import dev.termish.data.AsrProviderType

/**
 * 语音识别引擎抽象（可插拔 provider）：
 * 一次「点击开始 → 流式说话 → 结束发送」会话的引擎接口。
 * 各 provider（火山引擎流式识别等）实现本接口，由 [createAsrEngine] 工厂按
 * [AsrProvider.type] 创建。未来新增服务（阿里云/讯飞/本地 Whisper 等）只需：
 * 1. AsrProviderType 加枚举值；2. 新引擎实现本接口；3. 工厂加分支。
 *
 * 回调统一在平台线程触发，UI 层自行切主线程。
 */
interface AsrEngine {
    enum class State { IDLE, CONNECTING, LISTENING, FINALIZING, DONE, ERROR }

    /** 状态变化回调（平台线程）。 */
    var onState: ((State) -> Unit)?
    /** 中间结果（平台线程）：每包增量全量文本，可实时上屏。 */
    var onPartial: ((String) -> Unit)?
    /** 最终识别文本（平台线程）。 */
    var onFinalText: ((String) -> Unit)?
    /** 错误（平台线程）。 */
    var onError: ((String) -> Unit)?

    val state: State

    /** 开始会话（建连/准备）；成功后进入 LISTENING 并回调 onState。 */
    fun start()

    /** 流式发送一包 PCM（16kHz/16bit/mono，约 200ms）。 */
    fun sendPcm(data: ByteArray)

    /** 结束说话：等待最终结果并回调 onFinalText（或 onError）。 */
    fun finish()

    /** 放弃（误触/取消）：直接关闭，不触发结果回调。 */
    fun abort()
}

/** 按 provider 类型创建引擎实例。 */
fun createAsrEngine(provider: AsrProvider, apiKey: String): AsrEngine = when (provider.type) {
    AsrProviderType.VOLC_STREAMING -> VolcStreamingAsrEngine(
        apiKey = apiKey,
        resourceId = provider.resourceId.ifBlank { VolcAsrProtocol.DEFAULT_RESOURCE_ID },
        ws = createVoiceWebSocket(),
    )
}
