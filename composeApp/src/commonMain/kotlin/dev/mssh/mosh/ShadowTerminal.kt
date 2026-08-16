package dev.mssh.mosh

import dev.mssh.term.TerminalBuffer
import dev.mssh.term.TerminalEmulator

/**
 * SSP 远端状态（对应协议 服务端的 服务端终端状态 在客户端的影子）：
 * 一个完整终端（解析器 + 屏幕缓冲），按 HostMessage diff 演化。
 *
 * 复用项目自研的纯 Kotlin 终端模拟器。mosh 的 Complete 不可复制解析器状态
 * （注释称 parser state irrelevant），分叉时用同一 buffer 深拷贝 + 新解析器：
 * new_frame 产生的 diff 是完整转义序列，不会在序列中间断帧。
 */
internal class ShadowTerminal private constructor(
    val buffer: TerminalBuffer,
    private val emulator: TerminalEmulator,
    var echoAck: ULong,
) {
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

    /** 应用 HostMessage diff（hostbytes → 终端；resize → 改尺寸；echoack → 记录）。 */
    fun applyDiff(diff: ByteArray) {
        for (ev in decodeHostMessage(diff)) {
            when (ev) {
                is HostEventIn.HostBytes -> emulator.write(ev.bytes)
                is HostEventIn.Resize -> buffer.resize(ev.width, ev.height)
                is HostEventIn.EchoAck -> if (ev.echoAckNum > echoAck) echoAck = ev.echoAckNum
            }
        }
    }

    /** 分叉：深拷贝当前状态（mosh 收端 时间戳状态 复制）。 */
    fun fork(): ShadowTerminal {
        // COW 浅分叉：行对象共享、写时复制，把每次状态更新的成本从 O(单元格) 降到 O(行数)
        val buf = buffer.shallowFork()
        val emu = TerminalEmulator(buf)
        emu.onTitleChange = onTitleChange
        emu.onClipboardWrite = onClipboardWrite
        // 影子终端不应对外回写（DSR 应答等），置空即可
        return ShadowTerminal(buf, emu, echoAck)
    }

    companion object {
        fun create(cols: Int, rows: Int): ShadowTerminal {
            // 影子必须完整镜像服务端 framebuffer（mosh Framebuffer 无滚动上限）；
            // UI 渲染层的回看上限由 uiBuffer 自行裁剪，影子不可截断，否则 diff 会错位
            val buf = TerminalBuffer(cols, rows, maxScrollbackLines = Int.MAX_VALUE)
            val emu = TerminalEmulator(buf)
            return ShadowTerminal(buf, emu, 0u)
        }
    }
}
