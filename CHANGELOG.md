# Changelog

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.8.6] - 2026-10-05

### 新增

- **Linux 桌面管理**：增加带 Termish 图标的系统托盘和应用列表入口，支持查看连接状态、暂停／恢复访问、断开连接、重启／退出服务、查看日志与打开服务目录；中英文显示，无托盘宿主时可打开管理窗口
- **Linux 硬件编码**：自动探测 NVENC、QSV、VAAPI 的实际可用性，不可用或运行中失败时回退软件编码；增加编码器诊断与选择配置

### 修复

- **Linux 服务生命周期**：systemd 重启与正常退出采用不同退出状态；修复有用户 D-Bus 但无 systemd 用户管理器时安装中断的问题，回退登录自启动
- **Linux 显示状态**：补齐本地图形会话锁定状态与 X11 显示器电源检测，手机端可读取被控端状态

### 验证说明

- X11 画面与输入、真实 VAAPI 编码、systemd 重启／退出、完整安装与 LXQt 托盘操作，以及四架构构建、Android/iOS debug 和 SSH/SFTP 集成测试通过
- 屏幕服务更新至部署版本 59，安装／更新服务后生效；Wayland 锁屏后的采集与控制仍受桌面环境限制，尚未完成真实 GNOME/KDE 锁屏、休眠与解锁验收

## [1.8.5] - 2026-10-05

### 修复

- **旧屏幕服务残留**：原生服务安装并验证启动成功后，清理旧 Python 脚本及对应字节码缓存，保留配置、token、日志与其他文件
- **旧 Agent 后台进程**：原生 Bridge 成功启动后，核对账号、完整命令和解释器，停止旧 Python companion 并清理旧安装文件；旧进程未退出时保留文件并报告失败，保留会话数据

### 验证说明

- 清理边界、进程核验、退出失败、路径含空格、重复清理和缓存符号链接测试，以及 Android debug 和 SSH/SFTP 集成测试通过；已在真实 Mac 备份并清理旧残留，当前 Rust 服务保持正常运行
- 本次修改手机端安装流程，屏幕服务仍为版本 58、Agent Bridge 仍为 0.9.0；清理在安装／重新安装服务时执行

## [1.8.4] - 2026-10-05

### 新增

- **macOS 菜单栏**：Termish Helper 显示菜单栏图标，提供连接状态、断开当前连接、暂停／恢复访问、录屏与控制权限设置、重启服务、查看日志和打开服务目录；按系统语言显示中文或英文

### 优化

- **服务退出与重启**：菜单动作复用现有会话清理，释放输入并回收编码器；正常退出不再被 LaunchAgent 立即拉起，异常退出仍自动重启，下次登录仍自动启动
- **菜单与连接并行**：菜单在 macOS 主线程运行，连接管理在后台线程运行，继续使用原有 FFmpeg 采集和编码方案

### 验证说明

- macOS 原生菜单动作、真实合成视频连接的暂停／恢复／断开／重启／退出、隔离 LaunchAgent 生命周期、四种目标构建及 SSH/SFTP 集成测试通过；无窗口菜单的手动视觉点击验收未完成
- 屏幕服务更新至部署版本 58；暂停只在当前进程有效，重启后恢复访问。macOS 服务仍采用临时签名，升级后若权限失效，需要重新授权 Termish Helper 并重启服务

## [1.8.3] - 2026-10-04

### 修复

- **macOS 授权后重连**：收到录屏或辅助功能权限缺失提示后，用户点击「重新连接」会先重启屏幕服务，刷新授权前缓存的拒绝结果，再恢复画面与操作
- **辅助功能提示**：增加「重新连接」按钮，中英文指引改为授权后重连；不再默认要求删除权限条目
- **重连会话清理**：主动重连先关闭旧会话，避免屏幕服务重启触发旧会话的自动重连

### 验证说明

- 已在真实 Mac 确认两种权限开启后旧进程仍报拒绝、重启后恢复录屏与控制；用户已确认手机操作恢复
- 本次修改手机端重连流程，后台服务保持版本 57；Android/iOS 构建、单元测试和 SSH 集成测试通过。新手机端完整首次授权及撤销权限流程仍需实机验证

## [1.8.2] - 2026-10-04

### 修复

- **macOS 录屏权限**：独立预检录屏授权，缺权限时不启动或重启 FFmpeg；同一服务进程只请求一次录屏授权，手机收到明确拒绝后停止自动重连，避免连接循环触发弹窗
- **权限提示**：录屏与辅助功能分别提示，授权失效时指引重新添加当前后台应用，不再将录屏问题混为控制权限问题

### 优化

- **后台应用名称与图标**：macOS 服务打包为带 Termish Logo 的 `Termish Helper.app`，应用与进程名称统一，在后台运行；手机仍可经 SSH/SFTP 自动安装
- **应用包安装**：校验上传 SHA-256、应用签名和部署版本后启用，配置与 token 保留在应用包外；启动失败恢复旧应用与服务注册
- **发行签名构建**：增加固定证书签名入口，以稳定应用标识签名完整应用包，再生成安装校验摘要

### 验证说明

- 录屏门禁、视频协议、SSH 上传、应用包签名、安装迁移与失败恢复测试，以及 Android/iOS 本地构建已通过；真实首次授权、撤销授权和权限弹窗显示仍需实机确认
- 本次尚未接入 Developer ID 发行证书，macOS 服务仍使用临时签名，不能保证升级后保留授权。迁移后如权限失效，请在系统设置中重新授权 `Termish Helper.app`

## [1.8.1] - 2026-10-04

### 修复

- **macOS 远程控制权限**：改用事件发送权限检测并处理主线程系统事件，避免常驻服务授权后仍使用旧的辅助功能判断；兼容旧版 macOS 的权限接口
- **授权重复提示**：同一服务进程最多请求一次控制权限，连续操作或重新连接不会重复触发授权弹窗
- **手机权限状态**：被控端每秒检查控制权限变化，并通过视频通道更新 App 提示，支持在同一连接中接收授权与撤销状态

### 验证说明

