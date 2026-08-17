# crypto/ — 自研密码学实现（未审计）

> **English summary:** threat model of the pure-Kotlin crypto primitives.
> Only `Sha256` is used in production (host fingerprints, credential
> signatures). The AES-128-OCB used by the mosh wire protocol is an explicit,
> documented exception. Everything else is research/test-only — do not wire
> it into SSH/KEX, data encryption or key derivation until audited.

本目录下的算法实现仅供研究与测试参考，**未经第三方密码学审计**。

## 原语清单与生产使用状态

| 文件 | 实现 | 生产使用 | 说明 |
|------|------|----------|------|
| `Sha256.kt` | SHA-256 | ✅ **是** | 主机指纹（libssh2 blob SHA-256）、会话凭据摘要（`credentialSignature`） |
| `Aes.kt` + `Ocb.kt` | AES-128-OCB | ✅ **显式例外** | mosh 传输加密，见下文 |
| `Sha512.kt` | SHA-512 | ❌ 仅测试 | RFC 向量验证 |
| `Hmac.kt` | HMAC-SHA256/512 | ❌ 仅测试 | |
| `Ed25519.kt` | Ed25519 签名 | ❌ 仅测试 | 曾用于 SSH 公钥方案探索 |
| `Field.kt` | X25519 曲线域运算 | ❌ 仅测试 | |
| `ChaCha20.kt` / `Poly1305.kt` | ChaCha20-Poly1305 AEAD | ❌ 仅测试 | 曾用于 mosh 加密方案评估 |

**政策**：在完成审计前，禁止把上述实现接入 SSH/KEX、数据加密或密钥派生路径。
需要曲线 / ChaCha 能力时，优先使用平台提供方：

- Android：随 App 打包的完整 BouncyCastle（`TermishApplication.kt` 的
  `installFullBouncyCastle`——Android 自带阉割版 BC 缺 X25519 / Ed25519）
- iOS / Desktop JVM：系统安全框架 / JDK 内置 Provider

## 例外：mosh 传输加密（`dev.termish.mosh`）

mosh 的 SSP 协议强制使用 AES-128-OCB 作为 AEAD，且 mosh 密钥经 SSH 引导通道
下发（`MOSH CONNECT <port> <key>`），UDP 层加密防的是「链路监听者篡改/重放
终端流量」，密钥本身的安全性由 SSH 保证。为保持「纯 Kotlin、零原生依赖」
（替代 GPLv3 原生客户端的自研实现），`mosh/Aes.kt` + `mosh/Ocb.kt` 是自研实现
并运行在生产链路，属于本目录政策的**显式例外**：

- 正确性：`commonTest/mosh/OcbTest` 含独立标准解密向量
  （非自洽 round-trip），算法错误会在单测暴露
- 局限：表驱动软件 AES **无常数时间保证**，存在理论时序侧信道；威胁模型下
  （攻击者需已能贴近观测设备 CPU 缓存）风险可接受，但就此记录在案
- 演进方向：若未来引入平台 AEAD（JVM BouncyCastle / iOS CommonCrypto）且能接受
  按平台分源的工程成本，应替换掉自研 OCB

## 测试

`commonTest/crypto/CryptoTest` 覆盖 RFC 标准测试向量（RFC 4231 HMAC、
FIPS 180-4 SHA、RFC 8032 Ed25519 等）——这些实现即使不生产使用也保持
向量级正确，随时可审计。
