# App Store Connect 提审材料（iOS）

> 上架信息草稿。App Store Connect 表单字段按本文逐项填写；商店文案以
> 中文（zh-Hans）为主，英文括注。截图规格与生成方式见文末。

## 商店信息（zh-Hans）

- **App 名称**（30 字符内）：`Termish 终端`
- **副标题**（30 字符内）：`SSH · Mosh · AI Agent 远程终端`
- **关键词**（100 字符，逗号分隔）：
  `ssh,mosh,terminal,终端,服务器,linux,sftp,agent,ai,vim,tmux,远程`
- **描述**（4000 字符内）：

```
Termish 是一款为手机打造的 SSH + Mosh 终端——会话在 WiFi 切换、锁屏、
漫游后依然存活；也是远端 AI agent（herdr / codex / claude）的口袋入口：
点开主机即进入真正的工作台，vim、htop、任意 TUI 都能在指尖运行。

【为什么选 Termish】
• Mosh 优先：纯 Kotlin 实现的 Mosh 客户端，UDP 漫游——地铁、电梯、
  WiFi↔流量切换，会话不断线；本地回显预测让高延迟下打字依然跟手
• 手机上的真终端：纯 Kotlin 终端模拟器（非 webview），VT100/xterm 转义
  序列、真彩色、CJK 宽字符、OSC 8 超链接全支持
• 中文输入法友好：拼音组合态绝不发往服务器，候选栏完整可用——
  webview 终端的拼音泄漏问题在这里不存在
• SFTP 文件管理：浏览/上传/流式下载/递归下载目录，进度通知一点即达
• 多会话管理：同主机多会话标签页、断线自动重连、后台保活
• 本地优先 & 隐私：无账号、无云同步、无遥测；凭据只存系统安全存储；
  可选语音输入仅在按住按钮时把录音发送给用户配置的识别服务商

【内置能力】
- 认证：密码 / 私钥（含加密私钥）/ keyboard-interactive；TOFU 主机指纹
- 主题：终端调色板（Dracula / Solarized…）可同步进远端 TUI
- 触控：CTRL/ALT/ESC 功能键栏，TUI 鼠标上报（vim/htop 可触控）
- 服务器端会话：启动命令支持 tmux，断线重连回到原现场

Termish 开源（MIT），代码可审计：github.com/ttermish/termish
```

- **推广文本**（170 字符，可随时改不发审）：`开源 SSH + Mosh 终端。会话漫游不断线，中文输入法友好，远端 AI agent 工作台。`

## 商店信息（en，可选英文站）

- **App 名称**：`Termish`
- **副标题**：`SSH & Mosh terminal`
- **关键词**：`ssh,mosh,terminal,sftp,linux,server,agent,ai,vim,tmux,shell,remote`
- **描述**：改编 README "Why Termish?" 段落 + Features 列表（英文站
  直接复用 README 文案即可）

## 表单其他字段

| 字段 | 填写值 |
|---|---|
| 主要语言 | 简体中文 |
| 类别 | 工具（Developer Tools 备选） |
| 价格 | 免费 |
| 隐私政策 URL | https://termish.dev/privacy（先上线该页面） |
| 支持 URL | https://termish.dev（或 GitHub Issues） |
| 营销 URL | https://termish.dev |
| 技术支持邮箱 | README 上的联系邮箱 |
| 年龄分级 | 全部选"无"→ 4+ |
| 版权 | `2026 Termish Project` |
| Sign in with Apple | 不适用（App 无账号体系；SSH 服务器凭据不属于 App 登录） |
| 加密合规 | Info.plist 已声明 ITSAppUsesNonExemptEncryption=NO（标准协议加密豁免） |

## 审核备注（App Review Notes，重要）

```
Termish 是 SSH/Mosh 客户端工具（同类：Termius、Blink Shell、Secure
ShellFish），App 本身不提供任何内容——连接的是审核员/用户自己的服务器。

审核专用测试服务器（无需自备环境）：
  主机: termish-demo 仓库的 Docker 容器（见下方"审核环境"部分）
  若使用公网演示服务器，在此填：主机名 / 端口 / 用户名 / 密码
  （建议专门起一台一次性 demo 服务器，审核通过后即改密码/下线）

功能验证路径：
1. 添加主机（填入上面的测试服务器）→ 点击卡片连接 → 确认主机指纹
2. 终端内运行 ls / vim / htop 验证交互
3. 底部工具栏 CTRL/ESC/方向键可用；中文输入法打字正常
4. Sessions 页可断开/重连；SFTP 页可浏览、下载文件
5. 设置页可切换中英文、终端主题

已知平台行为（非缺陷）：iOS 后台挂起时系统会断开 socket，回到前台
App 自动重连并恢复屏幕缓冲；如需服务器端不间断会话，配合 tmux 或
Mosh 模式使用（商店描述已注明）。
```

> ⚠️ 演示服务器方案二选一：
> A. 用仓库 `termish-demo` Docker 镜像在公网 VPS 起一台，审核期保持在线
> B. App Review 的"演示账号"填不上 SSH 场景时，把连接信息写在备注里
> （Termius 等同类 App 的通行做法）

## 截图（Screenshots）

- 6.9"（必须）：iPhone 17 Pro Max 分辨率 1320×2868（或 1290×2796）
- 5.5"（可选，如不上 6.5" 以下机型可跳过——App Store Connect 会用
  6.9" 自动缩放；iPad 不发布（TARGETED_DEVICE_FAMILY=1）则无需 iPad 图）
- 建议 5 张：主机列表 / 终端（SSH 到 demo 服务器跑 htop）/ 终端（herdr
  agent 工作台）/ SFTP 文件列表 / 设置-主题页——素材直接用仓库
  docs/screenshots/ 现有图重制（英文 UI 用 -en，中文站用 -zh）
- 中英两套（zh-Hans + en 本地化各传一套）

## 上传前检查单（Mac 上）

1. Xcode 签名：Signing & Capabilities 选 Team（Apple Development
   Program），Bundle ID `dev.termish.app` 注册到该 Team
2. `make ios-native && make ios-framework` 后 Archive（Device 目标）
3. 上传 App Store Connect → TestFlight 自测 → 提审
4. 隐私政策页面上线（termish.dev/privacy）
5. 图标/启动屏确认（1024 图标已有；LaunchScreen 为空 dict = 系统默认）

## 隐私政策要点（给 termish.dev/privacy 页面用）

- 无账号、无遥测、无分析 SDK、无广告；Termish 项目不运营数据中继
- 连接数据（主机地址/凭据）只存设备本地：iOS Keychain / 系统安全存储
- 网络流量只发生在你与你自己配置的服务器之间（SSH/Mosh 直连）
- 可选语音输入会将录音加密发送至用户配置的识别服务商（目前为火山引擎）
- 远程 Agent 可能把提示词/附件发给用户配置的模型服务商，均不经 Termish 服务器
- 详细政策以 `docs/appstore/privacy-policy.md` 和线上 `/privacy` 为准
- 开源可审计：github.com/ttermish/termish（MIT）