- 自动化测试、协议拆包测试及 Android/iOS 本地构建已通过；新版首次授权、撤销授权和手机实际鼠标键盘操作仍需实机验证
- 升级会更新被控端程序；macOS 可能需要为新的程序身份重新授权一次。旧进程卡在已授权状态时，可重启屏幕服务后重新连接

## [1.8.0] - 2026-10-04

### 变更

- **Rust 远程服务**：远程画面服务与 Agent Bridge 改为原生可执行程序，内置 macOS / Linux 的 ARM64 与 x86_64 版本，继续支持从手机经 SSH 自动安装和更新，无需在电脑手动部署 Python
- **Agent 会话存储**：新会话改用私有 JSON 文件；旧 Python Bridge 的会话记录不迁移，新版不会展示这些旧记录，Agent 工具自身的原生历史仍可导入
- **构建与发布**：移除 Python 源码、`.pyz` 资源及 Python 构建依赖，远程服务由 Rust 工具打包，R2 发布改用 Node.js 与 curl

### 修复

- **远程画面生命周期**：补齐连接鉴权、会话占用、断开回收、按键释放与编码器重启处理；Wayland 授权取消时及时关闭请求和会话，桌面录屏与控制仍需系统授权
- **Agent 连接与安装**：保留后台任务、断线事件重放和审批重连，限制安装日志与等待时间，完善进程清理、附件路径校验和敏感信息脱敏

## [1.7.3] - 2026-09-06

### 优化

- **项目介绍**：重写中英文 README，突出原生 Agent 聊天、SSH / Mosh 与 SFTP，前置下载入口和三步上手流程
- **核心截图**：精选 Agent 对话、远程终端与文件管理三组演示截图，减少重复展示
- **开发文档**：将完整构建与测试步骤集中到贡献指南，明确 Android / iOS 获取方式、后台连接限制及外部服务配置要求

## [1.7.2] - 2026-09-06

### 修复

- **跨会话发送**：发送准备、附件进度、确认响应与错误绑定原会话，切换聊天后不会把消息或错误写入新页面
- **停止任务**：停止操作取消对应会话的发送准备或远端任务；首次发送取消后清理空会话，发送前失败的附件按本轮路径尝试回收
- **后台任务状态**：持续跟踪其他会话的运行与审批状态，补发并去重后台事件，避免离开聊天页后提前回收监听连接
- **Agent 安装状态**：各工具独立跟踪安装进度，防止重复安装，支持失败重试与重连后的状态恢复；内置 Bridge 更新至 0.8.1

### 变更

- **开源文档**：完善 MIT 授权说明、贡献指南、行为准则与 Issue/PR 模板，中英文说明与官网提供源码及贡献入口
- **发布配置**：Android 签名与 R2 发布使用仓库级 Actions 配置，支持从本地 `.env` 批量导入独立密钥
- **仓库整理**：移除项目内 `.agents` 辅助目录、内部评估与一次性检查报告

## [1.7.1] - 2026-09-05

### 移除

- **桌面客户端**：移除 macOS / Linux / Windows 应用与安装包，仅保留 Android 和 iOS；原 JVM 单元测试与 SSH/SFTP 集成测试迁入 Android 本地测试，远程画面功能继续保留

### 修复

- **终端顶部布局**：移除 header 下方重复的状态栏安全区留白，页头由 56dp 收紧至 48dp，保留 iOS 灵动岛必要的安全距离
- **移动端构建兼容性**：SSH 命令输出使用兼容 Android 8+ 的字符集 API；修复 iOS 视频桥接配置、平台扩展导入与只读属性实现，以及通知权限回调的可空处理；Markdown 渲染库对齐 Compose 1.8，修复 iOS 最终应用链接缺失符号

## [1.7.0] - 2026-08-25

### 新增

- **原生 Agent 对话**：在主机卡片直接进入 Codex、Claude Code、Gemini CLI、OpenCode 与 Pi 的手机原生工作区；内置纯标准库 Python Bridge 经 SFTP 自动部署，通过 SSH 内的私有 Unix socket 流式传输对话、思考与工具事件，并支持历史会话恢复、取消和 Agent CLI 引导安装
- **Agent 产物卡片**：对话会将本轮生成的 Markdown、PPT、Excel、Word、PDF 和图片等文件展示为可打开所在目录的卡片
- **Agent 原生历史导入**：可发现并导入远端 Codex、Claude Code、Gemini CLI、OpenCode 与 Pi 的本地会话，复用原生 session ID 继续上下文且不改写 Agent 自己的历史文件
- **Agent 供应商配置**：设置页可添加 DeepSeek（API Key 仅存平台安全存储），每个 Agent 可独立选择内置登录或指定供应商；首批适配 Claude Code、OpenCode 与 Pi
- **Agent 输入效率**：输入区支持手机附件、远端目录选择，以及输入 `/` 唤起当前 Agent 的内置命令补全；长附件名限制预览宽度并以省略号展示
- **Linux 屏幕远控**：支持 X11/XTEST 与原生 Wayland Portal/PipeWire 两套链路，自动识别桌面环境并可切换；补齐动态桌面尺寸、状态上报和鼠标注入
- **iOS 远程画面硬件解码**：接入 VideoToolbox 解码 H.264，降低移动端播放开销

### 优化

- **远程画面自适应传输**：根据 TCP 回压动态调整码率与帧率，静止画面发送保活帧，弱网和桌面静止场景下连接更稳定
- **主机卡片快速进入**：可从主机卡片直接进入 Agent 和远程画面，并复用已有 SSH 会话减少重复连接

### 修复

- **Agent 失败不再吞消息或永久 loading**：发送失败保留草稿与附件，登录/供应商错误直接显示；Bridge adapter 退出或异常时保证结束 turn，断连后不再幽灵重连
- **保留 Agent 自身审批策略**：不再强制覆盖 Codex、Claude Code 与 Gemini CLI 的权限/审批参数；当前尚未桥接手机交互审批，无头协议无法上报时安全失败并显示错误
- **虚拟鼠标左右推对称**：拖到左缘也可持续推动放大画面，鼠标热点钳制在可见视频区域内，不再滑出屏幕
- **虚拟鼠标拖动与坐标换算**：修复按住左键无法持续拖动、分辨率或横竖屏变化后点击偏移，以及虚拟鼠标与手指模式映射不一致的问题
- **远程画面断流恢复**：完善推流重连、会话切换和静态桌面超时处理，避免画面停住后误判断线
- **Agent 过程卡片**：恢复最初的卡片样式，思考与工具过程无论运行中或完成后都默认收起，并压低折叠态高度
- **Agent 消息顺序与并发保护**：发送请求交接期间暂存实时事件，避免助手增量早于用户消息或终态动画状态回写；Bridge 按会话串行化启动窗口，阻止多客户端同时覆盖同一 turn

