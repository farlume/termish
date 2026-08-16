# Security Policy

MSSH 是一个处理凭据的 SSH 客户端，安全问题优先处理。

## 支持的版本

只对最新 release 版本提供安全修复。

## 报告漏洞

**请勿在公开 Issue 中报告安全漏洞。**

推荐渠道（任选其一）：

1. **GitHub Private Vulnerability Reporting**：仓库 Security 标签页 →
   "Report a vulnerability"（如果仓库已开启该功能）
2. 在 Issue 中仅说明「有一个安全问题希望私下沟通」并留下联系方式，
   维护者会主动联系你

请尽量包含：受影响版本/平台、复现步骤、潜在影响。我们会在 72 小时内确认收到，
并在修复发布后公开致谢（除非你希望匿名）。

## 本项目自身的安全边界

了解我们的威胁模型有助于判断问题是否属于安全漏洞：

- 密码/私钥存放于 Android Keystore（AES-GCM）/ iOS Keychain；
  **desktop 是开发/测试 harness，密钥为明文文件**（`~/.mssh`），不算漏洞
- 主机密钥采用 TOFU（首次信任 + 指纹确认）
- `crypto/` 下的纯 Kotlin 实现**未经审计**，README 已声明；
  生产路径仅使用其中的 Sha256
- 无遥测、无分析、除你的 SSH 连接外无任何网络请求
