# Mosh 实现笔记（dev.termish.mosh）

> **English summary:** implementation notes for the pure-Kotlin Mosh client —
> AES-128-OCB wire crypto, the SSP transport state machine (fragmentation,
> RTT estimation, ACK/throwaway), the shadow terminal that reuses our own
> emulator, the local-echo prediction engine, and roaming via UDP port rotation.

## 为什么自研

mosh 官方客户端是 GPLv3，与本项目 MIT 许可冲突。`dev.termish.mosh` 是纯 Kotlin
实现（SSP 协议 + AES-128-OCB + zlib 分片 + UDP 直连），不需要任何 GPL 原生二进制。
实现基于公开的协议行为规格与线上报文格式，与官方客户端保持线上兼容。

## 实现依据

独立 Kotlin 重写：SSP 线上协议（wire format 与 proto 字段号是互操作所必需）、
AES-128-OCB（RFC 7253，配套 RFC 向量测试）、zlib（RFC 1950/1951）。
代码中标注的 mosh 引用均为行为对照（线上兼容约束），非代码复制。

## 分层与独立性

mosh 分两层，边界刻意保持干净：

- **协议层（零依赖，可独立提取为库）**：`MoshTransport` / `UserStream` /
  `Fragmentation` / `Messages` / `Ocb` / `Aes` / `MoshCrypto` / `KmpMoshSession`
  ——不 import `dev.termish.term`，只依赖 Kotlin 标准库与 kotlinx。
  若未来要单独发布 mosh 客户端库/供他人复用，此层直接拆模块即可。
- **影子层（必然依赖终端模拟器）**：`ShadowTerminal` / `PredictionLayer` 复用
  `term/` 模拟器维护服务端帧缓冲镜像——mosh 协议要求客户端持有终端状态；
  复用自研模拟器而非另写一套是刻意决策（行为同源 + 行级增量同步红利），
  不是耦合缺陷。

纪律：协议层禁止 `import dev.termish.term`（见 AGENTS.md）。

## 组件清单

| 文件 | 职责 |
|------|------|
| `MoshCrypto.kt` / `Ocb.kt` / `Aes.kt` | AES-128-OCB 数据报加解密 |
| `MoshTransport.kt` | SSP 传输层：发端 sender + 收端状态机 |
| `UserStream.kt` | 本地输入状态流（diff = 队列后缀，subtract = 丢已知前缀） |
| `Fragmentation.kt` | 指令分片 + zlib 压缩 / 重组 |
| `Messages.kt` | transportinstruction / userinput / hostinput proto 手工编解码 |
| `ShadowTerminal.kt` | 服务端终端状态的影子（复用 term/ 模拟器） |
| `PredictionLayer.kt` | 本地预测回显 |
| `KmpMoshSession.kt` | 会话事件循环、端口轮换、生命周期 |
| `MoshPlatform.kt` | expect：UDP 套接字 + zlib |

## 加密

- 会话密钥：SSH 引导时 `mosh-server` 打印的 22 字符 base64 key（16 字节，去掉 `==` 填充）。校验长度 + 规范编码（拒绝尾部非零 bit 的伪 key）。
- AES-128-OCB：表驱动 AES（**无常数时间保证**——威胁模型见 `crypto/README.md`：密钥经 SSH 通道下发，生产链路的例外）。L 表按协议约定，正确性由独立标准测试向量覆盖（`OcbTest`）。
- 线上格式：`8B nonce 低位 || OCB 密文 || 16B tag`；nonce = `4B 0 || 8B 大端序号`；明文 = `2B timestamp || 2B timestamp_reply || 载荷`。
- **方向位**：序号最高位区分方向（TO_CLIENT=1）。收包校验方向位，防止把客户端自己发过的包重放回来当合法数据。
- 加密块计数达 2^47 终止会话（OCB 生日界）。

## SSP 传输层

- **时间**：全部单调毫秒（注入 `nowMs()`），免疫墙钟漂移。
- **RTT**：Jacobson/Karels 估计；发送间隔 = SRTT/2 clamp 20–250ms；RTO 50–1000ms；timestamp 16 位环绕差值。
- **指令**：`(protocol_version=2, old_num, new_num, ack_num, throwaway_num, diff, chaff)`。分片按 MTU（IPv4 1252 / IPv6 1216，扣 Connection 头 12 + OCB 16）切，zlib 压缩；EMSGSIZE 时回退保底 MTU 472。
- **收端**：旧包只进时间戳/RTT 不进程序；按 new_num 乱序插入；ack 收编已确认的 sent states；throwaway 清理；接收态 >1024 时 15s 限流（state quench）。
- **发端**：sent states 上限 32（从中间裁）；`rationalizeStates` 减掉对端已知前缀；prospective resend（回退到最早未确认状态若 diff 更小）；chaff 0–16 随机字节混淆指令长度；CE 拥塞标记给时间戳减 500ms 让对端降帧率。
- **空 diff（纯 ack/心跳）不触发重绘**，避免每 3s 一次全量 UI 拷贝。
- **shutdown 握手**：num = ULONG_MAX 标记，16 次尝试 / 10s 超时；双方都 ack 过即干净退出。