## [1.6.4] - 2026-08-24

### 修复

- **小窗↔全屏切换黑屏**：surface 变化一律释放解码器、用客户端缓存的 SPS/PPS 立即重建（不依赖远端在关键帧重复参数集），重建后只喂 IDR 起播；展开/收起动画期间不上真实视频面（黑底占位，动画结束才建面）——SurfaceView 表面不跟随 graphicsLayer 缩放，画面不再卡成小窗尺寸亮点或黑屏
- **虚拟鼠标拖到右缘卡住/画面缩小**：拖过右缘的过冲量持久累积为视频整体左移（推量上限 = 把画面右缘推到面板左缘所需的平移量，放大后自动变大——鼠标贴近右缘时画面持续平移，永不滑出屏幕）；视频容器去祖先裁剪（部分设备上被祖先 clip 的 SurfaceView 表面会被缩放而非裁剪）
- **虚拟鼠标箭头到不了画面边缘**：箭头尖端精确落在热点（点击位置），尾巴超出画布由裁剪收起——此前图形整体内收，点不到画面最右列

## [1.6.3] - 2026-08-23

### 修复

- **设置页诊断开关即时刷新**：开关点击后立即生效（此前 TermLog 属性非 Compose state，点击后视觉不变，退出重进才更新）
- **git 面板 herdr workdir 打点**：herdr snapshot 空会话/解析失败路径补日志，便于定位

## [1.6.2] - 2026-08-23

### 新增

- **全屏帧率/画质切换**：右上角档位菜单（30/60/120 fps × 流畅/标清/高清），relay 支持动态参数（每连接读取 ~/.termish-screen.conf），切换后自动重建会话生效
- **relay 版本协商**（RELAY_VERSION）：客户端检测远端 relay 版本，旧版引导一键升级——客户端与远端脚本版本匹配（不再出现新客户端配旧 relay 跑不起新功能）
- **断流自动重连**：非主动断流 3 秒后自动重建会话（保留档位设置），无需手动重连

### 修复

- 档位切换回调漏传（全屏覆盖层未接 onStreamConfigChange）——帧率菜单点后右上角不更新、远端不生效；画质因本地 state 更新而看似可用
- 切档位与断流自动重连竞态：旧 uiState 档位未同步，自动重连把新档位覆盖回 30
- 小窗双指缩放改质心锚定（朝手指方向展开）+ 边界感知钳制（一边到头另一边继续撑开，不超出屏幕）
- coerceIn(min>max) 边界崩溃（小窗放大到接近画布大小时滑动可能崩溃）
- 工具栏键恢复正方形（宽屏 48dp 封顶居中，不再撑成扁块）
- 文档：ktlint 工作流规范 + 仓库公开状态表述更新

## [1.6.1] - 2026-08-23

### 修复

- **Linux 桌面推流（x11grab）全链路**：安装脚本 pkill 自杀（命令行含自身文本被全匹配误杀）改 PID 文件清理；DISPLAY 从 Xwayland 进程参数探测（Wayland 会话 display 号非 0）；X 检测兼容任意 X socket（Xwayland）；relay 错误日志路径平台化（Linux 无 ~/Library/Logs，open() 异常导致 ffmpeg 不被拉起——端口监听但 0 字节）；ffmpeg 缺失时免密 sudo 自动 apt 安装
- 语音输入：音量由录音线程直写消除主线程调度延迟（停顿 <2s 被误判静音自动发送）；静音自动发送阈值 2s→3s；正常发送不再弹 toast（仅错误/超时/误触提示）；连接失败提示清洗（不再透出 URL/端口）、超时 10s→5s
- 全屏 header 返回按钮与终端页 tab 栏对齐（状态栏高度留白，不再贴顶）
- mosh 引导失败一律进安装卡片（显示具体原因），不再静默降级无安装入口
- herdr 探测跳过 snap 版（沙箱受限与工作台不兼容）
- SFTP 选主机列表显示主机名（IP 并入详情行）

## [1.6.0] - 2026-08-22

### 新增

- 远程画面小窗支持**双指捏合/张开缩放**（单指拖动移动、右下角把手缩放、点击全屏统一由手势层处理）
- 全屏改为**真全屏**：覆盖 tab 栏与状态栏区域，Android 沉浸式隐藏状态栏；顶部元素避让状态栏（双保险）
- 全屏返回按钮改为标准播放器风格（纯箭头，与全应用 `KeyboardArrowLeft` 统一），去掉「收起」文字
- **息屏/锁屏处理**：relay 看门狗感知屏幕电源状态（`CGDisplayIsAsleep`），息屏时不 kill ffmpeg、不断连，唤醒后画面自动恢复；客户端显示明确提示（「Mac 屏幕已关闭，唤醒后自动恢复」/「锁屏状态，画面为锁屏界面」）而非笼统的「连接不上」

### 修复

- 横屏下终端键盘工具栏错位：`weight + aspectRatio` 在宽屏下产生超高键、文字截断（aspectRatio 无视高度约束），改为固定键高 48dp
- Tab 返回历史：终端 tab 之间切换不再压栈（返回直接回主页）；补上 SFTP 从终端页进入的返回链
- SFTP 选主机面板关闭叉号在 Dialog 中不跟随主题（LocalContentColor 平台默认黑色），显式指定 `onSurface`

### 工程

- 接入 **ktlint**（mavenCentral CLI + JavaExec 任务，不依赖插件门户）：`make lint-kt` / `./gradlew ktlintCheck`；CI checks 阶段加入；存量 175 文件格式化（import 排序/换行风格等），Compose 函数名与协议层常量按规则豁免，3 个文件重命名对齐类名

## [1.5.1] - 2026-08-22

### 修复

