package dev.termish.voice

import androidx.compose.runtime.Composable

/**
 * 语音输入平台抽象（expect/actual）：
 * - [MicrophoneRecorder]：16kHz / PCM16 / 单声道 录音器
 * - [VoiceWebSocket]：二进制 WebSocket 客户端
 * - [rememberMicPermissionRequester]：麦克风权限请求（Android 运行时权限弹窗；
 *   iOS 走 AVAudioSession 内部请求；桌面恒授予）
 */

/** 麦克风权限请求器：request 幂等，已授予立即回调 true。 */
interface MicPermissionRequester {
    fun request(onResult: (Boolean) -> Unit)
}

@Composable
expect fun rememberMicPermissionRequester(): MicPermissionRequester

/**
 * 录音器：16kHz / 16bit / 单声道 PCM（火山 ASR 的标准输入格式）。
 * 平台线程回调 [onData]（约 200ms 一块）；[onError] 报告不可恢复错误
 * （无设备/权限被收回等），随后录音停止。
 */
expect class MicrophoneRecorder() {
    /** 启动录音；返回 false 表示无法启动（无设备/未授权，错误经 [onError] 给出）。 */
    fun start(onData: (ByteArray) -> Unit, onError: (String) -> Unit): Boolean
    fun stop()
}

/** 二进制 WebSocket：connect 后异步回调；send 线程安全；close 幂等。 */
interface VoiceWebSocket {
    fun connect(
        url: String,
        headers: Map<String, String>,
        onOpen: () -> Unit,
        onMessage: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        onClosed: () -> Unit,
    )
    fun send(bytes: ByteArray)
    fun close()
}

expect fun createVoiceWebSocket(): VoiceWebSocket
