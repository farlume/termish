# SSH 传输层（ssh/）

> **English summary:** the platform-neutral `SshSession` contract and the two
> engines behind it — sshj+BouncyCastle on JVM (Android/desktop) and a hand-
> written libssh2 cinterop engine on iOS. Covers the auth chain (password /
> publickey / passphrase / keyboard-interactive), the single-threaded reader
> discipline that keeps escape-sequence decoding race-free, non-blocking
> polling on iOS, mosh-server bootstrap, and system probing.

## 分工

`commonMain/ssh/` 只定义平台无关契约，引擎由 expect/actual 注入：

| 层 | 文件 | 职责 |
|----|------|------|
| 契约 | `SshSession.kt` | `SshSession` / `SshCallbacks` / `SshConnection` / 认证与指纹模型 |
| 契约 | `SftpSession.kt` | SFTP 抽象（见 sftp.md） |
| 契约 | `MoshSession.kt` | mosh 客户端抽象 + `parseMoshConnect` / 引导命令 / 系统探测 |
| JVM 引擎 | `jvmSharedMain/…/SshSessionSshj.kt` | sshj + BouncyCastle（Android / desktop 共用） |
| iOS 引擎 | `iosMain/…/SshSessionLibssh2.kt` | libssh2 + OpenSSL 静态链接，cinterop 手写 |
| 工厂 | `SshEngine.jvm.kt` / `SshEngine.ios.kt` | `actual fun createSshSession` |

## SshSession 契约

```kotlin
interface SshSession {
    fun connectAndStart(columns: Int, rows: Int): SessionInfo  // 阻塞：连接+认证+PTY+shell
    fun resize(columns: Int, rows: Int, widthPx: Int, heightPx: Int)
    fun sendData(data: ByteArray)
    fun connectAndRun(command: String, timeoutMs: Long): CommandResult  // 一次性 exec（mosh 引导）
    fun probeSystem(): String?   // 复用已认证连接开临时 exec 通道，不重认证
    fun close()
    fun isActive(): Boolean
}
```

**回调契约（重要）**：`onOutput`/`onStderr`/`onPrompt` 是**挂起函数**——引擎在
reader 协程里调用，上层可在边界做背压（`TerminalController.enqueueOutput`
投递到有界队列，满则挂起 → 引擎停止读 socket → TCP 窗口收敛，`cat` 大文件
不丢字节也不撑爆内存）。`verifyHostKey` 在握手阶段**同步阻塞**调用；两个
120s 超时兜底见 architecture.md。

## JVM 引擎（sshj）

传输 / KEX / 加密全部交给久经考验的 sshj + BouncyCastle，本类只做生命周期与回调。

### 认证链（按序尝试）

1. **私钥**（`AuthPublickey`）：`isEncryptedPem` 静态判断 PKCS#8 / legacy PEM /
   OpenSSH 三种格式是否加密（OpenSSH 是手工解析 `openssh-key-v1` 头的
   ciphername，不是碰运气）；加密私钥先走 `onPrompt` 问口令（只问一次、
   不落盘），口令取消则记 `privateKeyError` 并把原因拼进最终错误
2. **密码**（`AuthPassword`）
3. **keyboard-interactive**（`AuthKbi`）兜底：二次验证 / 服务器强制 KBI 场景

认证失败时若私钥加载失败，错误信息会带出「私钥加载失败：…」提示，避免
用户只看到"认证失败"却不知道私钥根本没参与。

### 线程纪律

- **reader**：stdout / stderr 两个 reader 共享 `limitedParallelism(1)` 调度器——
  `onOutput`/`onStderr` 若并发回调，会同时进入无锁的 `TerminalEmulator`
  状态机（转义序列被打断、UTF-8 跨块错乱），**必须串行喂给上层**
- **writer**：`limitedParallelism(1)` 写调度器——避开主线程 socket 写
  （Android 会抛 NetworkOnMainThreadException）+ 保证输入顺序
- 通道关闭后 2s 内轮询 `getExitStatus`，把退出码回调给 UI

### 指纹

`Buffer.PlainBuffer().putPublicKey(key)` 序列化公钥 blob 后 SHA-256 / MD5，
与 OpenSSH 的指纹口径一致。

## iOS 引擎（libssh2）

662 行 cinterop 手写实现——这是全项目最需要小心维护的文件。libssh2 的
cinterop 映射不完整且非线程安全，所有绕行都是为了这两点。

### 线程模型：单线程串行编组

`serialDispatcher = Dispatchers.Default.limitedParallelism(1)`——**读循环 / 写 /
resize / keepalive / cleanup 全部编组到同一线程**，杜绝并发进入同一个
`LIBSSH2_SESSION`（libssh2 非线程安全，并发会直接崩）。