- 推流服务装过仍提示安装：SSH 非交互会话 PATH 受限，`command -v ffmpeg` 漏掉 brew
  安装的 ffmpeg → 误报缺失；查找路径扩展为显式探测 `/opt/homebrew/bin`、
  `/usr/local/bin`、`~/bin`（读流检测与安装脚本一致）
- 安装脚本 brew 分支装完重新定位 ffmpeg 真实路径（relay 不再拉起不存在的二进制）
- 缺 ffmpeg（FFMPEG_MISSING）与服务未运行统一进安装引导卡片
- 安装引导卡片不再叠加通用错误态：红色错误文案 + 「重新连接」按钮与「安装」
  操作重复；卡片按具体原因显示文案，安装失败保留日志可重试

## [1.5.0] - 2026-08-22

### 新增

- **小窗双指缩放**：远程画面小窗支持双指捏合/张开调整窗口大小（单指拖动移动、右下角把手缩放、点击全屏统一由手势层处理）

### 修复

- 小窗全屏后按返回直接跳主页：BackHandler 后注册者优先，全屏收起处理器须晚于 onBack 注册
- 全屏返回按钮被视频面/错误态覆盖看不到点不到：按钮移到最顶层，改为毛玻璃「← 收起/返回」，文案入 AppStrings 双语
- 小窗位置/尺寸在全屏往返后重置：状态提升到会话 key 块（组件内 remember 随全屏切换销毁）
- 小窗可拖出屏幕：钳制范围按初始右上角位置修正（旧对称范围向右/上越界）
- 小窗缩放把手拖不动：事件沿 hit path 父→子分发，把手独立手势被父节点抢走，改为统一手势按起点判定
- 小窗贴屏幕右边缘时水平缩放被系统返回手势抢走：把手区域排除系统返回手势（跨平台 expect/actual）
- 双指捏合时误触全屏：视频面 clickable 先于手势收到事件、位移小于 touch slop 判为点击，改由统一手势判定点击

## [1.4.0] - 2026-08-22

### 新增

- **远程画面（macOS 屏幕推流）**：终端 + 菜单「远程画面」——SSH 通道实时观看 Mac 屏幕
  - Mac 端一键安装常驻推流服务（LaunchAgent 跑在 GUI 域：avfoundation 抓屏受 TCC 限制，SSH 后台会话无法录屏，服务化绕行），服务缺失自动引导安装（流式日志）
  - 手机端 ExoPlayer + 本地 HTTP 流（MPEG-TS）播放：硬件解码、Fit 缩放、帧率/分辨率角标；替换手写 MediaCodec 管线（OPPO/MTK 真机实测吞输入不出帧）
  - 终端页小窗（画中画：拖动/缩放/关闭）+ 就地全屏，断线重连、同主机多会话互踢
- **终端 tab 返回历史**：返回键优先回到上一个 tab（SFTP/屏幕 → 终端），栈空才回首页

### 修复

- 推流服务 relay 断线/重连处理：BSD nc 的 FIN 半关闭不再误判为断连（预热期 ffmpeg 被误杀）；新连接直接 SIGKILL 旧 ffmpeg + 首帧 45s/中途 20s 无数据自愈，修僵尸进程堆积占死抓屏设备；安装结果按 TERMISH_SCREEN_OK 判定
- H264Stream Annex-B 解析器 start code 长度判定边界（3 字节 SC + NAL 头 0x01 不再误判）

## [1.3.0] - 2026-08-22

### 新增

- **语音输入（可插拔 ASR）**：屏幕水平居中常驻麦克风按钮，点一下即说——实时转写上屏 + 声波 + 计时，静音约 2s 自动发送；按钮可拖动、长按或角标重置回中央
  - 识别服务 Provider 化：设置页可添加/编辑/删除多个服务（火山引擎流式识别为首个），Key 存平台安全存储，旧配置自动迁移
  - 未配置时点击提示 + 空态引导
- **Markdown 预览**：SFTP 文件管理内 md 文件渲染展示（标题/代码块/行内样式/列表/引用，零依赖纯 Kotlin 渲染器），预览 ⇄ 源码一键切换
- **终端工具菜单**：右下角 + 展开——上传文件（当前目录//tmp，传完自动把远端路径输入终端）、文件管理（定位到终端工作目录）、收藏夹、Git
- **SFTP 文件管理器增强**：多选批量（下载/删除/复制路径）、删除（目录递归）与重命名（平台层 delete/rename，sshj+libssh2）、下拉刷新、目录收藏（按主机持久化）、日期分组、空态、20 类彩色文件图标
- **快捷命令空态可新增**：终端 {} 面板无命令时可直接新建（名称+内容弹窗）
- **全局品牌提示**：深色圆角 Snackbar + 翠绿操作，终端/文件管理/全局统一；进度控件统一（TransferProgressCard）

### 修复

- 语音静音自动结束过于敏感（阈值 0.05→0.02、窗口 1.6s→2.0s，轻声不误切）
- SFTP 预览层遮挡下载进度与完成提示（zIndex + 去重 SnackbarHost）
- 语音/片段对话框配色未跟随终端主题
- 语音识别协议按实测修正（顶层 result、帧 flags 最终包、首包无 sequence）


## [1.2.4] - 2026-08-22

### 修复

- **SFTP 预览面板打开时下载提示被遮挡**：二进制文件（apk 等）进预览后，全屏
  覆盖层盖住了下载进度卡与完成提示。删除被盖的重复 SnackbarHost、进度卡
  zIndex 提升至预览层之上

## [1.2.3] - 2026-08-22

### 修复

- **语音按钮位置**：水平居中 + 垂直在底部（工具栏上方），不再遮挡终端中部输出
  （1.2.2 误放垂直正中）

## [1.2.2] - 2026-08-22

### 新增

- **语音按钮移至屏幕正中**：待机常驻 64dp 品牌绿麦克风按钮，点一下即开始录音
  （免去右下角菜单两步）；录音/识别中原位切换红按钮 + 转写浮层，整组可拖动

## [1.2.1] - 2026-08-22

### 修复

- **语音输入静音自动结束过于敏感**：轻声说话（RMS ~1000）被旧阈值 0.05 误判为静音，
  停顿 1.6s 即切断。阈值降至 0.02、静音窗口延至 2.4s，容忍句中 1-2s 停顿

