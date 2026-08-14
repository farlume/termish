# 终端模拟器（term/）

> **English summary:** design and capability notes for the pure-Kotlin VT100/xterm
> emulator — color model, dual screen buffer with COW line sharing and
> (identity, version) line-level incremental sync, wide-char bookkeeping,
> the supported escape-sequence matrix, and the renderer's text-layout cache.
> Any change here must come with a unit test in `commonTest/`.

`commonMain/term/` 是项目的核心资产：纯 Kotlin、零平台依赖、可单元测试的终端模拟器。
四个文件，职责分明：

| 文件 | 职责 |
|------|------|
| `TerminalColor.kt` | 颜色编码、xterm 256 色板、单元格属性位、简易 wcwidth |
| `TerminalBuffer.kt` | 屏幕缓冲：普通屏+备用屏、滚动回看、光标/模式状态 |
| `TerminalEmulator.kt` | UTF-8 增量解码 + VT100/xterm 状态机 + OSC/DCS 处理 |
| `TerminalSelection.kt` | 文本选择与复制 |

渲染器 `ui/TerminalView.kt` 在 term/ 之外（Compose 依赖），但渲染性能约定与
term/ 的行模型强耦合，一并记录。

## 颜色模型

- 单元颜色为 `0xRRGGBB` Int；`DEFAULT_FG = -1` / `DEFAULT_BG = -2` / `DEFAULT_CURSOR = -3` 是哨兵值，渲染时按主题默认色解析。
- `TerminalPalette.BASIC_16`（VGA 标准）+ 256 色板（16 基础 + 216 立方体 `0/95/135/175/215/255` + 24 灰度 `8+10n`）。
- `CellAttr` 位掩码：BOLD/DIM/ITALIC/UNDERLINE/BLINK/INVERSE/HIDDEN/STRIKE。
- `CharWidth.wcwidth`：常用全角/CJK/emoji 区间返回 2，其余 1（移动端 SSH 场景够用的简化实现，非 Unicode TR11 全量）。

## 缓冲模型

### 双屏幕

- **普通屏**：`ArrayDeque<TerminalLine>`，末尾 `rows` 行是可见屏幕，之前的行是滚动回看（上限 `maxScrollbackLines`，UI 缓冲 10,000；mosh 影子终端 `Int.MAX_VALUE`——影子必须完整镜像服务端，diff 才不错位）。
- **备用屏**：固定 `rows×cols`，无回看（vim/tmux/htop 等全屏程序）。1049 进入时清屏（xterm 行为），1047/47 不清。

### 行级 COW 与增量同步（渲染性能的关键）

每一行 `TerminalLine` 携带：

- `version: Long` —— 单元格实际被改写时递增（`touch()`）；
- `identity: Any` —— 逻辑行身份，克隆/复制时保留；
- `shared: Boolean` —— COW 标记：为 true 的行不可原地修改，写前必须克隆（`mutableLine`）。

两个核心 API：

- `shallowFork()`：行对象共享、写时复制，O(行数) 而非 O(单元格)——mosh 影子状态分叉用（行对象共享的写时复制）。分叉用 `rows=0` 构造再追加源行，避免每代分叉累积幻影空行。
- `copyContentFrom(other)`：**行级增量同步**——同 identity 且 version 未变的行直接复用，只克隆变化行。UI 缓冲从 mosh 影子拷贝每帧状态时，未变行零拷贝；返回是否有视觉变化，无变化跳过重绘。

### 宽字符（全链路 2 格）

- 写入时预计算 `width`（1/2），渲染不再调 wcwidth。
- 尾巴（`isWideTail`）**继承头的颜色/属性**：否则宽字符右半格露出默认背景，在带底色的行（如 agent TUI 的消息条）上表现为黑块盖住半个字。
- 覆盖语义：写到尾巴上先清头；覆盖宽字符头清掉尾巴标记；尾巴盖掉原宽字符头时清掉其原尾巴标记。
- 行末写宽字符：DECAWM 开则换行，关则按窄字符覆盖最后一格。
- `pendingWrap`：行末写窄字符只标记延迟换行，下一个字符进入时消费（xterm 语义；DECAWM 关时钳在行末覆盖）。

### resize（xterm 式）

- 屏幕变矮（键盘弹出）：优先丢弃**光标下方的空白行**，保证光标与提示符留在可视区；不足的收缩量自然把顶部行挤入回看。
- 屏幕变高：从回看取回或新建空行。
- 每行宽度调整时共享行先克隆（COW）；截断若让宽字符尾巴悬空则清掉。
- 修改 `term/` 的 resize/宽字符逻辑必须同步补 `TerminalBufferTest`。

## 状态机与序列支持

`TerminalEmulator`：增量 UTF-8 解码（非法序列按 U+FFFD 或重新起头）+ 十态状态机
（GROUND / ESCAPE / ESC_INTERMEDIATE / CSI_ENTRY / CSI_PARAM / CSI_INTERMEDIATE /
OSC / OSC_ESC / DCS / DCS_ESC）。

