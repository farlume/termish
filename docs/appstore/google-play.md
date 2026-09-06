# Google Play 提交材料

> 包名：`dev.termish.app`<br>
> 类别：工具（Tools）<br>
> 默认语言：简体中文<br>
> 隐私政策 URL：`https://termish.dev/privacy`<br>
> 支持 URL：`https://termish.dev`

首次上传前必须确认 `compileSdk` / `targetSdk` 均为 36，并使用现有 Termish
正式签名作为 Play App Signing 的 app signing key，以保持官网 APK 与 Play 版
签名一致。建议另建独立 upload key。

## 商店文案（简体中文）

### 应用名称（30 字符内）

`Termish SSH 终端`

### 简短说明（80 字符内）

`支持 SSH、Mosh、SFTP、远程画面与 AI Agent 的本地优先终端`

### 完整说明（4000 字符内）

```text
Termish 是一款为手机打造的 SSH 与 Mosh 终端，也是连接远程开发环境和 AI Agent
的随身工作台。无需 Termish 账号，不接入广告和遥测；主机凭据保存在系统安全存储
中，连接直接建立到你选择的服务器。

稳定的远程会话
• SSH 与纯 Kotlin Mosh 客户端
• Wi-Fi、蜂窝网络切换后的连接恢复
• 后台保活、断线检测和自动重连
• 支持 tmux 等服务端持久会话

真正的移动终端
• 纯 Kotlin 终端模拟器，不使用 WebView
• 支持 VT100/xterm、真彩色、CJK 宽字符和 OSC 8 超链接
• 中文输入法组合态不会提前发送到服务器
• CTRL、ALT、ESC、方向键及 TUI 鼠标操作
• 多会话标签和自定义终端主题、字体、列数

文件与桌面
• SFTP 浏览、上传、下载和目录操作
• 远程桌面画面及触控/虚拟鼠标操作
• 文件传输进度与完成通知

远程 AI Agent
• 从手机连接运行在自己服务器上的 Agent 工具
• 支持 Codex、Claude Code、Gemini CLI、OpenCode 和 Pi
• 会话历史、附件、审批与任务状态
• Agent 服务商由用户自行配置，流量不经过 Termish 服务器

安全与隐私
• 密码、私钥和 API Key 使用系统安全存储
• 首次连接主机指纹确认
• 禁止明文网络流量
• 语音输入默认关闭；启用后仅在按住麦克风期间将音频加密发送给用户配置的
  识别服务商
• 无广告、无统计分析、无遥测

Termish 官网：https://termish.dev
开源代码（MIT）：https://github.com/ttermish/termish
隐私政策：https://termish.dev/privacy
```

## Store listing (English)

### App name

`Termish SSH Terminal`

### Short description

`Local-first SSH, Mosh, SFTP, remote screen, and AI Agent terminal`

### Full description

```text
Termish is a mobile SSH and Mosh terminal and a pocket workspace for remote
development environments and AI Agents. No Termish account, ads, analytics, or
telemetry are required. Credentials stay in platform-protected storage, and
connections go directly to servers you choose.

Resilient remote sessions
• SSH and a pure-Kotlin Mosh client
• Connection recovery across Wi-Fi and cellular changes
• Background keep-alive, health checks, and automatic reconnect
• Works with persistent server-side sessions such as tmux

A real mobile terminal
• Pure-Kotlin terminal emulator, not a WebView
• VT100/xterm sequences, true color, CJK wide characters, and OSC 8 links
• IME composition stays local until text is committed
• CTRL, ALT, ESC, arrow keys, and TUI mouse input
• Multiple session tabs, terminal themes, fonts, and column controls

Files and remote desktop
• Browse, upload, download, and manage files with SFTP
• View and control a remote desktop with touch or a virtual mouse
• Transfer progress and completion notifications

Remote AI Agents
• Connect to Agent tools running on your own server
• Supports Codex, Claude Code, Gemini CLI, OpenCode, and Pi
• Session history, attachments, approvals, and task status
• You choose the Agent provider; traffic does not pass through Termish servers

Security and privacy
• Passwords, private keys, and API keys use protected credential storage
• Host fingerprint confirmation on first connection
• Cleartext network traffic is disabled
• Voice input is off by default and sends audio to the recognition provider you
  configure only while you hold the microphone button
• No ads, analytics, or telemetry

Website: https://termish.dev
Privacy policy: https://termish.dev/privacy
Source code (MIT): https://github.com/ttermish/termish
```

## App content 表单建议

最终答案必须与准备上传的 AAB、第三方服务条款及线上隐私政策保持一致。

### Ads

`No, my app does not contain ads.`

### App access

选择“部分功能受限”，因为核心 SSH/SFTP/远程画面能力需要服务器凭据。

```text
Termish does not have an application account. To review its core functionality,
use the reusable SSH test server below.

Host: <REVIEW_HOST>
Port: <REVIEW_PORT>
Username: <REVIEW_USER>
Password: <REVIEW_PASSWORD>

Steps:
1. Open Termish and tap Add host.
2. Enter the host, port, username, and password above.
3. Save and tap the host card.
4. Accept the displayed host fingerprint.
5. Run `help`, `ls`, or `htop` to verify terminal input and output.
6. Open the session menu to access SFTP and remote-screen functions.

The credentials are reusable, do not require MFA, and remain available throughout
the review period. The account is isolated and contains only disposable test data.
```

