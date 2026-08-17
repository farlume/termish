# 架构与线程模型

> **English summary:** module layout of the KMP codebase, the expect/actual seams,
> the per-platform transport table, and the threading model that keeps the
> terminal buffer safe: SSH output is queued and consumed serially on the main
> thread (bounded queue + backpressure); the Mosh shadow terminal is owned by a
> session coroutine and synced to the UI buffer via a conflated channel.

## 模块布局

```
composeApp/
├── commonMain/                # 全平台共享（核心资产）
│   ├── term/                  # 纯 Kotlin 终端模拟器（零平台依赖，见 terminal-emulator.md）
│   ├── mosh/                  # 纯 Kotlin mosh 客户端（SSP 协议，见 mosh.md）
│   ├── ssh/                   # 平台无关会话抽象：SshSession / SftpSession / MoshSession
│   │                          #   + expect 工厂 createSshSession / createSftpSession
│   ├── crypto/                # 纯 Kotlin 密码原语（未审计——见其 README；生产只用 Sha256）
│   ├── data/                  # Host / AppSettings @Serializable 模型、HostRepository
│   │                          #   （multiplatform-settings + JSON）、SecretStore expect
│   ├── ui/                    # AppRoot 导航、SessionManager、TerminalScreen/View、
│   │                          #   KeyToolbar、SFTP、Host/Connections/Settings 页、theme/
│   └── util/                  # SessionKeepAlive / NetworkChange / Dispatchers /
│                              #   MonoFont / AppLifecycle 等 expect 接缝
├── jvmSharedMain/             # Android + desktop 共享的 JVM 引擎
│   ├── ssh/SshSessionSshj     # sshj + BouncyCastle（ed25519 / chacha20 / RSA / kb-int）
│   └── mosh/MoshPlatform.jvm  # UDP 套接字 + java.util.zip 实现
├── androidMain/               # MainActivity + SessionService（前台服务保活）+ Keystore
├── desktopMain/               # JVM 桌面开发/测试 harness + 打包（DMG/DEB/MSI）
├── iosMain/                   # MainViewController + libssh2 引擎 + Keychain + POSIX UDP
├── commonTest/                # crypto RFC 向量 + 终端模拟器 + mosh 单元测试
└── desktopTest/               # sshj / SFTP 集成测试（打真实 sshd，127.0.0.1:2222）
```

## 平台分工

| 平台 | SSH 引擎 | 认证 | Mosh 客户端 | 密钥存储 |
|------|----------|------|-------------|----------|
| Android | sshj + BouncyCastle | password / publickey / keyboard-interactive | 纯 Kotlin `dev.termish.mosh`（UDP 直连） | Android Keystore（AES-GCM） |
| Desktop | sshj + BouncyCastle | 同上 | 纯 Kotlin `dev.termish.mosh` | 明文 properties 文件（仅开发 harness） |
| iOS | libssh2 + OpenSSL（静态链接） | 同上 | 纯 Kotlin `dev.termish.mosh` | Keychain |

## expect/actual 接缝

平台代码只允许出现在这些接缝之后：

| 接缝 | commonMain | androidMain | desktopMain | iosMain |
|------|-----------|-------------|-------------|---------|
| `ssh.createSshSession` | expect | jvmSharedMain（sshj） | jvmSharedMain（sshj） | libssh2 引擎 |
| `ssh.createSftpSession` | expect | jvmSharedMain | jvmSharedMain | SftpSessionLibssh2 |
| `data.SecretStore` | expect | Keystore AES-GCM | 明文文件 | Keychain |
| `util.SessionKeepAlive` | expect | 前台服务 + wakelock | no-op | no-op |
| `util.NetworkChange` | expect | ConnectivityManager | no-op | no-op |
| `util.Dispatchers` | expect | IO/Default | IO/Default | 自定义 IO 队列 |
| `util.MonoFont` | expect | 捆绑 JetBrains Mono | 捆绑字体 | PingFang SC |
| `mosh.MoshUdpSocket` / zlib | expect | jvmSharedMain | jvmSharedMain | POSIX socket + 系统 zlib |
| `ui.FilePicker/FileSaver/DirectorySaver` | expect | SAF/DocumentFile | AWT | UIDocumentPicker |

