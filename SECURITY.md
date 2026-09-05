# Security Policy

Termish 是一个处理凭据的 SSH 客户端，安全问题优先处理。

## 支持的版本

只对最新 release 版本提供安全修复。

## 报告漏洞

请通过 [ttermish@gmail.com](mailto:ttermish@gmail.com) 私下报告安全漏洞，
不要公开披露未修复漏洞的细节。

请尽量包含：受影响版本/平台、复现步骤、潜在影响。我们会在 72 小时内确认收到，
并通过邮件跟进修复进展。

## 本项目自身的安全边界

了解我们的威胁模型有助于判断问题是否属于安全漏洞：

- 密码/私钥存放于 Android Keystore（AES-GCM）/ iOS Keychain
- 主机密钥采用 TOFU（首次信任 + 指纹确认）
- `crypto/` 下的纯 Kotlin 实现**未经审计**，README 已声明；
  生产路径仅使用其中的 Sha256
- 无遥测、无分析；网络流量限于用户配置的 SSH/Mosh 主机、语音识别服务和
  远程 Agent 模型服务