## [1.2.0] - 2026-08-22

### 新增

- **语音输入**：终端右下角功能菜单（+ 展开：语音 / 上传文件 / 文件管理 / Git）
  - 点击语音开始录音：屏幕中央 72dp 大按钮（可拖动）+ 实时转写文字上屏 + 流动声波 + 录音计时
  - 静音 ~1.6s 自动结束发送；60s 上限；误触（<300ms）丢弃
  - 火山引擎「大模型流式语音识别」接入（bigmodel_async WebSocket，按实测协议修正响应解析）
- **语音识别服务 Provider 化**：设置页可添加/编辑/删除多个识别服务（名称/API Key/资源 ID，密钥存平台安全存储），旧配置自动迁移；新增服务类型只需实现 AsrEngine 接口
- **终端文件上传**：菜单选择目标目录（当前目录 / 临时目录，卡片式选项）→ 系统文件选择器多选 → SFTP 流式上传 + 右下角进度卡片；上传完成自动把远端路径输入终端
- **文件管理**：终端菜单一键打开当前主机的 SFTP 文件管理，并定位到终端当前工作目录
- **SFTP 文件管理器全面优化**：
  - 多选模式（长按进入：批量下载 / 删除 / 复制路径）
  - 删除（目录递归）与重命名（平台层新增 SftpSession.delete/rename，sshj + libssh2 双实现）
  - 单击文件直接预览；预览面板操作菜单（下载 / 重命名 / 删除 / 复制路径）
  - 下拉刷新、空态、目录收藏、日期排序按「今天 / 本周 / 更早」分组
  - 文件类型图标扩展至 20 类（彩色线性图标，含 APK / 证书密钥 / Markdown / 种子 / 配置）
- **全局统一提示样式**：品牌 Snackbar（深色圆角卡片 + 翠绿 action），覆盖终端页 / 文件管理 / 全局提示

### 优化

- 录音态浮层与 Git/语音按钮统一为品牌翠绿；功能菜单 FAB 与 Git 面板入口合并
- 上传/下载进度控件统一（TransferProgressCard：右下角悬浮卡片）
- 文件夹图标回归主题色；文件图标加大到 24dp 并着色区分

### 修复

- 语音识别协议按实测修正：响应 result 为顶层对象、最终包由帧 flags 标记、首包无 sequence 段
- 设置页文案 AppStrings 拆分嵌套子类（避免 JVM 255 参数上限）

## [1.1.14] - 2026-08-21

### 修复

- **mosh 接管后 SSH 引导通道迟到输出污染画面**：云主机 PAM MOTD 脚本
  （landscape/ESM 检测）可耗时 1-3s，其输出在 mosh UDP 确认后才到达，
  迟到字节写进与 mosh 共用的 UI buffer，盖在 herdr TUI 下方且无后续
  mosh 帧覆盖（herdr 空闲不重绘），表现为「herdr 下方残留 Ubuntu 升级
  文案」。mosh UDP 首包确认时置 moshDisplayTakeover 门控，入队与消费
  两侧同时丢弃；重连时复位保证降级路径 SSH 输出正常进显示

## [1.1.13] - 2026-08-21

### 修复

- **iOS 中文全部渲染成 "kotlin.Unit"**：`codePointToString` 写作
  `StringBuilder().appendCodePoint(cp).toString()`，而 Kotlin/Native 的
  StringBuilder 无公开 `appendCodePoint` 成员，解析到本文件返回 `Unit` 的
  私有扩展 → 每个宽字符渲染成字面串 `kotlin.Unit`；JVM 恰被
  `java.lang.StringBuilder` 成员遮蔽而正确，掩盖了问题。改为手动码点
  转代理对的纯表达式实现，平台语义统一
- **CJK 长行输出冻屏**：宽字符头落 cols-2、尾落 cols-1 恰好填满行时
  `cursorCol` 推到 cols（越界），下一个字符访问 `cells[cursorCol]` 抛
  越界——输出消费协程死亡，终端静默冻屏且无任何诊断。钳到最后一格
  并挂起延迟换行（与窄字符行末语义对齐）
- **输出解析异常不再杀死会话**：消费循环的 `emulator.write` 包
  try/catch，单批字节解析异常只丢当前批次（经 `TermLog` 落诊断日志），
  会话保持响应——跳过一段输出最坏花屏，远好于永久失去响应

## [1.1.12] - 2026-08-21

### 修复

- **herdr/TUI 模式滚动无惯性**：`awaitEachGesture` 在手势块返回后立即重入
  （先调 block 再等 all-up），惯性取消语句放在 `awaitFirstDown` 之前，
  松手启动的惯性滚轮在同一帧被掐死，表现为「拖多少是多少」；
  移到等指之后，fling 恢复正常衰减滑行

## [1.1.11] - 2026-08-21

### 新增

- **SFTP 文本预览**（文件菜单 → 预览）：md/txt/log 等直接全屏等宽查看，
  只流式读取前 512 KB（大文件自动截断提示），前 4 KB 含 NUL 自动识别二进制，
  UTF-8 解码；行内可选文本 + 一键复制全部
- **SFTP 图片预览**：png/jpg/jpeg/gif/webp/bmp 点击直接黑底显示（Fit 适配），
  标题栏切换原始像素大小（可滚动看细节）；读取上限 8MB，超限明确提示；
  平台解码（Android BitmapFactory / iOS skia / 桌面 ImageIO）
- **SFTP 上传多选**：三平台选择器支持一次选多个文件并发上传
  （Android OpenMultipleDocuments / iOS allowsMultipleSelection /
  JFileChooser multiSelectionEnabled，各自独立流式读）
- **SFTP 文件类型 icon**：图片/视频/音频/压缩包/代码/文本/PDF 按扩展名
  区分图标（列表与搜索结果一致）

### 变更

- **终端 tab 标题优先显示主机自定义名称**；未起名时回退 `user@host`
- **同主机多会话 tab 编号**：按当前 tab 列表位置 1、2、3…（删除自动重排）
- **README 截图全部更新**（新 UI）+ mosh/herdr 引导安装截图（中英配对），
  文档同步引导安装说明（sudo 密码仅本次发送、可跳过降级 SSH）

