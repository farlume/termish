package dev.termish.voice

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import dev.termish.AppContext
import dev.termish.util.TermLog
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString.Companion.toByteString

@Composable
actual fun rememberMicPermissionRequester(): MicPermissionRequester {
    val context = LocalContext.current
    val launcher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            pendingResult?.invoke(granted)
            pendingResult = null
        }
    return remember(context, launcher) {
        object : MicPermissionRequester {
            override fun request(onResult: (Boolean) -> Unit) {
                val granted =
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                if (granted) {
                    onResult(true)
                } else {
                    pendingResult = onResult
                    launcher.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }
    }
}

/** 权限结果暂存（launcher 回调与 request 调用分离）。 */
private var pendingResult: ((Boolean) -> Unit)? = null

// ---- 录音：AudioRecord 16kHz / PCM16 / mono ----

/** 单包音频时长（文档建议 100–200ms，200ms 性能最优）。 */
private const val FRAME_MS = 200
private const val SAMPLE_RATE = 16000

actual class MicrophoneRecorder actual constructor() {
    private var record: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile private var running = false

    actual fun start(
        onData: (ByteArray) -> Unit,
        onError: (String) -> Unit,
    ): Boolean {
        if (running) return true
        val minBuf =
            AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
            )
        if (minBuf <= 0) {
            onError("麦克风不可用")
            return false
        }
        val rec =
            try {
                // lint MissingPermission：此处显式复查运行时权限（UI 层已请求过）
                val granted =
                    ContextCompat.checkSelfPermission(
                        AppContext.get(),
                        Manifest.permission.RECORD_AUDIO,
                    ) == PackageManager.PERMISSION_GRANTED
                if (!granted) {
                    onError("麦克风权限未授予")
                    return false
                }
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf * 2,
                )
            } catch (e: SecurityException) {
                onError("麦克风权限未授予")
                return false
            } catch (e: Exception) {
                onError("麦克风不可用")
                return false
            }
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            onError("麦克风不可用")
            return false
        }
        record = rec
        running = true
        val frameBytes = SAMPLE_RATE * 2 * FRAME_MS / 1000
        thread =
            Thread {
                try {
                    rec.startRecording()
                    val buf = ByteArray(frameBytes)
                    while (running) {
                        val n = rec.read(buf, 0, buf.size)
                        if (n > 0) {
                            onData(buf.copyOf(n))
                        }
                    }
                } catch (e: Exception) {
                    if (running) {
                        TermLog.w("voice") { "record error: $e" }
                        onError("录音失败")
                    }
                } finally {
                    try {
                        rec.stop()
                    } catch (_: Exception) {
                    }
                    rec.release()
                }
            }.apply {
                name = "termish-mic"
                start()
            }
        return true
    }

    actual fun stop() {
        running = false
        thread?.join(500)
        thread = null
        record = null
    }
}

// ---- WebSocket：OkHttp（Android minSdk 26 无 java.net.http） ----

actual fun createVoiceWebSocket(): VoiceWebSocket = OkHttpVoiceWebSocket()

private class OkHttpVoiceWebSocket : VoiceWebSocket {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .pingInterval(20, TimeUnit.SECONDS)
            .build()
    private var ws: WebSocket? = null

    override fun connect(
        url: String,
        headers: Map<String, String>,
        onOpen: () -> Unit,
        onMessage: (ByteArray) -> Unit,
        onError: (String) -> Unit,
        onClosed: () -> Unit,
    ) {
        val req =
            Request
                .Builder()
                .url(url)
                .apply {
                    headers.forEach { (k, v) -> addHeader(k, v) }
                }.build()
        ws =
            client.newWebSocket(
                req,
                object : WebSocketListener() {
                    override fun onOpen(
                        webSocket: WebSocket,
                        response: Response,
                    ) = onOpen()

                    override fun onMessage(
                        webSocket: WebSocket,
                        bytes: okio.ByteString,
                    ) = onMessage(bytes.toByteArray())

                    override fun onFailure(
                        webSocket: WebSocket,
                        t: Throwable,
                        response: Response?,
                    ) {
                        // 连接失败消息常含完整 URL/IP/端口（如 Failed to connect to
                        // openspeech.bytedance.com/1.2.3.4:443）——用户侧只给友好
                        // 文案，原始错误进日志供诊断（用户反馈：错误把端口打印出来）
                        TermLog.w("voice") { "ws failure: $t" }
                        val friendly =
                            when (t) {
                                is java.net.ConnectException -> "无法连接语音识别服务，请检查网络"
                                is java.net.SocketTimeoutException -> "连接语音识别服务超时，请检查网络"
                                else -> "语音识别服务连接失败"
                            }
                        onError(friendly)
                    }

                    override fun onClosed(
                        webSocket: WebSocket,
                        code: Int,
                        reason: String,
                    ) = onClosed()
                },
            )
    }

    override fun send(bytes: ByteArray) {
        ws?.send(bytes.toByteString())
    }

    override fun close() {
        try {
            ws?.close(1000, null)
        } catch (_: Exception) {
        }
        client.dispatcher.executorService.shutdown()
    }
}
