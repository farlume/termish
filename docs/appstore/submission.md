# App Store Connect 提审材料（iOS）

Submission copy and preparation notes for the MIT-licensed Termish iPhone app.

核对日期：2026-09-05。文案依据仓库中的 iOS 1.7.1；最终以实际上传的构建为准。
本页提供商店文案和填写说明，不代表 App Store Connect 已填妥或已提交。

Termish 于 2026-09-06 恢复 MIT 开源，源代码见 https://github.com/ttermish/termish。
源码许可与商店定价分别维护。当前源码未发现 iOS 内购实现，本版文案不承诺内购
已经可用，也不宣称永久免费；下载价格尚待确定。

## 商店信息：简体中文（zh-Hans）

- **App 名称**（最多 30 字符）：`Termish 终端`
- **副标题**（最多 30 字符）：`SSH、Mosh 与远程工作台`
- **关键词**（最多 100 UTF-8 字节，英文逗号分隔）：
  `ssh,mosh,sftp,linux,server,shell,tmux,vim,远程,服务器,开发,运维`
- **推广文本**（最多 170 字符）：
  `随时连接服务器，处理终端任务、管理文件和切换远程工作区。为 iPhone 打造的 SSH、Mosh 与 SFTP 客户端。`
- **描述**（最多 4000 字符）：

```text
把远程工作台带在身边。

Termish 是为 iPhone 打造的 SSH、Mosh 与 SFTP 客户端。无论是查看服务器状态、处理文件，还是继续远程开发任务，都可以从手机开始。

连接你的服务器
使用密码或私钥连接 SSH 主机，保存常用主机并管理多个会话。支持主机指纹确认，帮助你核对连接目标。

适合手机的终端操作
使用 Ctrl、Alt、Esc 和方向键操作终端程序，配合中文输入、多种终端配色与字体，让日常命令和交互式工具更易使用。

应对网络变化
通过 Mosh 连接，在网络恢复后继续可恢复的远程会话。Mosh 需要服务器安装 mosh-server，并允许相应 UDP 通信；恢复效果取决于网络、服务器状态和系统限制。

随手管理文件
使用 SFTP 浏览远程目录、上传和下载文件，在终端操作与文件管理之间切换。

继续远程开发
连接服务器上的 Herdr 工作区和已配置的 AI Agent 工具，查看任务进展、切换工作区并继续操作。相关工具和运行环境需要在服务器上安装或配置。

可选语音输入
配置兼容的语音识别服务后，可以按住麦克风按钮输入文字。启用该功能需要麦克风权限，录音会发送至你配置的识别服务商。

无需注册 Termish 账号
主机配置保存在设备上，密码、私钥和服务商密钥使用系统安全存储。Termish 不提供服务器或模型服务；使用远程功能需要你有权访问的服务器，语音和 AI 服务可能需要单独的服务商账号及费用。

iOS 可能暂停后台连接。需要持续运行的远程任务，建议在服务器上配合 tmux 等会话管理工具使用。

开源代码（MIT）：https://github.com/ttermish/termish
隐私政策：https://termish.dev/privacy/
支持与反馈：https://termish.dev/
```

## 商店信息：英文（en-US）

- **App 名称**：`Termish`
- **副标题**：`SSH, Mosh & SFTP Client`
- **关键词**：`terminal,linux,server,shell,remote,developer,files,tmux,vim,console`
- **推广文本**：
  `Connect to your servers, manage files, and continue remote development from your iPhone with SSH, Mosh, and SFTP.`
- **描述**：

```text
Keep your remote workspace close.

Termish brings SSH, Mosh, and SFTP to your iPhone. Check a server, manage files, or pick up a remote development task wherever you work.

Connect to your servers
Sign in with a password or private key, save frequently used hosts, and manage multiple sessions. Verify host fingerprints when connecting to a server.

Terminal controls for your phone
Use Ctrl, Alt, Esc, and arrow keys with interactive terminal tools. Chinese text input, terminal color themes, and font options help you work comfortably on a small screen.

Handle changing networks
Use Mosh to resume a recoverable remote session when connectivity returns. Mosh requires mosh-server on the destination host and the appropriate UDP access. Recovery depends on network conditions, server state, and operating system limits.

Manage files over SFTP
Browse remote folders, upload and download files, and switch between terminal work and file management.

Continue remote development
Connect to Herdr workspaces and configured AI Agent tools on your server to follow tasks, switch workspaces, and keep working. These tools and their runtime environments must be installed or configured on the server.

Optional voice input
Configure a compatible speech-recognition service and hold the microphone button to enter text. This feature requires microphone permission and sends recordings to your selected speech provider.

No Termish account required
Host settings stay on your device. Passwords, private keys, and provider keys use system-protected storage. Termish does not supply server hosting or model services. Remote features require a server you are authorized to access; speech and AI services may require separate provider accounts and charges.

iOS may suspend background connections. For long-running remote tasks, use a server-side session manager such as tmux.

Source code (MIT): https://github.com/ttermish/termish
Privacy policy: https://termish.dev/privacy/
Support: https://termish.dev/
```

