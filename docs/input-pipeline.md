# 输入管线（IME / 键盘 / 触摸）

> **English summary:** how keyboard input reaches the remote shell — a hidden
> `BasicTextField` as the IME entry point, composition-safe CJK input (composing
> text never hits the wire; only committed text is diffed), split backspace
> semantics, sticky CTRL/ALT, bracketed paste, and the touch-to-mouse-event
> mapping for TUIs with mouse reporting enabled.

Termish 的输入管线围绕一个原则设计：**IME 组合态（拼音）是一等公民**——组合态不上线、
提交才发送，远端行永远不会被拼音污染。实现集中在 `ui/TerminalScreen.kt`（状态管理）、
`ui/TerminalView.kt`（触摸手势）、`ui/KeyToolbar.kt`（功能键）。

## 输入接入口：隐藏 BasicTextField

- 一个 1dp 透明 `BasicTextField` 悬浮在工具栏区，仅作为输入法接入口，**不渲染可见 UI**——打字内容以远端回显为准（远端 echo 才是真实状态，本地显示反而重复干扰）。
- `KeyboardType.Text`（不是 Ascii）：保中文候选栏；`capitalization = None`、`autoCorrect = false`。
- `singleLine = false` 多行模式：捕获输入法 Enter（`\n` → 发 CR）。
- 点画布/⌨ 键时 `requestFocus()` + 延迟 150ms `show()`：焦点变更与输入连接建立是异步的，立即 show 常被忽略；`onFocusChanged` 里再兜底 show 一次。
- 输入法组合期间**组合文本绝不发往远端**。

## 提交 diff 算法

已提交文本基线 `committedText` + 输入框当前值做**公共前缀 diff**：

```
p = 最长公共前缀长度
发送 oldText.length - p 个 0x7f（删除尾部差异）
发送 newText.substring(p)（新增部分，经 sendTyped 处理 CTRL/ALT/换行）
```

- 纯尾部增删是精确操作；中间编辑无法映射到远端光标，按"删尾重发"处理。
- 输入框缓冲 >64 字符或含 `\n` 时清空（防长粘贴积压 + Enter 后复位）。

## 退格语义（三段式）

| 场景 | 行为 |
|------|------|
| 输入法组合中 | 不拦截：IME 自己删拼音（组合文本本就没发给远端） |
| 输入框有已提交内容 | 不拦截：平台删除 → onValueChange diff 发送（光标/选区由系统维护） |
| 输入框为空 | `onPreviewKeyEvent` 拦截 Backspace，直接发 0x7f —— Enter 后/快速命令后远端行有内容也删得掉 |

## 字符发送（sendTyped）

- ALT 粘性 → 前缀 `ESC`（0x1b）。
- CTRL 粘性 + a–z → `(ch.code and 0x1f)`。
- `\n` → CR（0x0d）。
- 其余 → UTF-8 字节直发。

**粘性修饰键消费规则**：任何键发出后（工具栏 onKey / onChar / onPaste）CTRL/ALT 立即复位，
避免残留导致下个字母变成 ⌃D/⌃C 意外退出会话。

## bracketed paste

工具栏 PST（或系统粘贴）：远端开启 DECSET 2004 时包裹 `ESC[200~ … ESC[201~`，
vim/nvim 不会把粘贴内容当手打（避免自动缩进错乱）。OSC 52 反向通道（远端写剪贴板）
受设置开关控制，静默写入不弹提示。

## 焦点事件

DECSET 1004 开启时，输入框聚焦/失焦发 `CSI I` / `CSI O`（vim/tmux 据此刷新界面状态）。

## 功能键工具栏（固定两行 8+8，无展开层）

```
行1: CTRL  ALT  ESC  TAB  ⌃C  ↑  ⌃L  ⌨
行2: ⌃D    PST  /    ⌃E   ←  ↓  →   ENT
```

- ↑ 与 ↓ 上下对齐，⌨ 与 ENT 同在右上/右下拇指区。
- CTRL/ALT 粘性 + 系统键盘字母即 ⌃A/⌃E/⌃R 等组合，不设专用按钮；符号键由系统键盘提供；画布惯性滚动替代 PgUp/PgDn。
- 方向键按 `applicationCursorKeys`（DECCKM）在 CSI A–D 与 SS3 A–D 间切换。
- `SpecialKey` 枚举仍定义 F1–F12/HOM/END/PGUP/PGDN/DEL 等（`specialKeyBytes` 有映射），但当前 UI 无渲染入口，属预留定义。

## 触摸 → 终端鼠标事件（TUI 鼠标上报）

远端开启鼠标上报（1000/1002/1003）时，画布手势被接管并映射为鼠标事件；未开启时不消费
事件，保持聚焦键盘/选择/滚动回看的默认行为。编码按模式：X10（字节偏移 32，上限 223）、
SGR 1006、urxvt 1015（与 1006 互斥）。

| 手势 | 映射 | 细节 |
|------|------|------|
| 轻点 | 同格 Down + Up | 释放用按下格，避免手指抖动被 herdr 的 1 格拖拽阈值判成拖拽 |
| 单指纵向拖拽 | 滚轮（64/65） | 位移主导方向锁定（防横向抖动误判）；**滚轮行钳制到第 1 行起**——第 0 行在桌面布局下是 herdr 的 tab 栏，滚到 0 会疯狂切 tab |
| 单指横向 / 双指拖拽 | Down + drag-motion(32) + Up | 选字、拖分割线、pane 内拖拽（需 1002/1003） |
| 纵向拖拽抬起 | 惯性滚轮 | 近 150ms 窗口估速（裁剪长按期的静止事件），衰减循环补发 |

手势意图明确前不补发 Down（轻点=点击、纵向=纯滚轮），避免"按下后在其他格抬起"被 herdr
判成拖拽选区并复制 → OSC 52 → 反复弹剪贴板提示。

## 滚动回看（非鼠标模式）

- `scrollable` fling 惯性；右边缘可拖滚动条。
- DECSET 1007 alternate scroll：备用屏无回看可滚，滚动手势转方向键交给远端全屏程序（less/vim）。
- 键盘弹出时画布按光标位置向上平移（不改 PTY 尺寸，避免全屏程序随键盘弹收反复重排），平移计算放 `graphicsLayer` 块内，不触发重组。

## 快速命令与启动命令

- 主机可配 quick commands（如常用运维命令），连接后以 chip 形式显示在画布上方，点按即发。
- startup command（如 `tmux new -A -s main`）连接成功后自动执行，配合自动重连实现服务端会话现场恢复。

## 相关设置项

工具栏显隐、自动重连、TOFU 首次确认、OSC 52 剪贴板、触感反馈——见设置页（apply-on-change 即改即存）。
