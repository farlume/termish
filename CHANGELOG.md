# Changelog

格式遵循 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)，
版本号遵循 [语义化版本](https://semver.org/lang/zh-CN/)。

## [Unreleased]

## [1.1.3] - 2026-08-19

### 变更

- **开源仓库卫生清理**：去源码注释与 AI 测试用例中的个人基础设施
  信息（部署机公网 IP、个人域名、内网 IP 占位化）；NOTICE 补全运行时
  依赖声明（Kotlin/Compose、kotlinx-*、multiplatform-settings、
  AndroidX、slf4j、MIT），新增 LICENSES/MIT.txt；回退误提交的个人
  Xcode 签名配置，保持 pbxproj 干净（Team ID 由 Xcode Cloud 注入）

## [1.1.2] - 2026-08-19

### 修复

- **Xcode Cloud Archive 签名配置**：仓库保留 `CODE_SIGNING_ALLOWED=YES`
  与空 `DEVELOPMENT_TEAM`，Team ID 改由 Xcode Cloud 环境变量注入，
  既满足云端归档签名，又避免把个人 Team ID 写进开源仓库

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
  终端模拟器，渲染帧率上限 50fps。解决原生 mosh-client（GPLv3）与项目 MIT
  许可的冲突。
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

[Unreleased]: https://github.com/ttermish/termish/compare/v1.1.2...HEAD
[1.1.2]: https://github.com/ttermish/termish/compare/v1.1.1...v1.1.2
[1.1.1]: https://github.com/ttermish/termish/compare/v1.1.0...v1.1.1
[1.1.0]: https://github.com/ttermish/termish/compare/v1.0.1...v1.1.0
[1.0.1]: https://github.com/ttermish/termish/compare/v1.0.0...v1.0.1
[1.0.0]: https://github.com/ttermish/termish/compare/v0.2.0...v1.0.0
[0.2.0]: https://github.com/ttermish/termish/releases/tag/v0.2.0