### C0 控制

| 序列 | 行为 |
|------|------|
| NUL, BEL | 忽略 |
| BS | 退格（撤销 pendingWrap 优先） |
| HT | 跳到下一 tab stop（默认每 8 列） |
| LF, VT, FF | 换行（受滚动区域约束） |
| CR | 回车 |
| SO / SI | 切换 G1 / G0 字符集 |
| 其余 C0、DEL | 忽略 |
| C1（0x80–0x9F） | 忽略（不支持，不应当字符画出） |

### ESC 序列

`7` DECSC / `8` DECRC（保存恢复光标 **及 pendingWrap、字符集**——tmux 依赖完整状态）；
`D` IND、`E` NEL、`M` RI、`H` HTS、`c` RIS、`=` DECKPAM、`>` DECKPNM；
`(` / `)` 字符集指定（`0` = DEC 特殊图形）、`#8` DECALN。

### CSI

| 类别 | 序列 |
|------|------|
| 光标移动 | CUU/CUD/CUF/CUB/CNL/CPL/CHA/CUP(HVP)/VPA |
| 擦除 | ED 0–3（含清回看）、EL 0–2、ECH |
| 插入/删除 | IL/DL/ICH/DCH |
| 滚动 | SU/SD、DECSTBM（`r`） |
| 光标保存 | SCOSC/SCORC（`s`/`u`） |
| 属性 | SGR（见下）、DECSCUSR（`q`，1–6） |
| 应答 | DSR（5/6）、DA（`ESC[?1;2c`）、DA2（`ESC[>1;2;0c`）、DECRQM（`$p`） |
| 其它 | REP（`b`，重复上次字符，上限 1024） |

### 模式（SM/RM）

| 模式 | 含义 |
|------|------|
| 4 | IRM 插入模式 |
| ?1 | DECCKM 应用光标键 |
| ?6 | DECOM 原点模式 |
| ?7 | DECAWM 自动换行 |
| ?25 | DECTCEM 光标显隐 |
| ?47 / ?1047 / ?1049 | 备用屏（1049 进出带光标保存/恢复） |
| ?1048 | 光标保存/恢复 |
| ?2004 | bracketed paste |
| ?1000 / ?1002 / ?1003 | 鼠标上报（X10 / 按钮拖拽 / 全事件） |
| ?1004 | 焦点事件（CSI I / CSI O） |
| ?1006 / ?1015 | SGR / urxvt 鼠标坐标格式（互斥） |
| ?1007 | 备用屏滚轮转方向键 |
| 20 | LF→CRLF：**有意忽略** |

### SGR

`0/1/2/3/4/5/7/8/9`、`21→下划线`、`22/23/24/25/27/28/29`、`30–37`、`38;5;n`、`38;2;r;g;b`、
`39`、`40–47`、`48;5;n`、`48;2;r;g;b`、`49`、`90–97`、`100–107`。

### OSC

| OSC | 行为 |
|-----|------|
| 0/1/2 | 标题变更 |
| 4;idx;? | 调色板查询（可多对，应答 xterm `rgb:rrrr/gggg/bbbb` 格式） |
| 8 | 超链接开始/结束 |
| 10/11 | 默认前景/背景色**查询**（TUI 如 herdr 据此决定对比色，必须应答；设置暂不支持） |
| 12 | 光标色查询/设置（仅 `#RRGGBB`） |
| 52 | 剪贴板写入/查询（受设置开关控制，base64） |
| 其它 | 忽略 |

### DCS

- `$q` DECRQSS：应答 DECSTBM / SGR（仅默认态）/ DECSCUSR 查询，无效查询回 `0$r`。
- 其余 DCS（sixel / tmux passthrough 等）忽略，缓冲限长 64KB 防内存膨胀。

## 渲染（ui/TerminalView.kt）

- **行级文本布局缓存**：`(identity, version)` 键 + LRU 256 行，未变行重绘只回放
  `TextLayoutResult`，跳过文本测量——全屏文本布局是每帧最贵的部分，滚动时整屏平移几乎全部命中。
- 样式缓存：按 (ARGB, bold, 窄/宽字体) 缓存 TextStyle；BOLD + 基本 8 色提亮到 8–15（xterm 惯例）。
- 只设底色、留默认前景的单元按背景亮度自动取黑/白前景（浅色主题下深底+深字不成黑块）。
- 字体：JetBrains Mono（捆绑，各端度量一致）；CJK 回退（iOS 显式 PingFang SC）。
- 光标：DECSCUSR 稳态样式（2/4/6）常亮，闪烁样式按 530ms 相位交替；块状光标下字符反色重绘。
- 目标列数模式：按 12sp 参考测量反算字号，逐次实测校正列数（见 `effectiveFontSizeSp`）。

## 测试纪律

任何 `term/` 改动（转义序列、缓冲行为、宽字符）必须配 `commonTest/` 单测：
`TerminalBufferTest` / `TerminalEmulatorTest` / `TerminalColorTest` / `TerminalSelectionTest`。
