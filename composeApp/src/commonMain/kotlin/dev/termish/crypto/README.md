# crypto/ — 自研密码学实现（未审计）

本目录下的算法实现（Ed25519、X25519/Field、ChaCha20-Poly1305、HMAC-SHA256/512、
SHA-512 等）仅供研究与测试参考，**未经第三方密码学审计**。

生产代码目前**只使用** `Sha256`（主机指纹、会话凭据摘要）。
其余实现没有被任何生产链路调用；在完成审计前，禁止把它们接入 SSH/KEX、
数据加密或密钥派生路径。

需要曲线/ChaCha 能力时，优先使用平台提供方：

- Android：随 App 打包的完整 BouncyCastle（见 `TermishApplication.kt` 的注册逻辑）
- iOS / Desktop JVM：系统安全框架 / JDK 内置 Provider

## 例外：mosh 传输加密（`dev.termish.mosh`）

mosh 的 SSP 协议强制使用 AES-128-OCB 作为 AEAD，且 mosh 密钥经 SSH 引导通道
下发（`MOSH CONNECT <port> <key>`），UDP 层加密防的是「链路监听者篡改/重放
终端流量」，密钥本身的安全性由 SSH 保证。为保持「纯 Kotlin、零原生依赖」
（替代 GPLv3 的 原生客户端），`mosh/Aes.kt` + `mosh/Ocb.kt` 是自研实现
并运行在生产链路，属于本目录政策的**显式例外**：

- 正确性：`commonTest/mosh/OcbTest` 含独立标准解密向量
  （非自洽 round-trip），算法错误会在单测暴露
- 局限：表驱动软件 AES **无常数时间保证**，存在理论时序侧信道；威胁模型下
  （攻击者需已能贴近观测设备 CPU 缓存）风险可接受，但就此记录在案
- 演进方向：若未来引入平台 AEAD（JVM BouncyCastle / iOS CommonCrypto）且能接受
  按平台分源的工程成本，应替换掉自研 OCB