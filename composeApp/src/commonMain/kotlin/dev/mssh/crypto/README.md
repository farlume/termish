# crypto/ — 自研密码学实现（未审计）

本目录下的算法实现（Ed25519、X25519/Field、ChaCha20-Poly1305、HMAC-SHA256/512、
SHA-512 等）仅供研究与测试参考，**未经第三方密码学审计**。

生产代码目前**只使用** `Sha256`（主机指纹、会话凭据摘要）。
其余实现没有被任何生产链路调用；在完成审计前，禁止把它们接入 SSH/KEX、
数据加密或密钥派生路径。

需要曲线/ChaCha 能力时，优先使用平台提供方：

- Android：随 App 打包的完整 BouncyCastle（见 `MsshApplication.kt` 的注册逻辑）
- iOS / Desktop JVM：系统安全框架 / JDK 内置 Provider