## [1.1.10] - 2026-08-21

## [1.1.9] - 2026-08-20

### 变更

- **移除设置页「支持作者」打赏入口**：收款码为个人资产，不随 App/仓库
  公开（支付宝/微信收款码、docs/donate/ 一并移除）

## [1.1.8] - 2026-08-20

## [1.1.7] - 2026-08-20

### 变更

- **键盘工具栏展开不再闪烁**：▾ 展开 F 键/F 功能键行时改为覆盖画布底部
  而非压缩画布——不触发 PTY resize，herdr 等 TUI 不再整体重排闪屏；
  键位改正方形（随屏宽自适应，上限 48dp 触控目标）；展开箭头改用
  Material 图标，符号键（↑↓←→ ⌨ / ⎇）与图标字号对齐 14sp 档
- **「连接中」指示跨页统一**：SFTP 首连与重连共用终端页同款居中胶囊
  （此前首连是裸文本）；终端页指示器在「画布−工具栏」可见区域内居中，
  配色跟随终端主题（应用浅色主题下不再在深色画布上浮出亮色胶囊）

### 修复

- **SFTP 断开 banner 永不消失**：重连/换新会话时关闭旧连接会同步触发
  旧连接的 onClosed 回调，竞态把刚重连成功的会话误标回「已断开」。
  引入连接代次标识，旧代次回调直接忽略；主动断开/移除同样不再误标

## [1.1.6] - 2026-08-20

### 变更

- **herdr 从连接模式降为连接选项**：herdr 是远端应用而非传输协议，与
  SSH/Mosh 同层单选属分层错位。改为「连接后启动 herdr 工作台」勾选项
 （与传输层正交）：Mosh 下引导 `mosh-server new -- herdr` 直接跑 TUI；
  SSH / 降级路径改为连接后注入 herdr 命令（退出回 shell，不再断开整个
  会话）；删除专用 exec+pty 通道整套机制，连接编排净减 259 行

### 修复

- **新主机多次弹「确认服务器身份」**：连接成功后的系统探测用主机快照
  整条覆盖，把刚记录的 TOFU 指纹抹回空——此后每次重连/新开会话/SFTP
  都重复弹窗。改为基于仓库最新值的部分字段更新

## [1.1.5] - 2026-08-20

### 新增

- **终端工具栏重排 + 新键位**：ESC 左上、CTRL/ALT 左下（同实体键盘底行）；
  新增 ⇧⇥（Shift+Tab，TUI 菜单反选 / Claude Code 模式切换）与 ⌃\
  （长按 ⌃C/⌃D 触发，杀连 ⌃C 都不响应的顽固进程）；方向键按住连发
  （400ms 后 60ms/次）；ESC 长按双发；⌃E 换成 /，两行 8+8 等宽、↑↓ 对齐

### 修复

- **工具栏重排引入的两处回归**：方向键丢失 ESC 字节（发出 "[A" 而非
  "ESC[A"，打字面字符、光标不动）；尾随 lambda 误绑长按回调（普通键单击
  失灵、方向键无响应）
- **覆盖层系统返回穿透**：主机编辑片段页 / 片段管理二级页 / 终端片段面板
  打开时手势返回直接退首页，连带丢弃正在编辑的表单；现在先关最上层覆盖层
- **表单页软键盘遮挡**：主机编辑 / 片段编辑 / 标签管理页 imePadding 顶起
  被盖住的底部字段；标签输入支持 IME Done 提交（原来只有硬件回车生效）
## [1.1.4] - 2026-08-19

### 修复

- **Xcode Cloud 归档双重故障**：① Xcode 26 下 ad-hoc 归档（Xcode Cloud
  固定注入 `CODE_SIGN_IDENTITY=-`）与 Automatic 签名风格互斥，改为
  无签名归档 + 导出阶段重签；② 设备构建链接到模拟器切片的
  libssh2（`LIBRARY_SEARCH_PATHS` 中 sim 先于 device），改按 SDK
  条件分流；③ 删除无引用 entitlements（keychain 默认即隐式组，
  行为不变）；个人 Team ID 不进仓库（本地真机首次需重选团队）

## [1.1.3] - 2026-08-19

### 变更

- **仓库信息清理**：去源码注释与 AI 测试用例中的个人基础设施
  信息（部署机公网 IP、个人域名、内网 IP 占位化）；NOTICE 补全运行时
  依赖声明（Kotlin/Compose、kotlinx-*、multiplatform-settings、
  AndroidX、slf4j、MIT），新增 LICENSES/MIT.txt；回退误提交的个人
  Xcode 签名配置，保持 pbxproj 干净（Team ID 由 Xcode Cloud 注入）

## [1.1.2] - 2026-08-19

### 修复

- **Xcode Cloud Archive 签名配置**：仓库保留 `CODE_SIGNING_ALLOWED=YES`
  与空 `DEVELOPMENT_TEAM`，Team ID 改由 Xcode Cloud 环境变量注入，
  既满足云端归档签名，又避免把个人 Team ID 写进仓库

## [1.1.1] - 2026-08-19

### 修复

- **Xcode Cloud 构建失败**（PhaseScriptExecution failed）：云构建镜像不带
  Java 且 iosApp/native/ 为 git-ignored（OpenSSL/libssh2 缺失）——新增
  ci_post_clone.sh 自动装 Temurin 17 + 交叉编译原生依赖，script phase
  增加 JAVA_HOME 兑底解析；本地构建不受影响

## [1.1.0] - 2026-08-19

### 新增

- **iOS App Store 上架准备**：隐私清单（PrivacyInfo.xcprivacy）、
  中英双语 InfoPlist.strings、加密合规声明（标准协议豁免）、
  iPhone-only 目标机型；提审材料草稿见 docs/appstore/submission.md

### 修复

- **iOS 项目文件损坏**：双语 InfoPlist.strings 的构建条目 isa 误写为
  PBXVariantGroup，Xcode 解析项目即崩（unrecognized selector），
  CI/Xcode Cloud 干净检出无法打开工程
