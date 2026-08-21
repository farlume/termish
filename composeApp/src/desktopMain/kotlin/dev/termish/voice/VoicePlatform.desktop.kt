package dev.termish.voice

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import dev.termish.util.TermLog
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.nio.ByteBuffer
import java.util.concurrent.CompletionStage
import java.util.concurrent.TimeUnit
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.DataLine
import javax.sound.sampled.TargetDataLine

@Composable
actual fun rememberMicPermissionRequester(): MicPermissionRequester =
    remember { DesktopMicPermission }

private object DesktopMicPermission : MicPermissionRequester {
    override fun request(onResult: (Boolean) -> Unit) = onResult(true)
}

// ---- 录音：Java Sound TargetDataLine 16kHz / PCM16 / mono ----

private const val SAMPLE_RATE = 16000f
private const val FRAME_MS = 200

actual class MicrophoneRecorder actual constructor() {
    private var line: TargetDataLine? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    actual fun start(onData: (ByteArray) -> Unit, onError: (String) -> Unit): Boolean {
        if (running) return true
        val format = AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED,
            SAMPLE_RATE, 16, 1, 2, SAMPLE_RATE, false, // little-endian (pcm_s16le)
        )
        val l = try {
            val info = DataLine.Info(TargetDataLine::class.java, format)
            AudioSystem.getLine(info) as TargetDataLine
        } catch (e: Exception) {
            onError("未找到麦克风设备")
            return false
        }
        try {
            l.open(format)
        } catch (e: Exception) {
            onError("麦克风打开失败")
            return false
        }
        line = l
        running = true
        val frameBytes = (SAMPLE_RATE * 2 * FRAME_MS / 1000).toInt()
        thread = Thread {
            try {
                l.start()
                val buf = ByteArray(frameBytes)
                while (running) {
                    val n = l.read(buf, 0, buf.size)
                    if (n > 0) onData(buf.copyOf(n))
                }
            } catch (e: Exception) {
                if (running) {
                    TermLog.w("voice") { "record error: $e" }
                    onError("录音失败")
                }
            } finally {
                l.stop()
                l.close()
            }
        }.apply {
            name = "termish-mic"
            isDaemon = true
            start()
        }
        return true
    }

    actual fun stop() {
        running = false
        thread?.join(500)
        thread = null
        line = null
    }
}

// ---- WebSocket：java.net.http（JDK 11+，desktop 专用） ----

actual fun createVoiceWebSocket(): VoiceWebSocket = JdkVoiceWebSocket()

private class JdkVoiceWebSocket : VoiceWebSocket {
    private val client = HttpClient.newBuilder()
        .connectTimeout(java.time.Duration.ofSeconds(10))
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
        val builder = client.newWebSocketBuilder()
        headers.forEach { (k, v) -> builder.header(k, v) }
        try {
            ws = builder.buildAsync(URI.create(url), object : WebSocket.Listener {
                override fun onOpen(webSocket: WebSocket) {
                    onOpen()
                }

                override fun onBinary(webSocket: WebSocket, data: ByteBuffer, last: Boolean): CompletionStage<*>? {
                    val bytes = ByteArray(data.remaining())
                    data.get(bytes)
                    onMessage(bytes)
                    return null
                }

                override fun onError(webSocket: WebSocket, error: Throwable) {
                    onError(error.message ?: error.javaClass.simpleName)
                }

                override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                    onClosed()
                    return null
                }
            }).get(10, TimeUnit.SECONDS)
        } catch (e: Exception) {
            onError(e.message ?: "连接失败")
        }
    }

    override fun send(bytes: ByteArray) {
        ws?.sendBinary(ByteBuffer.wrap(bytes), true)
    }

    override fun close() {
        try {
            ws?.sendClose(WebSocket.NORMAL_CLOSURE, null)
        } catch (_: Exception) {
        }
        ws = null
    }
}