- 读循环是常驻协程：非阻塞 `channel_read_ex`，`EAGAIN` 时 `delay(30)` 并顺带
  发应用层 keepalive（keepalive 必须在读线程发，这是它只能在这儿的根本原因）
- `sendData`/`resize` 从 UI 线程投递到串行线程执行，不在调用线程碰 libssh2
- `close()` 异步化：先等 reader 退出再 cleanup（防 use-after-free），死链路上
  `channel_close` 可能 EAGAIN 循环数秒，不能阻塞 UI 线程

### 阻塞 ↔ 非阻塞两段式

- **握手 / 认证 / exec 引导**：阻塞模式（`session_set_blocking(1)`），代码直白
- **交互 shell**：切非阻塞 + 轮询——才能响应 `close()`、在同一线程发 keepalive
- `retryUntilSuccess`：EAGAIN 重试统一 10s 超时

### 三个 cinterop 绕行（每处都是踩过坑的）

1. **`tcpConnect` 手写非阻塞 connect**：cinterop 没有现成的连接超时。
   `getaddrinfo` + `O_NONBLOCK` + `poll(POLLOUT)`，就绪后二次 `connect`
   判 `EISCONN`（POSIX 标准技巧，避免 `getsockopt(SO_ERROR)` 的跨平台类型
   负担）。超时对齐 sshj 的 `setConnectTimeout`——黑洞地址快速失败，而不是
   等内核 TCP 重试 60s+ 让 UI 一直转圈
2. **`writeAll` 的 UTF-8 边界**：`libssh2_channel_write_ex` 的 cinterop 映射把
   buffer 映射为 `String`（UTF-8 编码 + 显式长度），rc 可能停在 UTF-8 字符
   中间；只能按完整字符推进 `bytesDone`，残字节随整字符重发（概率极低，容忍）
3. **`termish_sftp_write` 原生辅助函数**：见 sftp.md——libssh2_sftp_write 只暴露
   String 版，二进制上传必须用 `unsigned char*` 绕行

### 主机指纹口径

`libssh2_session_hostkey` 返回的 blob 本身含算法字符串前缀（与
`libssh2_hostkey_hash(SHA256)` 一致，即 OpenSSH 指纹口径）——直接对整个 blob
做 SHA-256，**不要再手工拼类型前缀**（拼了指纹就对不上 known_hosts）。

### KBI 全局回调

`staticCFunction` 不能捕获变量 → `kbiHandler` 是进程级单例，加 `kbiMutex`
互斥：多会话并发 KBI 认证时串行化，避免 prompt 路由到错误会话。

## Mosh 引导（connectAndRun）

mosh 模式走 `connectAndRun` 一次性 exec 通道：

```bash
mosh-server new -c 256 -p <固定端口> -l LANG=en_US.UTF-8   # 固定端口（NAS/端口转发）
mosh-server new -c 256 -l LANG=en_US.UTF-8                  # 自动端口（60000-61000）
```

- `-c 256`：远端 TERM=xterm-256color，与本机渲染能力一致（`-c 8` 会让远端
  程序降级 8 色）
- 输出里 `MOSH CONNECT <port> <key>` 由 `parseMoshConnect` 解析，UDP 端口 /
  OCB 密钥随 SSH 通道安全下发
- 引导用的临时 SSH 会话路由到一个**吞掉 onClosed 的 bootstrap 回调**——否则
  引导结束触发 onClosed 会把 CONNECTING 误判为用户关闭，刚拉起的
  mosh-client 被立即销毁（TerminalController 里有详细注释）

## 系统探测（Termius 式自动识别）

`probeSystem()` 在**已认证的连接上**开临时 exec 通道执行
`cat /etc/os-release 2>/dev/null; uname -s`，不重新认证、不打断交互 shell：

- sshj：`client.startSession()` 新通道，5s 超时
- libssh2：会话非线程安全，探测必须 `withContext(serialDispatcher)` 编组到
  读线程执行
- `detectSystemFromOutput`：优先 os-release 的 `ID=`（ubuntu / debian / …），
  否则按 `uname -s` 归一到 linux / macos / freebsd / openbsd

## 测试

- `commonTest/ssh/`：`EncryptedPemTest`（三种 PEM 格式加密判定）、
  `MoshConnectParseTest`（MOSH CONNECT 解析）、`SystemDetectTest`
- `desktopTest/ssh/`：`SshjIntegrationTest` / `SftpIntegrationTest` 打真实
  sshd（127.0.0.1:22222，`scripts/test-sshd.sh` 自动起），**self-detect，
  sshd 缺席时 SKIP 不算失败**
- iOS 引擎没有自动化测试（CI 不构建 iOS）——libssh2 改动必须真机/模拟器
  验证，见 AGENTS.md 的 iOS 构建流程