## 线程模型（2026-08 重构后的约定）

约束一句话：**`TerminalBuffer` 的全部读写（渲染 / resize / 选择 / 模拟器写入）串行在主线程**。

### SSH 输出路径

```
sshj/libssh2 reader 协程（IO 线程）
    └─ onOutput/onStderr（挂起回调）
         └─ TerminalController.enqueueOutput
              └─ Channel<ByteArray>(256)   ← 有界队列
                   └─ 主线程消费协程
                        ├─ emulator.write(chunk)     ← buffer 唯一写入口
                        ├─ 8ms 帧预算内批量抽干，合并重绘
                        └─ frame++（Compose 重绘信号）
```

- **背压**：队列满时 `send` 挂起 → reader 停止读 socket → TCP 窗口收敛。不丢字节也不撑爆内存（`cat` 大文件场景）。
- **串行化**：sshj 的 stdout/stderr 两个 reader 用 `limitedParallelism(1)` 共享调度器，转义序列不会被并发打断。
- 消费循环 `receiveCatching` + `tryReceive` + 预算到点 `yield()`，小包洪泛时每包一次重绘的抖动被消除。

### Mosh 输出路径

```
KmpMoshSession 事件循环协程（Default 线程）
    ├─ 影子终端（ShadowTerminal.buffer，Mutex 保护）
    └─ onStateUpdate → Channel<ShadowTerminalView>(CONFLATED)
                        └─ 主线程消费：uiBuffer.copyContentFrom(view.buffer)
                             └─ 行级 (identity, version) 增量比对，无视觉变化不重绘
```

- CONFLATED 只保留最新影子状态，突发更新最多一个主线程拷贝在途。
- 拷贝持影子锁：会话协程 applyDiff/resize 中途不能读，否则 resize 半程行状态不一致会越界。

### 写路径

- sshj：`sendData` 经 `limitedParallelism(1)` 写调度器（避开主线程 socket 写 + 保证输入顺序）。
- mosh：`sendData` 投递到事件 Channel，由会话协程统一 push 进 `UserStream`。
- 认证/主机密钥弹窗：`onPrompt` 挂起 120s 超时按取消处理；`verifyHostKey` 同步阻塞 120s 超时按拒绝——防止连接线程永久悬挂。

### 控制器生命周期

- `TerminalController` 持 `ioDispatcher + SupervisorJob` 作用域；`close()` 保留重入能力（可 reconnect），`destroy()` 关闭队列并取消作用域（回收重连退避/主题注入/稳定期重置等延迟任务）。
- 自动重连任务统一挂在 `reconnectJob`，close 时取消，防关闭后仍被延迟协程拉起。

## 会话保活与网络事件

- Android：`SessionService` 引用计数前台服务 + PARTIAL_WAKE_LOCK，续期按绝对时刻排班（防保活空洞）；Android 15 `dataSync` 6 小时超时后优雅退出，回前台自动重连。
- iOS：后台挂起即断线，回前台 `SessionManager.reconnectDroppedSessions()` 自动重连（缓冲保留）。
- 网络切换：SSH 在「新网络就绪」事件下主动断开走快速重连（30s 免疫期 + 15s 防抖 + 单调钟）；mosh 不重建，靠 UDP 漫游自愈。

## 持久化

- 非秘密数据（主机列表/设置/最近会话）→ `multiplatform-settings`（JSON，`ignoreUnknownKeys`）。
- 秘密数据（密码/私钥）→ `SecretStore`（Keystore/Keychain）。
- 解析失败不丢数据：原始串备份到 `*.corrupt.<ts>` key（最多 3 份）。