提交前把全部占位符换成公网审核机信息；不要把审核密码写进仓库或商店公开文案。

### Target audience

推荐仅在产品确实面向专业开发/运维用户时选择 `18 and over`，并声明应用不面向儿童。

### Content rating

如实完成 IARC 问卷。应用自身不包含暴力、色情、赌博、毒品或用户公开互动内容；
用户自己的服务器内容不由 Termish 提供。

### Data safety

建议按当前实现申报：

| 数据类型 | 收集 | 共享 | 必需性 | 用途 | 说明 |
|---|---|---|---|---|---|
| Audio files → Voice or sound recordings | Yes | 依据火山引擎角色确认 | Optional | App functionality | 用户按住麦克风时发送至火山引擎转写 |

通用问题建议：

- Data encrypted in transit：`Yes`
- Users can request deletion：Termish 无中央账号或自有服务器数据；第三方数据删除方式
  以火山引擎条款为准，确认后再选择
- Credentials、SSH 内容、SFTP 文件：Termish 项目不接收；流量直接到用户指定服务器
- Agent 提示词与附件：从用户服务器发送至其自行配置的服务商；根据 Google Play
  对用户主动传输和服务商的定义确认是否属于 sharing 豁免，不要直接填“无数据”

### Foreground service declaration

类型：`connectedDevice`<br>
最接近的用途：`Continuous data transfer to an external device`

功能说明：

```text
Termish is an interactive SSH and Mosh client. After the user explicitly connects
to a remote computer, a connected-device foreground service maintains that active
encrypted network session while the app is backgrounded. A persistent notification
makes the activity visible and lets the user return to or stop the session.
```

延迟或中断影响：

```text
Deferring the task prevents an explicitly requested interactive shell from becoming
usable. Interrupting it disconnects the user's active terminal, stops incoming
output and file transfers, and may lose unsaved remote work unless the user also
configured a persistent server-side session.
```

演示视频应完整录到：用户主动连接 → 终端输出 → 切后台 → 常驻通知 → 回前台仍连接
→ 用户主动断开后通知消失。视频用不公开的 YouTube 链接并保证审核员可访问。

## 发布说明

首次内测可用：

```text
首个 Google Play 测试版本：支持 SSH、Mosh、SFTP、多会话、远程画面、语音输入和远程 Agent 工作台。
```

English:

```text
First Google Play test release with SSH, Mosh, SFTP, multiple sessions, remote screen, voice input, and remote Agent workflows.
```

## 图形素材

- Play 图标：`google-play/play-icon.png`（512×512）
- Feature graphic：`google-play/feature-graphic.png`（1024×500）
- 中文手机截图：`google-play/zh/`
- 英文手机截图：`google-play/en/`

上传顺序和无障碍说明：

| 顺序 | 文件 | 中文 Alt text | English alt text |
|---|---|---|---|
| 1 | `hosts.png` | Termish 主机列表及活跃连接状态 | Host list with active connection status |
| 2 | `terminal-menu.png` | 终端会话及 Git、上传、SFTP 和语音快捷菜单 | Terminal session with Git, upload, SFTP, and voice actions |
| 3 | `sftp.png` | SFTP 文件列表与目录操作菜单 | SFTP file browser and directory actions |
| 4 | `settings.png` | 主题、语言、通知和终端偏好设置 | Theme, language, notification, and terminal preferences |

## 控制台执行顺序

1. 完成开发者身份、联系方式、真机和付款资料验证。
2. 创建应用并确认包名 `dev.termish.app`。
3. 配置 Play App Signing：导入现有 Termish app signing key，另建 upload key。
4. 上传 API 36 的 AAB 到 Internal testing。
5. 完成 Store listing、Privacy policy、Ads、App access、Target audience、
   Content rating、Data safety 和 Foreground service declarations。
6. 检查 Pre-launch report，修复崩溃、ANR、无障碍和兼容性问题。
7. 新个人账号运行 12 人连续 14 天的 Closed testing，并保存测试反馈。
8. 申请 Production access；获批后先小比例发布，再逐步扩大。

## 提交前清单

- [ ] `compileSdk` / `targetSdk` 为 36
- [ ] `versionCode` 高于 Play Console 已上传版本
- [ ] AAB 使用正确 upload key 签名
- [ ] 线上隐私政策可通过 HTTPS 匿名访问
- [ ] App 内隐私政策与线上版本、Data safety 一致
- [ ] 火山引擎音频保存、删除及服务商角色已经核实
- [ ] FGS 演示视频无需登录即可观看
- [ ] 审核 SSH 账号公网可达、无 MFA、长期有效
- [ ] 512 图标、1024×500 宣传图和至少四张手机截图已上传
- [ ] 中英文商店文案与当前功能一致
- [ ] 已检查 Android vitals、Pre-launch report 和政策状态
