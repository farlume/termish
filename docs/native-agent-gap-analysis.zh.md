# 原生 Agent 功能差距评估

评估基线：Bridge 协议 v3 / Bridge 0.7.8。当前已覆盖原生对话、流式消息、
Thinking/工具过程卡、事件游标补发与运行中活动快照恢复、会话恢复、停止、附件、可执行快捷命令、Pi RPC，以及按 Agent
选择内置登录或 DeepSeek（Claude Code / OpenCode / Pi）。Agent 输入框复用全局流式
语音识别配置，支持实时转写、确认后发送以及错误/超时可见反馈。远端 Agent CLI
自己的会话可搜索并导入 Bridge；导入只复制可展示消息，保留原生 session ID 续接
上下文，不删除或改写 Agent 的历史文件。

## 审批语义

Termish 应继承 Agent 自己的权限策略，而不是另设一套默认策略：

1. Agent 的规则自动允许时，工具直接执行，Termish 不弹窗。
2. Agent 的规则明确拒绝时，展示拒绝结果，Agent 可继续调整方案。
3. Agent 请求用户决定时，Bridge 发出 `approval_request`，手机提供“允许一次 /
   本会话允许 / 拒绝”，再用 `approval_response` 恢复 Agent。
4. 断线后审批仍属于远端会话；重连应恢复待审批列表。会话删除、终止或审批
   超时必须 fail closed，不能自动允许。

Codex 已切换到 app-server，命令、文件修改、网络访问和权限请求会进入统一审批队列；Pi RPC
的 confirm/select/input/editor 也通过同一队列恢复。待审批项在 Bridge 进程存活时可
跨 SSH 断线恢复，停止、超时与会话销毁均 fail closed。Claude、Gemini 与 OpenCode
仍需分别接入它们的原生双向协议，且 Bridge 进程重启后的审批持久化与审计尚未完成。

## P0：可靠性与安全闭环

已完成的可靠性基线：每个会话事件带 Bridge 代际与单调游标，短断线按游标从
2048 条有界日志补发并严格去重；Bridge 重启、客户端游标异常或缓存窗口溢出时，
自动降级为包含部分回答、真实思考和运行中工具的原子快照，不会把旧代际事件
错误拼接到新会话状态。

- 登录就绪探测：Codex、Claude（支持新版 auth status 的版本）、Gemini、OpenCode 与
  Pi 均在附件上传和消息落库前区分“已安装”和“已登录/供应商密钥有效”；仍需提供
  设置页的一键登录指引与供应商在线连通测试。
- 手机审批与 Agent 提问：Codex 与 Pi 已有独立审批弹窗、断线恢复和超时/终止拒绝；
  仍需补 Bridge 重启持久化、后台通知、审批历史，以及 Claude/Gemini/OpenCode 原生通道。
- 终态保证：异常退出、启动超时与取消目前都会清除 busy，并保留发送失败时的草稿
  与附件；后续协议仍需显式 completed / failed / cancelled / waiting-approval 状态。
- 凭据边界：API Key 仅存系统安全存储、仅随当前 SSH 请求进入 Agent 环境；Bridge
  会在事件、stderr、数据库消息和协议错误进入客户端前统一脱敏。诊断包仍需端侧
  再做一次纵深脱敏审计。
- 附件配额与回收：单文件/单轮/工作区上限、重复名处理、上传中断清理、会话删除
  后清理，以及图片/目录能力声明。

## P1：完整的日常工作流

- 每个 Agent 独立保存供应商、模型与工作目录；从 Agent/供应商发现模型，不再
  依赖一个全局自由文本模型字段。
- 供应商管理增加“测试连接”、禁用、错误状态和兼容矩阵；再扩展 OpenAI-compatible、
  Anthropic-compatible 与自定义 endpoint。
- 后台任务通知、失败重试、排队发送、编辑并重发、重新生成，以及多会话并行状态。
- 展示 token/费用/上下文占用、实际模型、压缩和重试状态。
- 工具结果专用视图：diff、文件列表、命令退出码、超长日志分页和复制。
- 会话搜索、置顶、归档、导出；远端 Bridge/CLI 版本诊断与一键修复。

## P2：增强能力

- herdr/终端会话接管与 Agent 原生会话互相跳转。
- 多设备审批与状态同步、端到端加密同步供应商配置。
- Agent 能力协商：结构化输出、图片、MCP、子 Agent、计划模式和自定义命令。
- 细粒度策略可视化与审批历史，但只展示/编辑 Agent 支持的真实策略，不能制造
  一个与 Agent 配置相冲突的 Termish 权限层。

## 验收门槛

- 未登录、无效 Key、余额/限流、审批等待、Agent 崩溃、Bridge 重启和 SSH 断线
  都有稳定、可恢复且不无限 loading 的结果。
- 自动允许、明确拒绝、允许一次、本会话允许四条权限路径均有真实 Agent E2E。
- Android 模拟器覆盖主流程；鉴权安全存储、后台恢复与通知需 Android/iOS 真机抽查。
