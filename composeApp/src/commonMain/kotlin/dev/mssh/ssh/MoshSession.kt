package dev.mssh.ssh

/**
 * Mosh 客户端会话：由平台实现拉起 mosh-client 进程（PTY + 进程 + 读写桥接），
 * 输出走 [onOutput] 喂给终端模拟器。
 */
interface MoshSession {
    fun isActive(): Boolean
    fun resize(columns: Int, rows: Int)
    fun sendData(data: ByteArray)
    fun close()
}

/** 平台实际：创建 mosh-client 进程（含 PTY、环境变量、IO 线程）。 */
suspend expect fun createMoshClient(
    ip: String,
    port: Int,
    key: String,
    columns: Int,
    rows: Int,
    onOutput: (ByteArray) -> Unit,
    onExit: () -> Unit,
): MoshSession

/** 解析 `mosh-server new` 输出里的 `MOSH CONNECT <port> <key>`。 */
fun parseMoshConnect(output: String): Pair<Int, String>? {
    val m = Regex("MOSH CONNECT (\\d+) ([A-Za-z0-9+/=]+)").find(output) ?: return null
    return m.groupValues[1].toIntOrNull()?.let { it to m.groupValues[2] }
}

/** mosh-server 引导命令（按 Termux 同款参数）。 */
const val MOSH_SERVER_BOOTSTRAP = "mosh-server new -c 8 -l LANG=en_US.UTF-8"
