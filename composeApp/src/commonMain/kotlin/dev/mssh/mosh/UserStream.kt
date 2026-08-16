package dev.mssh.mosh

/**
 * SSP 本地状态（对应协议 的 Network::UserStream）：待送达服务器的用户输入流。
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
        var pending = ByteArray(0)
        for (i in idx until actions.size) {
            when (val e = actions[i]) {
                is Event.Byte -> pending += e.b
                is Event.Resize -> {
                    if (pending.isNotEmpty()) {
                        events.add(UserEventOut.Keystrokes(pending))
                        pending = ByteArray(0)
                    }
                    events.add(UserEventOut.Resize(e.width, e.height))
                }
            }
        }
        if (pending.isNotEmpty()) events.add(UserEventOut.Keystrokes(pending))
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