## 影子终端（ShadowTerminal）

复用项目自研模拟器，按 `HostMessage` diff 演化（`HostBytes` / `Resize` / `EchoAck`）：

- `fork()` = `buffer.shallowFork()`（COW 浅分叉，O(行数)）+ 新模拟器实例——分叉同样只深拷贝 buffer。
- `buffer` 有 Mutex：会话协程 applyDiff/resize 与 UI 拷贝线程互斥（resize 半程读行会越界）。
- 影子缓冲回看无上限（`Int.MAX_VALUE`）：UI 缓冲自行裁剪，影子截断会让 diff 错位。

**有意的影子语义**（`ShadowTerminal.applyDiff` 注释明示，不要为"对齐服务端"改）：

| 行为 | 服务端默认语义 | 本项目影子 |
|------|------------------------|-----------|
| 备用屏（1049） | 忽略（退出 vim 留残影是其著名缺陷） | 完整支持 |
| resize | 顶锚定，丢底部行 | xterm 式底锚定，优先丢光标下空白行 |
| 滚动回看 | 无 | 保留 |

diff 只按行号作用可见区，语义差异不会破坏协议——但体验上影子更好。

## 本地预测回显（PredictionLayer）

高 RTT 下打字"即时"的关键，预测引擎的简化版：

- **触发迟滞**：send_interval >30ms 开、≤20ms 关（仅无活跃预测时允许关）；下划线标记（flagging）>80ms 开、≤50ms 关。
- **预测载体**：确认态的 COW 分叉上重放 pending 输入；白名单——可打印字符（含 UTF-8）、0x7f 退格（"\b \b" 语义）、CR（shell 规范模式回显 \r\n）、CSI/SS3 C/D 方向键；**其余控制字节 → 放弃本段**（保守策略）。
- **备用屏只预测可打印字符段**：控制字节在全屏程序里语义各异（vim 普通模式 x 是删字符不是退格）。
- **收编**：echo_ack >= 承载帧号即视为回显已到（或"无回显"的事实，如密码输入），丢弃预测；未收编的预测在每个新确认态上重放，避免"出现→消失→再出现"闪烁。
- **放弃**：大粘贴（>128 字节）不预测；glitch 悬挂 3s 回确认态；resize 几何失效重置。

## 会话与漫游（KmpMoshSession）

- 事件循环协程（Default）+ 每 socket 一个阻塞读协程（IO）；UI 的输入/resize/close 经 Channel 汇入。
- **端口轮换**（mosh 客户端语义）：10s 无对端 ack 且 10s 没换过源端口 → 换新 socket；旧 socket 保留收包，新端口稳定 60s 后剪除；最多同时 10 个。
- **已连接后永不主动超时退出**（漫游核心）：断网靠重发（ACTIVE_RETRY 窗口）+ 端口轮换维持，网络恢复（WiFi↔蜂窝、隧道、休眠唤醒）无缝续传；服务端同样永不超时。`still_connecting` 阶段 15s 无对端包才判失败。
- 链路健康度：距上次收包秒数，值变化才回调（空闲心跳约 3s，正常 0~3）；UI 达 5s 显示"失去联系"横幅。

## 主题注入（Mosh 特有）

mosh-server 会**吞掉**远端 TUI 发出的 OSC 10/11 颜色查询——查询永远到不了手机，herdr 等
应用只能拿宿主机终端主题，手机浅色主题下远端却按深色渲染。

解法：把手机终端配色（OSC 10/11 + OSC 4 基础 16 色应答）以"用户输入"形式写进 mosh 输入流
（`buildThemeSyncPayload`），herdr 会像收到终端应答一样解析采纳。约束：

- 仅当主机配置了启动命令（TUI 会话）且开启 `moshThemeSync` 才注入——普通 shell 的 readline 不解析 OSC，会把字节当输入回显成乱码。
- 连接后延迟 1.2s 注入：太早被 readline 当输入，太晚 herdr 显示灰色蒙层。

## 测试

- 单元测试（`commonTest/mosh/`）：`OcbTest`（独立标准解密向量，非自洽 round-trip）、`SspTest`、`TransportTest`、`PredictionTest`。
- **无 mosh-server 集成测试**：CI 未安装 mosh-server，desktopTest 只覆盖 sshj/SFTP 集成。改动 MoshTransport/KmpMoshSession 的收发逻辑时，建议本地起真实 mosh-server 手动验证（或先跑单测 + 桌面端连真实主机）。

## 已知取舍

- 表驱动 AES 无常数时间保证（见 crypto/README.md 威胁模型）。
- 预测引擎是简化版：普通屏含不支持控制字节的段落不预测；无 per-cell 收编。
- ECN CE 位平台暂不提供，`congestionExperienced` 恒 false（字段与处理路径已就位）。
