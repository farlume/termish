# Termish Agent Bridge

> **English summary:** a bundled Rust companion runs Agent CLIs behind the user's SSH connection. It provides persistent sessions, streaming events, bounded history and native approval forwarding without a public listening port.

## 安装与运行

手机通过 SSH 探测系统和架构，用 SFTP 上传内置二进制，校验 SHA-256 与服务版本后原子启用。
支持 macOS/Linux 的 arm64、x86_64；远端无需 Rust 编译器。Agent 自身仍需登录或配置服务商，
缺失的 CLI 可通过手机触发用户目录内的 npm 安装，无需 sudo。

```text
Termish → SSH exec → termish-agent connect → 私有 Unix socket → Rust daemon → Agent CLI
```

构建与测试：

```bash
sh scripts/service-build.sh build-agent --all  # Mac + Xcode SDK，一次构建四种目标
./gradlew agentBridgeBuild                    # 构建并打包本机可用目标
./gradlew agentBridgeTest                     # 无模型账号的协议与 adapter 测试
sh scripts/service-test.sh agentBridge        # 不依赖 Gradle 的相同测试
```

二进制和清单位于 `agentBridge/build/binaries/`；生成的 Kotlin 位于
`composeApp/build/generated/agentBridge/kotlin/`；Compose 文件资源位于
`composeApp/src/commonMain/composeResources/files/termish-agent/`。生成产物不提交。

## 存储与生命周期

```text
~/.local/share/termish-agent/
├── current/termish-agent
├── current/termish-agent.NOTICE
├── data/sessions/<id>.json
├── npm/                         # 可选的用户目录 CLI 安装
└── runtime/rust/{agent.sock,agent.pid,agent.log,agent.version}
```

服务版本从 0.9.0 起使用独立 JSON 存储，旧版本的 SQLite 记录不迁移、不读取。
每次保存以临时文件和原子替换落盘；目录权限为 0700，文件/socket 为 0600，umask 为 077。
原生运行目录独立于旧服务，避免旧 socket 被当成 Rust 服务。
手机安装并成功启动原生服务后，会核对进程所属账号、完整命令和 Python 解释器，
停止该账号的旧 Python companion，并删除 `current/termish-agent.pyz`。
若旧进程未退出，保留旧文件并报告安装失败；旧 SQLite 数据与当前 Rust 会话不删除。

`ensure-running` 按需启动 daemon，`restart` 经私有 socket 请求旧 Rust 实例退出后重新启动。
无需 systemd 或 root。SSH relay 断开不终止 Agent 任务；重连可获取正在运行的文本、工具和审批。
服务退出或重启会取消自身任务并清理子进程组，之后可继续持久化的会话。

## 协议与适配器

SSH stdin/stdout 与 socket 之间使用 NDJSON，协议版本 3，单请求上限 8 MiB。
支持 `system.hello`、Agent 检查/安装、会话创建/列表/重命名/模型设置/分页/删除、
原生历史发现与导入、prompt 发送/取消、审批答复和事件回放。

适配 Codex App Server、Claude Code stream-json、Gemini CLI stream-json、OpenCode JSON
和 Pi RPC。事件统一表达文本、思考、工具开始/结束、错误、取消、结算、安装输出与 busy 状态，
保留 turn/activity/message ID、顺序和时间戳。

Codex 的命令、文件变更和权限请求，Pi 的 confirm/select/input/editor 请求转发手机审批；
待审批请求在 SSH 重连后仍可回答。超时、取消或不支持的审批会拒绝，服务不附加自动批准参数。
命令解析覆盖 PATH、常见用户安装位置和 NVM。真实 CLI/模型行为仍需账号环境验证。

原生历史可发现并导入 Codex、Claude、Gemini、OpenCode、Pi 的会话，保留原生 resume ID，
原始文件不修改。附件来自工作区的 `.termish/attachments/`，拒绝越界和符号链接逃逸；
记录随会话删除，共用附件不提前移除，过期未引用文件在启动时回收。

服务商配置 ID 与 adapter 类型分别传输。API key 仅用于当前任务的环境变量或模型查询，
不写入会话，错误和事件会脱敏。`providers.fetchModels` 使用远端 curl；Agent 本身仍可能有
自己的凭据存储。DeepSeek 支持 Claude Code、OpenCode 和 Pi 的对应接口。