文案只描述当前移动端功能，不放源码、桌面版、浏览器版入口，也不展示实现细节。
字段限制依据 [Apple 版本信息说明](https://developer.apple.com/help/app-store-connect/reference/app-information/platform-version-information/)。
商店名称仍需由 App Store Connect 检查可用性。

## 其他表单字段

| 字段 | 填写说明 |
| --- | --- |
| 平台与版本 | iOS，`1.7.1`；版本字段不带 Git tag 的 `v` |
| Bundle ID | `dev.termish.app` |
| 主要语言 | 简体中文；补充英文商店本地化 |
| 类别 | 建议主类别 Developer Tools，次类别 Utilities；由发行人确认 |
| 下载价格 | 待确定；与应用内购买商品价格分别设置 |
| 应用内购买 | 当前代码未接入；若首发就收费解锁功能，先完成内购实现、商品配置和测试 |
| 隐私政策 URL | `https://termish.dev/privacy/`；本次访问 HTTP 200，内容修订见检查结果 |
| 支持 URL | `https://termish.dev/`；本次访问 HTTP 200，网站有联系邮箱入口 |
| 营销 URL | `https://termish.dev/` |
| 支持邮箱 | `ttermish@gmail.com` |
| 年龄分级 | 按实际功能回答现行问卷，不预填“全部无 / 4+” |
| 版权 | `2026 <实际版权持有人的姓名或公司名称>`；替换后再提交 |
| 审核联系人 | 填真实姓名、可联系邮箱和带国家区号的电话；不写入仓库 |
| App 账号登录 | 当前无 Termish 账号体系；审核用 SSH / 服务商访问资料仍须提供 |
| 加密合规 | 根据真实实现及发行地区完成问卷；不能仅凭 Info.plist 的 NO 值判断豁免 |
| 发布方式 | 由发行人选择手动或自动发布；本材料不替代该选择 |

类别建议参考 [Apple 类别定义](https://developer.apple.com/app-store/categories/)。

## 商业化与发行地区

应用内付费是产品计划；填写材料时必须区分当前构建和后续计划。
如果本次就提供付费解锁，需要可用的购买入口、权益恢复、商品名称和价格、
审核截图及明确的审核操作路径，并完成所需协议、税务和收款信息。
各内购类型的首次商品需与新 App 版本一起提交；订阅还需按要求加入订阅组。
参见 [Apple 内购提交说明](https://developer.apple.com/help/app-store-connect/manage-submissions-to-app-review/submit-an-in-app-purchase/)。
未接入内购时，不在文案中写“已支持订阅”“解锁 Pro”，也不上传无法在 App 中验证的商品。

在 App Store Connect 的“定价与销售范围”选择实际发行地区；开发者账号所在地区
不等同于 App 只能销售的地区。中国大陆可用性须核对适用的 App 备案要求，
不能用官网是否托管在 Cloudflare 来判断。参见
[销售范围](https://developer.apple.com/help/app-store-connect/manage-your-apps-availability/manage-availability-for-your-app-on-the-app-store/)
和 [中国大陆所需信息](https://developer.apple.com/help/app-store-connect/reference/app-information/app-information/)。
按 App Store Connect 要求声明交易者状态；如以交易者身份在欧盟发行，还需验证
公开联系资料，见 [DSA 信息要求](https://developer.apple.com/help/app-store-connect/manage-compliance-information/manage-european-union-digital-services-act-trader-requirements/)。

## 审核环境与备注

审核人员需要可从公网连接的专用测试主机。素材制作时使用的本地 Docker 容器、
`127.0.0.1`、`10.0.2.2` 和局域网地址不能作为审核连接地址。
测试主机应包含 SSH、SFTP、Mosh 和截图中的 Herdr 演示工作区；同时准备可复现的
Agent / 远程画面操作路径，以及可选语音功能所需的测试配置。
按实际开放功能提供访问方式，不能依赖审核人员自行注册付费服务。

只在 App Store Connect 的私有审核信息中填写专用账号、密码和密钥，
不要提交到 Git 或放在商店公开描述中。保持测试环境在审核及复审期间可用。
以下英文备注为模板，**替换所有尖括号内容并验证步骤后才可粘贴**。
备注上限为 4000 UTF-8 字节，填写后重新检查长度。

```text
Termish is an iPhone client for SSH, Mosh, SFTP, remote workspaces, and server-side Agent tools. It does not require a Termish account. Commands and development tools run on the selected remote server.

Dedicated review environment:
Host: <PUBLIC_HOST>
SSH/SFTP port: <PORT>
Username: <REVIEW_USERNAME>
Password: <REVIEW_PASSWORD>
Expected SSH host fingerprint: <SHA256_FINGERPRINT>
Mosh UDP access: <ALLOWED_UDP_PORTS>
Demo directory: <DEMO_DIRECTORY>
Herdr workspace and launch steps: <VERIFIED_HERDR_STEPS>

Review steps:
1. In Hosts, add the connection above. Connect and verify its host fingerprint.
2. Run ls, htop, or vim on the test host. Test the Ctrl, Esc, and arrow-key controls.
3. Open the host's SFTP file browser. Browse the demo directory and upload or download a test file.
4. Open Connections to switch between sessions. Open Settings to change the interface language and terminal theme.
5. Follow the Herdr launch steps above to open the prepared editor and switch workspace tabs.
6. Agent review setup and a sample prompt: <VERIFIED_AGENT_STEPS_AND_TEST_ACCESS>
7. Remote-screen review setup: <VERIFIED_REMOTE_SCREEN_STEPS>
8. Optional voice input: <VERIFIED_SPEECH_PROVIDER_SETUP_AND_TEST_ACCESS>. Enable voice input in Settings, review the audio disclosure, grant microphone permission, and hold the microphone button in a terminal.

iOS may suspend background network activity. Mosh recovery requires the server-side session to remain available. Long-running remote tasks can use a server-side session manager such as tmux.

Speech recognition sends audio to the selected provider. Server-side Agent tools may forward prompts, context, and attachments to their configured model provider. These services do not use a Termish-operated relay.

Purchases in this build: <ACTUAL_PURCHASE_STATUS_AND_REVIEW_STEPS>
Review contact: <NAME_EMAIL_AND_PHONE>
```

本次代码检查未发现内购。如最终提交的构建仍没有内购，购买状态可写：
`This build does not include In-App Purchases. Server hosting, speech recognition, and model services are supplied separately by the user's chosen providers.`
如加入内购，改成实际入口和测试步骤，不能继续使用这句。

审核资源与准确功能说明依据 [Apple 审核要求](https://developer.apple.com/app-store/review/guidelines/#app-completeness)。

## 截图和 App 预览

- 已有 [6.5 英寸素材](ios/README.md)：中文 8 张、英文 7 张，全部为 1242 × 2688 JPEG，无透明通道，包含 Herdr 编辑器和工作区。
- 上传到对应语言的 **iPhone 6.5 英寸**栏位；不要将其他设备尺寸混入该栏位。
- 两段预览视频均为 886 × 1920，中文 27 秒、英文 29 秒，包含 Herdr 操作。
- 当前 `TARGETED_DEVICE_FAMILY=1`，不提供独立 iPad 版本素材。
- 当前素材对应 1.7.1（35）；若最终构建改动了购买入口、弹窗或布局，重新核对受影响截图和视频。
- 上传后等待 Apple 处理素材，并检查预览封面与播放效果。

规格参见 [截图要求](https://developer.apple.com/help/app-store-connect/reference/app-information/screenshot-specifications)
与 [预览要求](https://developer.apple.com/help/app-store-connect/reference/app-information/app-preview-specifications/)。

## Xcode Cloud 构建与最终提交检查

1. 核对 Xcode Cloud 构建对应的提交、Bundle ID 和签名团队；工作流必须包含用于分发的 Archive，并完成上传 App Store Connect 的步骤。
2. 构建成功后确认 App Store Connect 已处理完上传包；仅有 Xcode Cloud 的绿色成功状态不足以证明版本可选。
3. 核对商店版本与上传包的营销版本一致（本次为 `1.7.1`）。仓库包号是 `35`，Xcode Cloud 页面上的 `45` / `46` 是其构建序号；以 App Store Connect 实际解析出的版本及包号为准。
4. 确认包不是仅限内部测试的分发产物；标记为 TestFlight Internal Only 的包不能用于 App Store 提审。
5. 完成构建的加密合规信息，再在版本页选择要提交的构建。当前尚未确认此前“无法添加构建”的具体原因。
6. 在 TestFlight 安装实际候选包，验证 SSH、Mosh、SFTP、Herdr、Agent、可选语音和隐私入口；如有内购，验证购买、取消和恢复权益。
7. 填写中英文元数据，上传媒体，补齐审核联系人和公网测试环境；核对版权、价格、销售范围、年龄分级和 App 隐私。
8. 清除全部占位内容，核对上述检查项，然后由发行人执行提交。

参见 [上传与处理构建](https://developer.apple.com/help/app-store-connect/manage-builds/upload-builds/)、
[选择提审构建](https://developer.apple.com/help/app-store-connect/manage-builds/choose-a-build-to-submit)
及 [Apple 内部测试分发说明](https://developer.apple.com/tutorials/develop-in-swift/test-your-beta-app)。