- **iOS 编译修复一批**：FilePicker 编译错误（NSObject import 错位 +
  协调读取回调误用）；libssh2 长度参数从字符数改为字节数（UTF-8 多字节
  路径下 SFTP 下载失败）；SFTP 上传补齐与断线防挂死；kbiHandler 泄漏；
  herdr 引导重复探测去重
- **CI 构建溯源**：main 分支测试包文件名带版本 + run 号 + 短 SHA，
  下载后可溯源是哪次构建

## [1.0.1] - 2026-08-19

### 修复

- **HERDR 模式进不了工作台**：sshd 非交互 exec 的 PATH 不含
  `~/.local/bin` 时，探测命中 `$HOME` 前缀候选，但下游 mosh 引导
  `-- '$HOME/...'` 的单引号不展开、mosh-server 直接 execvp 不过 shell，
  报 `execvp: $HOME/.local/bin/herdr: No such file or directory`。
  探测命中后先解析成绝对路径（`echo $HOME`）再贯穿引导/降级/重连全链路
- 终端顶部状态飘条（连接中/重连中/失联/错误）改直角，贴合全宽终端视觉

## [1.0.0] - 2026-08-17

首个正式版（versionCode 3）。此前所有构建均为内部测试，未公开分发；
发布前完成签名密钥重建（别名 `mssh` → `termish`），无历史升级兼容负担。

### 新增

- **纯 Kotlin mosh 客户端**（`dev.termish.mosh`）：自研 AES-128-OCB（L 表约定对齐协议规格，含独立标准测试向量）、SSP 状态同步（分片/重组/
  乱序恢复/ACK/throwaway/prospective resend）、zlib 分片压缩、RTT 估计
  （Jacobson/Karels）、UDP 直连与 15s 连通性超时。影子终端复用自研纯 Kotlin
  终端模拟器，渲染帧率上限 50fps，无需依赖原生 mosh-client 二进制。
- 本地回显预测（prediction overlay）：高 RTT 下按 mosh 触发阈值在确认态分叉上
  预测渲染，echo_ack 收编，打字即时性对齐原生 mosh-client
- Mosh 全端支持：SSH 引导启动 `mosh-server`、固定 UDP 端口（NAS/端口转发场景）、
  终端主题注入（`moshThemeSync`）让 herdr 等 TUI 拿到手机配色
- 会话管理：Connections tab 离开不断连、离开策略（保留/10 分钟/断开）、指数退避
  自动重连、Android 前台服务 + wakelock 后台保活、断网/网络切换恢复
- 多会话支持：同主机多会话 tab、终端页 tab 栏、会话状态统计卡片
- SFTP：连接覆盖层 + 文件浏览 tab、上传、流式下载、**递归目录下载**、面包屑
  导航（两级折叠 + 返回=历史回退）、通配符/多关键词搜索、切 tab 保持路径/列表
  状态；iOS 二进制字节流上传（cinterop 绕过 String 映射）、无 longentry 服务器
  stat 兜底目录识别
- 加密私钥支持（PKCS#8 / legacy PEM / OpenSSH，连接时询问口令，不落盘）
- 自动识别远端系统（Termius 式）：连接后 exec 探测 os-release/uname，无需手填
- macOS 风格终端页头（交通灯圆点，红点=返回）、状态栏图标主题感知
- 认证/主机密钥确认弹窗全局化（首页连接等待时直接弹出）
- 设置页、主机编辑页整行可点单选（无障碍 Role.RadioButton）
- 工程化：根目录 Gradle 工作流任务（testIntegration/runDebug/reinstallDebug）、
  Makefile 薄壳、签名机密 `.env` 化（CI 零文件注入）、集成测试自我探测

### 终端模拟器

- VT100/xterm 序列、UTF-8、CJK 宽字符全链路 2 格、alt screen、scrollback
- 行级 COW 与 `(identity, version)` 增量同步、`shallowFork` 分叉（渲染性能关键）
- 真彩/256/ANSI-16、OSC 8/10/11/12/52、bracketed paste、DECSCUSR、鼠标报告
  （X10/SGR/urxvt）、焦点事件、alternate scroll
- 行级文本布局缓存（LRU 256 行，滚动整屏平移全命中）
- CJK IME 一等公民：组合态不上线、提交才 diff 发送、候选栏正常

### 修复

- SSH 输出消费串行到主线程：reader 只向有界队列（256 chunk，满则 TCP 背压）
  投递，主线程单点喂 emulator 并按 8ms 帧预算批量合并重绘——终端缓冲全部读写
  限定在主线程，消除与 Canvas 绘制、resize 重建行数组之间的竞争
- TerminalController 协程治理：移除会话时销毁控制器回收延迟任务；不吞
  CancellationException；mosh 异常退出重连纳入 reconnectJob 统一管理
- 认证/主机密钥弹窗 120s 超时：页面销毁/无人应答按拒绝处理，连接线程不永久阻塞
- mosh 语义对齐：已连接后永不主动超时退出（漫游）、重绘失效（打字延迟/滚动跳变
  根因）、shallowFork 幻影行累积、COW 卡顿、状态点与失联横幅同源
- 保活与重连：续期计时改绝对时刻防空洞、网络监听区分断开/传输切换、免疫期/防抖
  拆分并用单调钟、mosh「连上即退」稳定期重置、重连失败停保活防空转
- 终端 resize 崩溃（TerminalLine 行列不同步越界）、点击坐标漂移、OSC 8 点击、
  折行复制
- iOS：SSH 线程安全与资源泄漏、连接竞态、Keychain 日志元数据
- iOS/桌面连接超时：tcpConnect 改非阻塞 connect + poll，应用 15s
  connectTimeoutMillis（此前 iOS 对黑洞地址要等内核 TCP 重试 60s+，UI 一直
  转圈）；错误提示从模糊的 errno 60 变为「连接超时（15s 无响应）」
- 首页卡片状态三态显示：连接中（CONNECTING/AUTH）不再计入「已连接」——
  统计与条目标签区分 已连接绿 / 连接中橙 / 已断开灰
- 主机配置变更后卡片点击不复用旧会话：凭据签名（credentialKey）比对，
  不匹配则用当前配置新建（旧会话保留在连接页可手动关闭）
