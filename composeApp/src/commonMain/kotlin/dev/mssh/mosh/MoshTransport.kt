package dev.mssh.mosh

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * SSP 传输层（对应协议 Network::Transport<UserStream, Complete>）：
 * 发端 TransportSender 语义 + 收端状态机，线程不安全，由会话协程单线程驱动。
 *
 * 时间均为单调毫秒（调用方注入 nowMs()）。
 */
internal class MoshTransport(
    ip: String,
    initialCols: Int,
    initialRows: Int,
    key: String,
    private val nowMs: () -> Long,
    private val sendDatagram: (ByteArray) -> SendResult,
    /** 最新影子状态变化（有新帧可渲染）时回调。 */
    private val onNewState: (ShadowTerminal) -> Unit,
) {
    // ---- 常量（mosh 发送器 / 协议常量） ----
    private val SEND_INTERVAL_MIN = 20L
    private val SEND_INTERVAL_MAX = 250L
    private val ACK_INTERVAL = 3000L
    private val ACK_DELAY = 100L
    private var sendMindelay = 8L
    private val ACTIVE_RETRY_TIMEOUT = 10000L
    private val CONGESTION_TIMESTAMP_PENALTY = 500L
    private val MIN_RTO = 50L
    private val MAX_RTO = 1000L
    /** 应用层 MTU：mosh Connection 按地址族取 1252(IPv4)/1216(IPv6)，再扣
     *  Connection::ADDED_BYTES(12) + OCB 16；500 只是 mosh 在 EMSGSIZE 时的保底。 */
    private val MTU_FALLBACK = 500 - 12 - 16
    private var sendMtu: Int

    private class SentState(var timestamp: Long, val num: ULong, val state: UserStream)

    private class RecvState(var timestamp: Long, var num: ULong, val state: ShadowTerminal)

    // ---- 发端状态 ----
    private val currentState = UserStream()
    private val sentStates = ArrayDeque<SentState>()
    private var assumedReceiverIdx = 0
    private val fragmenter = Fragmenter()
    private var nextAckTime = nowMs()
    private var nextSendTime = Long.MAX_VALUE
    private var ackNum = 0uL
    private var pendingDataAck = false
    private var lastHeard = 0L
    private var mindelayClock = -1L
    private var seq = 0uL
    /** 最近一次发出的 ack_num（mosh Fragmenter::last_ack_sent 对应物）。 */
    private var lastAckSent = 0uL

    // ---- 收端状态 ----
    private val receivedStates = ArrayDeque<RecvState>()
    private val assembly = FragmentAssembly()
    private var receiverQuenchTimer = 0L
    private var expectedReceiverSeq = 0uL

    // ---- RTT 估计（Jacobson/Karels，与 mosh Connection 一致） ----
    private var srtt = 1000.0
    private var rttvar = 500.0
    private var rttHit = false
    private var savedTimestamp = -1
    private var savedTimestampReceivedAt = 0L

    var shutdownInProgress = false
        private set
    private var shutdownTries = 0
    private var shutdownStart = -1L

    /** mosh -v 风格的帧发送诊断输出。 */
    var verbose = false

    val latestRemote: ShadowTerminal get() = receivedStates.last().state

    init {
        sendMtu = if (ip.contains(':')) 1216 - 12 - 16 else 1252 - 12 - 16
        val initial = UserStream()
        sentStates.addLast(SentState(nowMs(), 0u, initial))
        receivedStates.addLast(RecvState(nowMs(), 0u, ShadowTerminal.create(initialCols, initialRows)))
    }

    // ---- 上层输入 ----

    fun pushBytes(data: ByteArray) {
        if (shutdownInProgress) return // mosh get_current_state 在 shutdown 时 assert
        for (b in data) currentState.pushByte(b)
    }

    fun pushResize(cols: Int, rows: Int) {
        if (shutdownInProgress) return
        currentState.pushResize(cols, rows)
    }

    /** 击键最低发送延迟（mosh 客户端 set_send_delay(1)，默认 8ms）。 */
    fun setSendDelay(ms: Int) {
        sendMindelay = ms.toLong()
    }

    fun startShutdown() {
        if (!shutdownInProgress) {
            shutdownInProgress = true
            shutdownTries = 0
            shutdownStart = nowMs()
        }
    }

    fun shutdownAckTimedOut(): Boolean =
        shutdownInProgress && (shutdownTries >= 16 || nowMs() - shutdownStart >= ACTIVE_RETRY_TIMEOUT)

    /** 对端已 ack 我们的 shutdown 状态（sent_states.front().num == -1）。 */
    fun shutdownAcknowledged(): Boolean = sentStates.first().num == ULong.MAX_VALUE

    /** 已发出 ack_num=-1，即确认了对端的 shutdown（mosh counterparty_shutdown_ack_sent）。 */
    fun counterpartyShutdownAckSent(): Boolean = lastAckSent == ULong.MAX_VALUE

    fun hasPeer(): Boolean = lastHeard != 0L

    fun lastHeardMs(): Long = lastHeard

    /** 对端已确认状态（front）的发送时刻；客户端端口轮换判定用（mosh get_sent_state_acked_timestamp）。 */
    fun sentStateAckedTimestamp(): Long = sentStates.first().timestamp

    /** 当前平滑 RTT（毫秒）。 */
    fun srttMs(): Long = srtt.toLong()

    /** 当前发送间隔（SRTT/2 clamp 20..250ms）——预测引擎的触发输入
     *  （mosh 用 send_interval 而非裸 SRTT 驱动 SRTT_TRIGGER/FLAG_TRIGGER）。 */
    fun sendIntervalMs(): Long = sendInterval()

    /** 最近发送状态帧号（mosh local_frame_sent）：预测层估计输入承载帧用。 */
    fun lastSentNum(): ULong = sentStates.last().num

    // ---- RTT ----

    private fun timeout(): Long {
        val rto = ceil(srtt + 4 * rttvar).toLong()
        return min(max(rto, MIN_RTO), MAX_RTO)
    }

    private fun sendInterval(): Long {
        val i = ceil(srtt / 2.0).toLong()
        return min(max(i, SEND_INTERVAL_MIN), SEND_INTERVAL_MAX)
    }

    private fun timestamp16(): Int = ((nowMs() % 65536).toInt() and 0xffff).let { if (it == 0xffff) 0 else it }

    // ---- 收包（decrypt 之后调用） ----

    fun processPacket(pkt: MoshCryptoSession.PlainPacket, congestionExperienced: Boolean = false) {
        val now = nowMs()
        // 旧包只用于时间戳/RTT，不进 SSP（mosh: out-of-order 提前 return）
        if (pkt.seq >= expectedReceiverSeq) {
            expectedReceiverSeq = pkt.seq + 1u
            if (pkt.timestamp != 0xffff) {
                savedTimestamp = pkt.timestamp
                if (congestionExperienced) {
                    // mosh recv_one：CE 命中给时间戳减 500ms，让对端测到更大 RTT 从而降帧率
                    savedTimestamp = (savedTimestamp - CONGESTION_TIMESTAMP_PENALTY.toInt()) and 0xffff
                }
                savedTimestampReceivedAt = now
            }
            if (pkt.timestampReply != 0xffff) {
                val r = timestampDiff(timestamp16(), pkt.timestampReply).toDouble()
                if (r < 5000) {
                    if (!rttHit) {
                        srtt = r
                        rttvar = r / 2
                        rttHit = true
                    } else {
                        rttvar = 0.75 * rttvar + 0.25 * kotlin.math.abs(srtt - r)
                        srtt = 0.875 * srtt + 0.125 * r
                    }
                }
            }
            lastHeard = now
        }

        val frag = Fragment.parse(pkt.payload)
        if (!assembly.addFragment(frag)) return
        val inst = assembly.assembly()
        if (inst.protocolVersion != MOSH_PROTOCOL_VERSION) return

        processAcknowledgmentThrough(inst.ackNum)

        // 已收到的状态直接忽略
        if (receivedStates.any { it.num == inst.newNum }) return

        // 必须找到 old_num 对应的参考状态（幂等性的安全前提）
        val refIdx = receivedStates.indexOfFirst { it.num == inst.oldNum }
        if (refIdx < 0) return

        processThrowawayUntil(inst.throwawayNum)

        if (receivedStates.size > 1024) {
            if (now < receiverQuenchTimer) return
            receiverQuenchTimer = now + 15000
        }

        val reference = receivedStates.first { it.num == inst.oldNum }
        val newState = RecvState(now, inst.newNum, reference.state.fork())
        if (inst.diff.isNotEmpty()) {
            newState.state.applyDiff(inst.diff)
        }

        // 按序号插入（乱序恢复）或追加
        val insertAt = receivedStates.indexOfFirst { it.num > newState.num }
        if (insertAt >= 0) {
            receivedStates.add(insertAt, newState)
            return
        }
        receivedStates.addLast(newState)
        ackNum = newState.num
        lastHeard = now
        if (inst.diff.isNotEmpty()) {
            pendingDataAck = true
            // 空 diff（纯 ack/心跳）不触发重绘：内容未变，避免无谓的全量 UI 拷贝
            onNewState(newState.state)
        }
    }

    private fun processAcknowledgmentThrough(ack: ULong) {
        if (sentStates.any { it.num == ack }) {
            val it = sentStates.iterator()
            while (it.hasNext()) {
                if (it.next().num < ack) it.remove()
            }
        }
    }

    private fun processThrowawayUntil(throwawayNum: ULong) {
        val it = receivedStates.iterator()
        while (it.hasNext()) {
            if (it.next().num < throwawayNum) it.remove()
        }
    }

    // ---- 定时与发送 ----

    private fun updateAssumedReceiverState() {
        val now = nowMs()
        assumedReceiverIdx = 0
        for (i in 1 until sentStates.size) {
            if (now - sentStates[i].timestamp < timeout() + ACK_DELAY) {
                assumedReceiverIdx = i
            } else {
                return
            }
        }
    }

    private fun rationalizeStates() {
        // 必须拷贝：known 会在循环中被自身的 subtract 清空，持引用会把前缀越改越乱
        // （mosh 协议 原样写是 std::deque 迭代器 UB 但恰好工作，Kotlin 侧不可复刻）
        val known = sentStates.first().state.copy()
        currentState.subtract(known)
        for (s in sentStates) s.state.subtract(known)
    }

    private fun calculateTimers() {
        val now = nowMs()
        updateAssumedReceiverState()
        rationalizeStates()

        if (pendingDataAck && nextAckTime > now + ACK_DELAY) {
            nextAckTime = now + ACK_DELAY
        }

        if (currentState != sentStates.last().state) {
            if (mindelayClock == -1L) mindelayClock = now
            nextSendTime = max(mindelayClock + sendMindelay, sentStates.last().timestamp + sendInterval())
        } else if (currentState != sentStates[assumedReceiverIdx].state &&
            lastHeard + ACTIVE_RETRY_TIMEOUT > now
        ) {
            nextSendTime = sentStates.last().timestamp + sendInterval()
            if (mindelayClock != -1L) {
                nextSendTime = max(nextSendTime, mindelayClock + sendMindelay)
            }
        } else if (currentState != sentStates.first().state &&
            lastHeard + ACTIVE_RETRY_TIMEOUT > now
        ) {
            nextSendTime = sentStates.last().timestamp + timeout() + ACK_DELAY
        } else {
            nextSendTime = Long.MAX_VALUE
        }

        if (shutdownInProgress || ackNum == ULong.MAX_VALUE) {
            nextAckTime = sentStates.last().timestamp + sendInterval()
        }
    }

    /** 距下次需要 tick 的毫秒数；0 表示立即。 */
    fun waitTime(): Int {
        calculateTimers()
        val nextWakeup = min(nextAckTime, nextSendTime)
        if (nextWakeup == Long.MAX_VALUE) return Int.MAX_VALUE
        val now = nowMs()
        return if (nextWakeup > now) (nextWakeup - now).toInt() else 0
    }

    fun tick() {
        calculateTimers()
        val now = nowMs()
        if (now < nextAckTime && now < nextSendTime) return

        var diff = currentState.diffFrom(sentStates[assumedReceiverIdx].state)

        // prospective resend 优化（发送器）
        if (assumedReceiverIdx > 0) {
            val resendDiff = currentState.diffFrom(sentStates.first().state)
            if (resendDiff.size <= diff.size || (resendDiff.size < 1000 && resendDiff.size - diff.size < 100)) {
                assumedReceiverIdx = 0
                diff = resendDiff
            }
        }

        if (diff.isEmpty()) {
            if (now >= nextAckTime) {
                sendEmptyAck()
                mindelayClock = -1
            }
            if (now >= nextSendTime) {
                nextSendTime = Long.MAX_VALUE
                mindelayClock = -1
            }
        } else if (now >= nextSendTime || now >= nextAckTime) {
            sendToReceiver(diff)
            mindelayClock = -1
        }
    }

    private fun sendEmptyAck() {
        val now = nowMs()
        val newNum = if (shutdownInProgress) ULong.MAX_VALUE else sentStates.last().num + 1u
        addSentState(now, newNum, currentState)
        sendInFragments(ByteArray(0), newNum)
        nextAckTime = now + ACK_INTERVAL
        nextSendTime = Long.MAX_VALUE
    }

    private fun addSentState(timestamp: Long, num: ULong, state: UserStream) {
        sentStates.addLast(SentState(timestamp, num, state.copy()))
        if (sentStates.size > 32) {
            sentStates.removeAt(sentStates.size - 16) // 从中间删，保头保尾
        }
    }

    private fun sendToReceiver(diff: ByteArray) {
        val newNum = when {
            shutdownInProgress -> ULong.MAX_VALUE
            currentState == sentStates.last().state -> sentStates.last().num
            else -> sentStates.last().num + 1u
        }
        if (newNum == sentStates.last().num) {
            sentStates.last().timestamp = nowMs()
        } else {
            addSentState(nowMs(), newNum, currentState)
        }
        sendInFragments(diff, newNum)
        assumedReceiverIdx = sentStates.size - 1
        nextAckTime = nowMs() + ACK_INTERVAL
        nextSendTime = Long.MAX_VALUE
    }

    private fun sendInFragments(diff: ByteArray, newNum: ULong) {
        val inst = TransportInstruction(
            protocolVersion = MOSH_PROTOCOL_VERSION,
            oldNum = sentStates[assumedReceiverIdx].num,
            newNum = newNum,
            ackNum = ackNum,
            throwawayNum = sentStates.first().num,
            diff = diff,
            chaff = makeChaff(),
        )
        lastAckSent = inst.ackNum
        if (newNum == ULong.MAX_VALUE) shutdownTries++
        for (frag in fragmenter.makeFragments(inst, sendMtu)) {
            if (sendDatagram(encryptPacket(frag.toBytes())) == SendResult.TOO_LARGE) {
                // mosh Connection::send：EMSGSIZE 时把 MTU 回退到保底值
                sendMtu = MTU_FALLBACK
            }
        }
        if (verbose) {
            println(
                "[mosh] sent old=${inst.oldNum} new=${inst.newNum} ack=${inst.ackNum} " +
                    "throwaway=${inst.throwawayNum} len=${diff.size} srtt=${srtt.toInt()} rto=${timeout()}",
            )
        }
        pendingDataAck = false
    }

    /** 时间戳回复：收到对端 timestamp 后 1s 内回显（按持有时间修正）。 */
    private fun encryptPacket(payload: ByteArray): ByteArray {
        val now = nowMs()
        var reply = 0xffff
        if (savedTimestamp >= 0 && now - savedTimestampReceivedAt < 1000) {
            reply = (savedTimestamp + (now - savedTimestampReceivedAt)).toInt() and 0xffff
            if (reply == 0xffff) reply = 0
            savedTimestamp = -1
            savedTimestampReceivedAt = 0
        }
        val mySeq = seq
        seq++
        return crypto.encrypt(mySeq, timestamp16(), reply, payload)
    }

    private val crypto = MoshCryptoSession(key)

    fun decryptDatagram(data: ByteArray): MoshCryptoSession.PlainPacket? = crypto.decrypt(data)

    /** 随机 0..16 字节 chaff（mosh 发送器 make_chaff），
     *  混淆指令长度并让 fragment id 每帧变化。 */
    private fun makeChaff(): ByteArray =
        ByteArray(prng.nextInt(17)) { prng.nextInt(256).toByte() }

    private val prng = kotlin.random.Random.Default

    companion object {
        /** uint16 环绕安全的差值（network.cc timestamp_diff）。 */
        fun timestampDiff(newer: Int, older: Int): Int = ((newer - older) + 65536) % 65536
    }
}
