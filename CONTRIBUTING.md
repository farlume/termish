# Contributing to Termish

感谢你愿意贡献！本文件是参与开发的完整指引，README 的 Contributing 一节是速览版。

## 开发环境

| 平台 | 需要 |
|---|---|
| Android / Desktop | JDK 17+、Android SDK（`local.properties` 或 `ANDROID_HOME`）；模拟器或真机 |
| iOS | Xcode + 一次性原生依赖：`make ios-native` + `make ios-framework`（见 `scripts/` 头部注释） |

常用入口（`make help` 查看全部，或直接 `./gradlew <task>`）：

```bash
make run              # 构建 + 安装 + 启动到模拟器/设备（gradle runDebug）
make test             # 单元测试 + 集成测试（sshd/mosh 不在时自动 SKIP）
make test-integration # 自动起本地 sshd 后跑传输层集成测试
make release          # 已签名 release（需要 .env，见 .env.example）
```

## 代码结构约定

- `term/` 是**纯 Kotlin、零平台依赖**的终端模拟器 —— 任何转义序列或 buffer
  行为的改动，必须在 `commonTest/` 加对应的单元测试
- 平台代码只允许出现在 expect/actual 接缝之后：`ssh/SshSession`、`util/`、`data/SecretStore`
- UI 设计 token 集中在 `ui/theme/`（`Dimens`、调色板）—— 新 UI 代码不要写
  临时 dp/alpha 字面量
- 字符串走 `AppStrings`（中英双语），不要硬编码文案

## 文档同步

**语言策略（刻意为之，不是遗漏）**：README 是用户面，双语文档化（`README.md`
英文权威 + `README.zh-CN.md` 中文全文镜像）；`docs/` 深水文档是贡献者面，
单份中文 + 开头英文摘要——与代码注释、提交语言一致，不为 docs/ 维护英文副本，
除非出现真实的国际贡献需求（英文 issue/PR 出现后再按需翻译）。

- `README.md`（英文权威）与 `README.zh-CN.md`（中文全文镜像）必须**同步修改**——
  改任一结构（标题层级、章节增删、事实性内容）时两份一起改
- 深水文档在 `docs/`：`architecture.md` / `terminal-emulator.md` / `mosh.md` /
  `input-pipeline.md`。它们面向贡献者，正文中文、开头英文摘要
- `term/` 行为改动（转义序列、buffer、宽字符）→ 同步 `docs/terminal-emulator.md`
  的支持矩阵或模型描述，并补 `commonTest/` 单测
- `mosh/` 行为改动（传输/加密/预测/漫游）→ 同步 `docs/mosh.md` 的对应小节
- UI 交互改动（输入管线、工具栏、手势）→ 同步 `docs/input-pipeline.md`

## 提交与 PR

- Commit message：中文简述 + 必要时正文说明**为什么**（参考 git log 风格）
- PR 前请跑过 `make test` 和 `make lint`
- 改动传输层（sshj/libssh2/SFTP）请跑 `make test-integration`；mosh 无 mosh-server
  集成测试，改动收发逻辑建议本地连真实 mosh-server 手动验证（见 docs/mosh.md）
- 涉及签名/发版：不要提交任何真实机密；`.env`、`*.jks` 已在 `.gitignore`

## 版本与发版

- 版本号统一用 `make bump V=x.y.z`（先 `DRY=1` 预览），它会同步 Android /
  桌面 / iOS 三处
- 打 `vX.Y.Z` tag 推送后，CI 校验版本一致性并自动产出 Release

## 报告问题

- Bug：用 [Bug report 模板](.github/ISSUE_TEMPLATE/bug_report.md)，附版本号、
  平台、复现步骤和日志（iOS 崩溃日志在 `<NSTemporaryDirectory>/termish-diag.log`）
- 安全漏洞：**不要开公开 issue**，走 [SECURITY.md](SECURITY.md) 的私密渠道

## 行为准则

参与本项目即表示同意遵守 [行为准则](CODE_OF_CONDUCT.md)。
