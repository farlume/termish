package dev.termish.mosh

/**
 * SSP 本地状态（对应协议用户流）：待送达服务器的用户输入流。
 * diff = 队列后缀；subtract = 丢弃对端已知的前缀。
 */
internal class UserStream {
    sealed class Event {
        class Byte(val b: kotlin.Byte) : Event()
        class Resize(val width: Int, val height: Int) : Event()

        override fun equals(other: Any?): Boolean = when {
            this is Byte && other is Byte -> b == other.b
            this is Resize && other is Resize -> width == other.width && height == other.height
            else -> false
        }

        override fun hashCode(): Int = when (this) {
            is Byte -> b.toInt()
            is Resize -> 31 * width + height
        }
    }

    private val actions = ArrayDeque<Event>()

    val size: Int get() = actions.size

    fun pushByte(b: Byte) = actions.addLast(Event.Byte(b))

    fun pushResize(width: Int, height: Int) = actions.addLast(Event.Resize(width, height))

    /** 从 existing 是 this 的前缀出发生成 diff（UserMessage protobuf 编码）。 */
    fun diffFrom(existing: UserStream): ByteArray {
        var idx = 0
        for (e in existing.actions) {
            check(idx < actions.size && actions[idx] == e) { "UserStream diff_from: 前缀不匹配" }
            idx++
        }
        val events = ArrayList<UserEventOut>()
        // 增长式字节缓冲：避免逐字节 "pending += b" 造成的 O(n²) 拷贝（大粘贴场景）
        var buf = ByteArray(64)
        var len = 0
        fun flushKeys() {
            if (len == 0) return
            events.add(UserEventOut.Keystrokes(buf.copyOf(len)))
            len = 0
        }
        for (i in idx until actions.size) {
            when (val e = actions[i]) {
                is Event.Byte -> {
                    if (len == buf.size) buf = buf.copyOf(buf.size * 2)
                    buf[len++] = e.b
                }
                is Event.Resize -> {
                    flushKeys()
                    events.add(UserEventOut.Resize(e.width, e.height))
                }
            }
        }
        flushKeys()
        if (events.isEmpty()) return ByteArray(0)
        return encodeUserMessage(events)
    }

    /** 丢弃与 prefix 相同的前缀（对端已确认收到的部分）。 */
    fun subtract(prefix: UserStream) {
        repeat(prefix.actions.size) {
            check(actions.isNotEmpty() && actions.first() == prefix.actions[it]) {
                "UserStream subtract: 前缀不匹配"
            }
            actions.removeFirst()
        }
    }

    override fun equals(other: Any?): Boolean {
        if (other !is UserStream || other.actions.size != actions.size) return false
        for (i in actions.indices) if (actions[i] != other.actions[i]) return false
        return true
    }

    override fun hashCode(): Int = actions.fold(1) { acc, e -> 31 * acc + e.hashCode() }

    fun copy(): UserStream {
        val c = UserStream()
        for (e in actions) c.actions.addLast(e)
        return c
    }
}
