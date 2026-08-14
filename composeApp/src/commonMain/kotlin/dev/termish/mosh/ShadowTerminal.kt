package dev.termish.mosh

import dev.termish.term.TerminalBuffer
import dev.termish.term.CellAttr
import dev.termish.term.TerminalEmulator
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * SSP 远端状态（对应服务端终端状态的客户端影子）：
 * 一个完整终端（解析器 + 屏幕缓冲），按 HostMessage diff 演化。
 *
 * 复用项目自研的纯 Kotlin 终端模拟器。mosh 的 Complete 不可复制解析器状态
 * （注释称 parser state irrelevant），分叉时用同一 buffer 深拷贝 + 新解析器：
 * new_frame 产生的 diff 是完整转义序列，不会在序列中间断帧。
 */
internal class ShadowTerminal internal constructor(
    val buffer: TerminalBuffer,
    private val emulator: TerminalEmulator,
    var echoAck: ULong,
) {
    /**
     * buffer 读写锁：会话协程（applyDiff / resize / fork）与 UI 拷贝线程
     * （copyContentFrom）并发访问同一 TerminalBuffer；不加锁时 resize 中途
     * 行 cells 与 cols 字段不同步，cloneLine 会越界崩溃（ArrayIndexOutOfBounds）。
     */
    internal val lock = Mutex()

    var onTitleChange: (String) -> Unit = {}
        set(v) {
            field = v
            emulator.onTitleChange = v
        }
    var onClipboardWrite: (String) -> Unit = {}
        set(v) {
            field = v
            emulator.onClipboardWrite = v
        }

    /** 应用 HostMessage diff（hostbytes → 终端；resize → 改尺寸；echoack → 记录）。
     *
     *  注意：本影子的 resize/alt 屏语义（xterm 式底锚定、回看保留、1049 恢复
     *  屏幕）有意【不】镜像 mosh 服务端——mosh Framebuffer 无回看、无 alt 屏
     *  （1049 被忽略，退出 vim 留残影是其著名缺陷）、resize 顶锚定丢底部行。
     *  服务端模型更穷，逐字节镜像反而降级体验；diff 只按行号作用可见区，
     *  语义差异不会破坏协议。不要为“对齐服务端”改这里。 */
    fun applyDiff(diff: ByteArray) {
        runBlocking {
            lock.withLock {
            for (ev in decodeHostMessage(diff)) {
                when (ev) {
                    is HostEventIn.HostBytes -> emulator.write(ev.bytes)
                    is HostEventIn.Resize -> buffer.resize(ev.width, ev.height)
                    is HostEventIn.EchoAck -> if (ev.echoAckNum > echoAck) echoAck = ev.echoAckNum
                }
            }
            }
        }
    }

    /** 分叉：深拷贝当前状态（mosh 收端时间戳状态复制）。 */
    fun fork(): ShadowTerminal {
        return runBlocking {
            lock.withLock {
            // COW 浅分叉：行对象共享、写时复制，把每次状态更新的成本从 O(单元格) 降到 O(行数)
            val buf = buffer.shallowFork()
            val emu = TerminalEmulator(buf)
            emu.onTitleChange = onTitleChange
            emu.onClipboardWrite = onClipboardWrite
            // 影子终端不应对外回写（DSR 应答等），置空即可
            ShadowTerminal(buf, emu, echoAck)
            }
        }
    }

    /** 本地预测回显：在确认态的 COW 分叉上重放用户输入的白名单效果（mosh
     *  逐字节预测的简化版）。支持：
     *  - 可打印字符（含 UTF-8 多字节序列）：直接喂给分叉的模拟器
     *  - 0x7f 退格：readline 回显 "\b \b" 的预测效果（光标回退并抹格）
     *  - CR：shell 规范模式回显 \r\n（mosh newline_carriage_return）
     *  - 左/右方向键（CSI/SS3 C、D）：光标移动
     *  其余控制字节 → 返回 null（放弃本段预测，对齐 mosh 的保守策略）。
     *  仅用于显示，不进入 SSP 状态机；echo_ack 确认后即被丢弃。 */
    internal fun predictInput(bytes: ByteArray, underline: Boolean): ShadowTerminal? {
        val fork = fork()
        val buf = fork.buffer
        if (underline) buf.currentAttrs = buf.currentAttrs or CellAttr.UNDERLINE
        var i = 0
        var textStart = 0
        fun flushText(until: Int) {
            if (until > textStart) fork.emulator.write(bytes.copyOfRange(textStart, until))
        }
        while (i < bytes.size) {
            val b = bytes[i].toInt() and 0xff
            when {
                b == 0x7f -> {
                    flushText(i)
                    i++
                    // 光标不在行首才可退（mosh: cursor().col > 0）；pendingWrap 时
                    // 退格先撤销延迟换行，恰好等价于抹掉行末刚换行的字符
                    if (buf.cursorCol > 0 || buf.pendingWrap) {
                        buf.backspace()
                        buf.putChar(' '.code)
                        buf.backspace()
                    }
                    textStart = i
                }
                b == 0x0d -> {
                    flushText(i)
                    i++
                    fork.emulator.write(CR_LF)
                    textStart = i
                }
                b == 0x1b -> {
                    flushText(i)
                    val ok = i + 2 < bytes.size &&
                        (bytes[i + 1] == '['.code.toByte() || bytes[i + 1] == 'O'.code.toByte()) &&
                        (bytes[i + 2] == 'C'.code.toByte() || bytes[i + 2] == 'D'.code.toByte())
                    if (!ok) return null
                    fork.emulator.write(bytes.copyOfRange(i, i + 3))
                    i += 3
                    textStart = i
                }
                b < 0x20 -> return null // 其余控制字节不预测
                else -> i++ // 可打印/UTF-8 字节：攒段批量写
            }
        }
        flushText(bytes.size)
        return fork
    }

    companion object {
        private val CR_LF = byteArrayOf(0x0d, 0x0a)

        fun create(cols: Int, rows: Int): ShadowTerminal {
            // 影子必须完整镜像服务端 framebuffer（mosh Framebuffer 无滚动上限）；
            // UI 渲染层的回看上限由 uiBuffer 自行裁剪，影子不可截断，否则 diff 会错位
            val buf = TerminalBuffer(cols, rows, maxScrollbackLines = Int.MAX_VALUE)
            val emu = TerminalEmulator(buf)
            return ShadowTerminal(buf, emu, 0u)
        }
    }
}
