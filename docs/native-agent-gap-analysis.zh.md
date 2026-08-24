# 原生 Agent 功能差距评估

评估基线：Bridge 协议 v2 / Bridge 0.4.0。当前已覆盖原生对话、流式消息、
Thinking/工具时间线、会话恢复、停止、附件、快捷命令、Pi RPC，以及按 Agent
选择内置登录或 DeepSeek（Claude Code / OpenCode / Pi）。

## 审批语义

Termish 应继承 Agent 自己的权限策略，而不是另设一套默认策略：

1. Agent 的规则自动允许时，工具直接执行，Termish 不弹窗。
2. Agent 的规则明确拒绝时，展示拒绝结果，Agent 可继续调整方案。
3. Agent 请求用户决定时，Bridge 发出 `approval_request`，手机提供“允许一次 /
   本会话允许 / 拒绝”，再用 `approval_response` 恢复 Agent。
4. 断线后审批仍属于远端会话；重连应恢复待审批列表。会话删除、终止或审批
   超时必须 fail closed，不能自动允许。

当前一次性 CLI 适配器不能完整实现第 3 项。后续应按能力切换到 Codex
app-server、Claude Agent SDK、OpenCode server API；Gemini 需评估 ACP，Pi 则需
使用扩展级审批接口。普通 Gemini headless 的 `ask_user` 会按拒绝处理，Pi RPC
也没有通用的内置工具审批回调。

## P0：可靠性与安全闭环

- 登录就绪探测：区分“已安装”和“已登录/密钥有效”，提供登录指引、供应商
  连通测试和可读的鉴权错误。
- 手机审批与 Agent 提问：持久化待处理请求、重连恢复、超时拒绝、通知和审计；
  不能把审批伪装成普通聊天消息。
- 事件游标与补发：当前断线期间的实时 delta 不可回放；协议需给事件单调序号，
  客户端按游标追平，避免重复或缺失。
- 终态保证：每个 Turn 必须落到 completed / failed / cancelled / waiting-approval；
  Bridge 或 Agent 异常退出后清除 busy，并保留草稿与附件。
- 凭据边界：API Key 仅存系统安全存储、仅随当前 SSH 请求进入 Agent 环境；日志、
  stderr、数据库和诊断包需要统一脱敏。
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
