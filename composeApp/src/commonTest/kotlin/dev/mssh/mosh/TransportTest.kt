package dev.mssh.mosh

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MoshTransport 定时/发送路径回归：nowMs 注入、sendDatagram 旁路捕获。 */
class TransportTest {

    private class Harness {
        var now = 0L
        val sent = ArrayList<ByteArray>()
        var states = 0
        val transport = MoshTransport(
            ip = "127.0.0.1",
            initialCols = 80,
            initialRows = 24,
            key = dev.mssh.util.base64Encode(ByteArray(16)).substring(0, 22),
            nowMs = { now },
            sendDatagram = { sent.add(it); SendResult.OK },
            onNewState = { states++ },
        )
    }

    @Test
    fun rationalizeWithUnackedPrefixDoesNotThrow() {
        // 回归：front 未 ack 且 current 有 ≥2 个不同字节时，rationalizeStates
        // 曾持引用自减导致 "前缀不匹配" 崩会话。
        val h = Harness()
        h.transport.pushBytes("abc123".encodeToByteArray())
        h.now += 100
        h.transport.tick()
        h.now += 100
        h.transport.tick() // 第二次 rationalize（front 已被减过一次）
        h.now += 5000
        h.transport.tick()
        assertTrue(h.sent.isNotEmpty())
    }

    @Test
    fun emptyAckHeartbeat() {
        val h = Harness()
        h.transport.tick() // 初始立即 ack
        val initial = h.sent.size
        h.now += 3500
        h.transport.tick()
        assertTrue(h.sent.size > initial) // 3s 后有心跳空 ack
    }

    @Test
    fun inputTriggersFragmentedSend() {
        val h = Harness()
        h.transport.tick()
        val before = h.sent.size
        h.transport.pushBytes(ByteArray(2000) { (it % 256).toByte() })
        h.now += 300 // 超过 sendInterval 上限 250ms（帧率限制是 mosh 的正常行为）
        h.transport.tick() // 记录 mindelayClock
        h.now += 20 // 越过 mindelay(8ms)
        h.transport.tick()
        assertTrue(h.sent.size > before) // 用户输入产生新数据报（含分片）
    }

    @Test
    fun serverShutdownIsAckedWithMaxNum() {
        // 服务器 shutdown（new_num = ULLONG_MAX）：客户端应加速回 ack_num=-1，
        // 之后 counterpartyShutdownAckSent() 为 true（mosh 干净退出条件之一）
        val h = Harness()
        h.transport.tick() // 初始握手 ack
        val inst = TransportInstruction(
            protocolVersion = 2,
            oldNum = 0u,
            newNum = ULong.MAX_VALUE,
            ackNum = 0u,
            throwawayNum = 0u,
            diff = ByteArray(0),
            chaff = ByteArray(0),
        )
        val payload = Fragmenter().makeFragments(inst, 400).first().toBytes()
        h.transport.processPacket(MoshCryptoSession.PlainPacket(1u, 0, 0xffff, payload))
        assertFalse(h.transport.counterpartyShutdownAckSent())
        h.now += 5000 // 超过 ACK_INTERVAL，且 ack_num==-1 会触发加速
        h.transport.tick()
        assertTrue(h.transport.counterpartyShutdownAckSent())
    }

    @Test
    fun emptyDiffStateDoesNotNotifyRenderer() {
        // 空 diff（纯 ack/心跳）不应触发 onNewState：内容未变，避免无谓的全量 UI 拷贝
        val h = Harness()
        val inst = TransportInstruction(
            protocolVersion = 2,
            oldNum = 0u,
            newNum = 1u,
            ackNum = 0u,
            throwawayNum = 0u,
            diff = ByteArray(0),
            chaff = ByteArray(0),
        )
        val payload = Fragmenter().makeFragments(inst, 400).first().toBytes()
        h.transport.processPacket(MoshCryptoSession.PlainPacket(1u, 0, 0xffff, payload))
        assertEquals(0, h.states)
    }
}
