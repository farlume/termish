# Termish Privacy Policy / Termish 隐私政策

Effective date / 生效日期：2026-09-03

Contact / 联系方式：ttermish@gmail.com

## English

Termish is a local-first SSH, Mosh, SFTP, remote-screen, and remote Agent client.
It does not require a Termish account and does not include advertising, analytics,
or telemetry services.

### Data stored on your device

Host details, app preferences, snippets, and recent session metadata are stored on
your device. Passwords, private keys, and API keys are stored using the protected
credential storage supplied by the operating system. Termish does not upload these
credentials to a server operated by the Termish project.

Android backups are disabled for Termish. You can delete local data by removing the
corresponding host or provider, clearing the app's data in system settings, or
uninstalling the app.

### SSH, Mosh, SFTP, and remote-screen connections

Termish connects directly to servers that you configure. Terminal traffic, files,
remote-screen frames, and control events are transmitted between your device and
the selected server. They are not proxied, stored, or inspected by the Termish
project. The owner or operator of the destination server may process this data
under their own terms and privacy policy.

### Optional voice recognition

Voice input is disabled by default. When you enable it, Termish shows a disclosure
before requesting microphone permission. Audio is recorded only while you hold the
microphone button and is sent over an encrypted connection to the speech-recognition
provider that you configured. The currently supported provider is Volcano Engine.

Termish uses the audio only to obtain a transcription and does not intentionally
persist the recording. The provider may process or retain data according to its
own terms, account settings, and legal obligations. You can stop this processing
by disabling voice input or revoking microphone permission.

### Optional Agent providers

Agent tools run on the server you select. Depending on your configuration, prompts,
terminal context, working-directory information, and attachments may be sent from
that server to OpenAI, Anthropic, Volcano Engine, or another compatible provider.
This traffic does not pass through a server operated by the Termish project. Each
provider's terms and privacy policy apply.

### Notifications and background connections

Notifications are optional and are used for connection, transfer, and Agent-task
status. When you keep an active SSH session, Android may show a persistent
notification while a foreground service maintains the connection. Termish does not
use these capabilities for advertising or tracking.

### Diagnostics

Diagnostic logging is disabled by default. Logs stay on your device unless you
explicitly export and share them. Logs may contain technical context such as host
names, network addresses, file paths, commands, or error details. Review an exported
log before sharing it.

### Data sharing, retention, and deletion

The Termish project does not operate a central user account, analytics database, or
session relay and therefore does not retain user content on its own servers. Data
sent to a server or provider chosen by you is controlled by that party. Contact the
relevant provider or server operator to request deletion of data held by them.

### Children

Termish is a professional developer tool and is not directed to children under 13.

### Changes

This policy may be updated when Termish adds or changes data-handling features. The
effective date above will be revised, and material changes will be communicated in
the app or release notes where appropriate.

## 中文

Termish 是一款本地优先的 SSH、Mosh、SFTP、远程画面及远程 Agent 客户端。
它不要求注册 Termish 账号，也不接入广告、统计分析或遥测服务。

### 保存在设备上的数据

主机信息、应用偏好、命令片段和最近会话元数据保存在你的设备上。密码、私钥和
API Key 使用操作系统提供的安全凭据存储。Termish 不会将这些凭据上传至由
Termish 项目运营的服务器。

Termish 在 Android 上禁用了应用备份。你可以删除对应的主机或服务商、在系统
设置中清除应用数据，或卸载应用来删除本地数据。

### SSH、Mosh、SFTP 与远程画面连接

Termish 直接连接你配置的服务器。终端流量、文件、远程画面帧和控制事件只在
当前设备与所选服务器之间传输，不会由 Termish 项目代理、保存或查看。目标
服务器的所有者或运营者可能依据其自身条款和隐私政策处理这些数据。

### 可选的语音识别

语音输入默认关闭。启用时，Termish 会先展示数据用途说明，再申请麦克风权限。
只有在你按住麦克风按钮期间才会录音，音频会通过加密连接发送给你配置的语音
识别服务商。目前支持的服务商为火山引擎。

Termish 仅使用音频获取转写结果，不会主动持久化保存录音。服务商可能根据其
条款、账号设置和法律义务处理或保留数据。你可以关闭语音输入或撤销麦克风权限
来停止此项处理。

### 可选的 Agent 服务商

Agent 工具运行在你选择的服务器上。根据你的配置，提示词、终端上下文、工作
目录信息和附件可能由该服务器发送给 OpenAI、Anthropic、火山引擎或其他兼容
服务商。此类流量不经过 Termish 项目运营的服务器，并受各服务商的条款和隐私
政策约束。

### 通知和后台连接

通知是可选功能，用于提示连接、文件传输和 Agent 任务状态。保持活跃 SSH 会话
时，Android 可能展示常驻通知，并通过前台服务维持连接。Termish 不会将这些
能力用于广告或跟踪。

### 诊断信息

诊断日志默认关闭。只有在你主动导出并分享时，日志才会离开设备。日志可能包含
主机名、网络地址、文件路径、命令或错误详情等技术上下文，请在分享前检查内容。

### 数据共享、保留和删除

Termish 项目不运营中央用户账号、统计数据库或会话中继，因此不会在自有服务器
上保留用户内容。发送至你选择的服务器或服务商的数据由对应主体控制；如需删除
其持有的数据，请联系相应服务商或服务器运营者。

### 儿童隐私

Termish 是面向开发者和运维人员的专业工具，不以 13 岁以下儿童为目标用户。

### 政策变更

当 Termish 新增或变更数据处理功能时，本政策可能更新。上方生效日期会随之修订；
如有重大变化，将在适当情况下通过应用内提示或发行说明告知。