- CI workflow 修复：job 级 if 不可用 matrix context 导致 workflow 解析失败、
  Ubuntu sshd 需 /run/sshd 目录、Windows WiX 改官方二进制下载（choco 不稳）
- 发版链路：GitHub Release 自动汇总三平台产物并附 SHA-256 校验和

### 安全

- Android Keystore (AES-GCM) / iOS Keychain 密钥存储；密码/私钥不落盘明文
- TOFU 主机密钥校验：首次确认、指纹变更弹窗核对新旧指纹（有重置入口）；
  点信任即记录指纹，认证失败不再重复弹授信窗
- 签名密钥重建：keystore 别名从历史遗留 `mssh` 清理为 `termish`（CN=Termish，
  4096-bit RSA，有效期 10000 天）；旧 keystore 归档于
  `~/Documents/秘钥/termish-mssh-legacy-20260817.jks`
- 无遥测、无分析、除 SSH 连接外无网络请求

### 移除

- 原生 mosh-client 路径（so 模式）：`USE_KMP_MOSH` 回退开关、Android JNI PTY 桥、
  iOS `mosh-client` 二进制、desktop pty4j、相关构建脚本与 CI 步骤、GPL 许可声明。
  原生实现完整快照保留在 `native-mosh` tag

### 文档

- README 双语化（英文权威 + 中文全文镜像）；docs/ 深水文档下沉：
  architecture / terminal-emulator / mosh / input-pipeline
- crypto/README：明确记录 mosh AES-128-OCB 自研实现的生产链路例外及威胁模型
- README 首页下载入口（Android/桌面/iOS 三渠道按钮）+ 双语截图体系：
  每语言 6 张 3×2（Android 深色 5 页面 + iOS），全黑色主题实拍；
  官网 Screens 同步（英文 6 张 / 中文 9 张 3×3）

## [0.2.0] - 2026-08-15

内部测试构建，未公开分发。

### 新增

- 纯 Kotlin 终端模拟器 + Compose Multiplatform UI（Android/iOS/Desktop）
- 传输层：JVM sshj + BouncyCastle，iOS libssh2 + 静态 OpenSSL
- 主机管理：搜索、标签、快捷命令、密码/私钥/keyboard-interactive 认证
- 双行功能键工具栏（F1-F12、方向键、sticky CTRL/ALT）
- 设计系统：zinc 中性色 + emerald 强调色，内置 JetBrains Mono

[Unreleased]: https://github.com/farlume/termish/compare/v1.8.6...HEAD
[1.8.6]: https://github.com/farlume/termish/compare/v1.8.5...v1.8.6
[1.8.5]: https://github.com/farlume/termish/compare/v1.8.4...v1.8.5
[1.8.4]: https://github.com/farlume/termish/compare/v1.8.3...v1.8.4
[1.8.3]: https://github.com/farlume/termish/compare/v1.8.2...v1.8.3
[1.8.2]: https://github.com/farlume/termish/compare/v1.8.1...v1.8.2
[1.8.1]: https://github.com/farlume/termish/compare/v1.8.0...v1.8.1
[1.8.0]: https://github.com/farlume/termish/compare/v1.7.3...v1.8.0
[1.7.3]: https://github.com/farlume/termish/compare/v1.7.2...v1.7.3
[1.7.2]: https://github.com/farlume/termish/compare/v1.7.1...v1.7.2
[1.7.1]: https://github.com/farlume/termish/compare/v1.7.0...v1.7.1
[1.7.0]: https://github.com/farlume/termish/compare/v1.6.4...v1.7.0
[1.6.4]: https://github.com/farlume/termish/compare/v1.6.3...v1.6.4
[1.6.3]: https://github.com/farlume/termish/compare/v1.6.2...v1.6.3
[1.6.2]: https://github.com/farlume/termish/compare/v1.6.1...v1.6.2
[1.6.1]: https://github.com/farlume/termish/compare/v1.6.0...v1.6.1
[1.6.0]: https://github.com/farlume/termish/compare/v1.5.1...v1.6.0
[1.5.1]: https://github.com/farlume/termish/compare/v1.5.0...v1.5.1
[1.5.0]: https://github.com/farlume/termish/compare/v1.4.0...v1.5.0
[1.4.0]: https://github.com/farlume/termish/compare/v1.3.0...v1.4.0
[1.3.0]: https://github.com/farlume/termish/compare/v1.2.4...v1.3.0
[1.2.4]: https://github.com/farlume/termish/compare/v1.2.3...v1.2.4
[1.2.3]: https://github.com/farlume/termish/compare/v1.2.2...v1.2.3
[1.2.2]: https://github.com/farlume/termish/compare/v1.2.1...v1.2.2
[1.2.1]: https://github.com/farlume/termish/compare/v1.2.0...v1.2.1
[1.2.0]: https://github.com/farlume/termish/compare/v1.1.14...v1.2.0
[1.1.14]: https://github.com/farlume/termish/compare/v1.1.13...v1.1.14
[1.1.13]: https://github.com/farlume/termish/compare/v1.1.12...v1.1.13
[1.1.12]: https://github.com/farlume/termish/compare/v1.1.11...v1.1.12
[1.1.11]: https://github.com/farlume/termish/compare/v1.1.10...v1.1.11
[1.1.10]: https://github.com/farlume/termish/compare/v1.1.9...v1.1.10
[1.1.9]: https://github.com/farlume/termish/compare/v1.1.8...v1.1.9
[1.1.8]: https://github.com/farlume/termish/compare/v1.1.7...v1.1.8
[1.1.7]: https://github.com/farlume/termish/compare/v1.1.6...v1.1.7
[1.1.6]: https://github.com/farlume/termish/compare/v1.1.5...v1.1.6
[1.1.5]: https://github.com/farlume/termish/compare/v1.1.4...v1.1.5
[1.1.4]: https://github.com/farlume/termish/compare/v1.1.3...v1.1.4
[1.1.3]: https://github.com/farlume/termish/compare/v1.1.2...v1.1.3
[1.1.2]: https://github.com/farlume/termish/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/farlume/termish/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/farlume/termish/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/farlume/termish/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/farlume/termish/compare/v0.2.0...v1.0.0
[0.2.0]: https://github.com/farlume/termish/releases/tag/v0.2.0
