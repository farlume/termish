package dev.termish.screen

import dev.termish.mosh.Fragment
import dev.termish.mosh.FragmentAssembly
import dev.termish.mosh.Fragmenter

/**
 * 屏幕推流的半可靠分片传输（方案 A 传输原型）。
 *
 * 复用 mosh 的 SSP 分片格式（[Fragment] id/num/final），但负载是视频帧字节而非
 * SSP 指令。与 [dev.termish.mosh.MoshTransport] 的关键差异：视频流**单向、丢帧
 * 可接受**，因此这里只有「分片 + 重组 + 丢包丢弃」，没有状态同步、没有重传、
 * 没有 RTT 估计——比 SSP 传输层简单得多。
 *
 * 半可靠语义：
 * - 发端：每个视频包分配新 instruction id，zlib 压缩 + 按 MTU 切片
 * - 收端：乱序 / 重复分片容忍；缺分片时整包丢弃（视频流丢一包没关系，
 *   解码器等下一个 IDR 恢复）
 * - 连续视频流下，丢包后下一包的新 id 会让收端自动重置（不会永久卡住）
 *
 * 线程模型：两端各一个实例，非线程安全，由各自会话单线程驱动（与 MoshTransport 一致）。
 */
class ScreenStreamSender(
    /** 底层数据报发送（UDP send；失败静默，视频流不重传）。 */
    private val sendDatagram: (ByteArray) -> Unit,
) {
    private val fragmenter = Fragmenter()

    /** 发送一个视频包（payload 原始字节，内部 zlib 压缩 + 分片）。 */
    fun send(
        payload: ByteArray,
        mtu: Int,
    ) {
        for (frag in fragmenter.makeFragments(payload, mtu)) {
            sendDatagram(frag.toBytes())
        }
    }
}

/** 收端重组：喂入数据报，返回非 null 表示一个完整视频包已重组（解压后字节）。 */
class ScreenStreamReceiver {
    private val assembly = FragmentAssembly()

    /** 调试：最近一次喂入是否顶掉了未完成块（丢包拖垮；上层消费后清位）。 */
    var lastAbandoned = false

    /**
     * 喂入一个数据报。
     *
     * @return 完整包的解压后字节；null = 当前包还不完整（等待更多分片，
     *  或分片损坏 / 丢包——后者会一直 null 直到下一个包的新 id 到来触发重置）。
     */
    fun onDatagram(data: ByteArray): ByteArray? {
        val frag =
            try {
                Fragment.parse(data)
            } catch (_: Exception) {
                return null
            }
        if (!assembly.addFragment(frag)) {
            lastAbandoned = assembly.lastAbandoned
            return null
        }
        lastAbandoned = false
        return assembly.assembleBytes()
    }
}
