# 远程画面服务

> **English summary:** the phone installs precompiled Rust companions over SSH/SFTP for macOS and Linux (X11/Wayland), on arm64 and x86_64. Rust owns authenticated TCP video, native input, portal consent and encoder lifecycle; no remote compiler or interpreter is required. macOS provides a menu bar; Linux provides a tray menu and management window, hardware encoder probing with software fallback, and graphical-session lock-state hints.

## 手机安装与依赖

手机沿用 SSH 登录，探测系统、架构和图形会话，通过 SFTP 分块上传到私有临时文件。
安装器校验 SHA-256、可执行性与部署版本后，安装或复用 FFmpeg，生成或复用 token，
由 Rust 校验配置并原子启用可执行文件，再注册图形会话服务和验证回环端口。
不支持或未打包的架构会返回明确错误。
原生服务启动验证成功后，安装器清理旧 `screen-relay.py`、`screen_service_config.py`
及对应字节码缓存；保留配置、token、日志和其他文件，不跟随缓存目录的符号链接。

macOS 后台应用名为 **Termish Helper**，复用 Termish Logo；`LSUIElement` 隐藏 Dock 图标，
无需打开应用窗口。Rust 可执行文件位于完整 `.app` 包中，名称同样为 Termish Helper。
图形登录会话中默认显示菜单栏图标，菜单按系统语言提供中文或英文。
手机上传整包，经 SHA-256、应用签名和部署版本校验后启用，名称/图标资源属于签名覆盖范围。
macOS 用 FFmpeg avfoundation/VideoToolbox，X11 用 x11grab 和 XTEST，文本粘贴依赖 xclip。
Linux 默认先用合成色块探测 NVENC、QSV、VAAPI；FFmpeg 列出编码器不代表驱动能初始化。
探测有总时限，断开连接可取消；驱动或设备不可用时回退 libx264。实际推流期间硬件编码器退出
或长时间无输出，同一 TCP 会话内切回软件编码并保留帧序号；软件采集本身失败仍会结束连接。
系统需有对应显卡驱动及设备访问权限，服务不强制安装或更换驱动。
Wayland 用 Rust D-Bus RemoteDesktop/ScreenCast Portal 同一授权会话获取画面和输入权限，
将授权后的 PipeWire FD 交给 GStreamer，视频管道输入 FFmpeg 编码；无需 Xwayland 抓根窗口。
Portal 响应订阅先于请求，避免快速授权响应丢失；坐标使用授权流的逻辑尺寸。

macOS 屏幕录制、辅助功能与 Wayland Portal 均需系统授权。新的可执行文件身份可能需要重新授权。
服务运行在图形登录会话中，锁屏、休眠、注销及操作系统安全限制仍然适用。
macOS 控制权限使用 CoreGraphics 的事件发送权限检测，后台服务处理主线程系统事件。
缺控制权限时每个服务进程只请求一次，在同一进程内重连和连续操作不会重复请求辅助功能授权；
控制权限变化每秒检查，并通过视频通道同步到手机。若检测结果被进程缓存，需重启服务刷新。
录屏权限使用 `CGPreflightScreenCaptureAccess` 单独检查：鉴权后、启动/重启 FFmpeg 前
以及抓屏期间每秒检查。拒绝时不启动编码器，返回独立录屏状态，手机停止自动重连。
服务仅在首次被拒绝的已鉴权连接上请求一次录屏授权；授权后由用户重新连接。
macOS 的进程内权限检测可能保留授权前的结果。手机收到录屏或辅助功能权限缺失后，
用户点击「重新连接」会先重启当前用户的 `dev.termish.screen` LaunchAgent，等待服务端口恢复再建立画面。
断流自动重连、画质切换和正常连接不重启服务；此操作不修改 TCC 授权记录。
录屏与辅助功能是两种授权，开启其中一种不会自动获得另一种。
若旧版本已经卡在“已授权但不能控制”，重启 `dev.termish.screen` LaunchAgent 后再连接。
若更新后录屏已勾选但仍被拒绝，应在录屏列表中删除旧 `screen-service` 或失效的 Termish Helper 条目，重新添加
`~/Library/Application Support/termish/Termish Helper.app` 并重启服务。从裸程序迁移到应用包可能需首次重新授权。
当前未配置发行证书的 Mac payload 使用
ad hoc 签名，代码摘要随构建改变；权限预检和提示限流不会让旧签名授权自动适用于新文件。
跨版本保留授权需要同一受信任证书与稳定标识的正式代码签名；不能以放宽代码要求绕过校验。
参考 [Apple DTS 对 ad hoc 录屏授权的说明](https://developer.apple.com/forums/thread/819406)。

## 构建

`rust/src/service.rs` 管理连接与编码器，`protocol.rs` 处理 THA1/THC1/THF1/THV2，
`platform/` 实现各桌面平台。`service.properties` 是部署版本、默认端口的唯一来源。
`tools/service-build/` 是共用 Rust 构建工具，生成 payload 清单、Kotlin 安装脚本和 Compose 资源。

Mac 构建机安装 Xcode SDK、rustup 和四种目标：

```bash
rustup target add aarch64-apple-darwin x86_64-apple-darwin \
  aarch64-unknown-linux-musl x86_64-unknown-linux-musl
sh scripts/service-build.sh build-screen --all
sh scripts/service-build.sh build-agent --all
make screen-service
```

发行用 Mac 录屏服务可设置 `TERMISH_SCREEN_MACOS_SIGN_IDENTITY` 为钥匙串中固定的
Developer ID Application 证书名称或指纹。构建工具以稳定标识 `dev.termish.screen-service`
签名两个 Darwin 应用包、校验签名，再打包并计算上传/安装使用的 SHA-256；签名失败即停止构建。
manifest 保留签名身份，Android/Linux 打包导入时不改签名。未设置时仍为 ad hoc 构建，
不能保证升级后保留权限；不接受 `-` 作为保留授权的签名身份。证书私钥不提交到仓库。

Linux 至少安装对应 musl 目标，使用 Rust 自带链接器，无需交叉 C 编译器或 X11 开发库。
默认构建本机已安装且可用的目标；相同源码指纹的导入产物会保留。CI 先在 Mac 构建全部四种
目标，再通过 artifact 传给 Android 构建。构建期间源码变化、清单和二进制 SHA 不匹配均失败。

`screenServiceBuild` 依赖 `screenServiceRustBuild`，在 Kotlin 编译与资源复制前打包。
清单位于 `screenService/build/binaries/`，生成 Kotlin 位于
`composeApp/build/generated/screenService/kotlin/`，文件资源位于
`composeResources/files/termish-screen/`。生成产物不提交。

部署版本修改 `service.properties` 的 `RELAY_VERSION`，独立于应用版本。

## 配置与服务路径

安装目录为 `~/Library/Application Support/termish/`，配置在签名包之外，升级保留 token 和自定义路径：

```text
Termish Helper.app/        # macOS 后台应用（Linux 仍为 screen-service）
  Contents/Info.plist
  Contents/MacOS/Termish Helper
  Contents/Resources/AppIcon.icns
screen-service.NOTICE      # 完整第三方版权声明
screen-service.backend     # rust
screen-service.json        # 配置，0600
```

Darwin payload 文件名保留 `termish-screen-Darwin-*`，内容为签名应用包的 gzip tar；Linux payload
仍为 ELF。安装先验证上传 SHA，再解包并校验签名与版本；旧服务只在校验通过后停用。
启动失败恢复旧应用包与 LaunchAgent；成功后移除旧裸程序。JSON 和 LaunchAgent XML
由 Rust 生成，中文、引号和 shell/XML 字符不经展开；无效配置不覆盖原文件。

```bash
"$HOME/Library/Application Support/termish/Termish Helper.app/Contents/MacOS/Termish Helper" --version
# Linux 或独立 Rust 调试二进制仍支持这些参数：
screen-service --config /path/to/screen-service.json --check-config
screen-service --config /path/to/screen-service.json
screen-service --display-state
# Linux：同账号的桌面会话 D-Bus 管理，非网络管理端口
screen-service --manage
screen-service --control status
screen-service --control pause     # resume / disconnect / restart / quit
screen-service --check-encoder     # 只编码测试色块，不捕获桌面、不绑定端口
```

`--version`/`--help` 不需要 FFmpeg/token；`--check-config` 不绑定端口、启动编码器或请求授权，
也不输出 token。

| 配置 | 默认值与用途 |
| --- | --- |
| `port` | 17321 探测、17323 TCP 视频，仅 IPv4 回环 |
| `ffmpeg` | 安装时解析绝对路径 |
| `capture_source` | `desktop`；诊断可选 `test_pattern`，只生成测试图案，不抓桌面或注入输入 |
| `menu_bar` | 两个平台的桌面采集默认 `true`；`test_pattern` 默认 `false`，可设 `true` 隔离测试菜单；Linux 为托盘开关，管理窗口和 CLI 独立可用 |
| `encoder` | Linux 默认 `auto`；可选 `software`、`nvenc`、`qsv`、`vaapi`，指定硬件不可用时仍回退软件；macOS 保持 VideoToolbox |
| `token_file` | `~/.termish-screen.token`，256-bit token |
| `log_file` | macOS `~/Library/Logs/termish-screen.err`，Linux `~/.termish-screen.err` |
| `encoder_pid_file` | `~/.termish-screen-ffmpeg.pid`，诊断及自身子进程清理 |
| `stream_config_file` | `~/.termish-screen.conf`，手机 FPS/画质设置 |

手机部署保留默认端口，独立调试可用 `--port`/`--ffmpeg` 覆盖。原来的 UDP 兼容入口已移除，
手机使用 TCP 协议。

## 生命周期与诊断

macOS 使用 `dev.termish.screen` LaunchAgent，Linux 使用同名 systemd 用户服务，
不可用时退回桌面自启动。Linux 启动器重新探测图形会话与 Xauthority。

Linux 用原生 Rust 实现 StatusNotifierItem 与 D-Bus Menu，复用服务的同一生命周期状态；
KDE 及启用了对应托盘宿主的 GNOME 可显示 Termish 图标。宿主晚启动或重启后自动重新注册。
GNOME 没有托盘支持时，从应用列表打开 **Termish Helper／Termish 服务管理**，提供相同的管理操作。
安装器部署品牌 SVG 与桌面启动项，管理窗口依赖 zenity，缺少时与其他系统依赖共用已有 sudo 授权流程。
服务状态和菜单动作只通过当前账号的会话 D-Bus 暴露（服务名包含端口），不增加公网或回环管理端口。
无会话总线时远程画面服务仍可运行，管理入口不可用并记录原因。

Linux 菜单显示 X11 桌面可用性或 Wayland Portal 授权状态，查看权限状态不触发授权窗口。
权限入口提供当前平台的授权指引；Wayland 仍在连接时通过系统窗口选择屏幕并允许控制。
logind 只读取当前用户活跃、本地的图形会话 `LockedHint`，排除 SSH 与登录界面。
X11 读取 DPMS 休眠状态并在连接时尝试唤醒；Wayland 不使用 Xwayland DPMS 冒充物理屏幕状态。
手机端会读取两个平台的锁屏／休眠提示。这是状态提示，不代表 Wayland 允许锁屏捕获或输入；
不修改安全策略、不保证各桌面支持解锁，真实 GNOME/KDE 锁屏仍需按环境验收。

macOS 菜单由主线程 AppKit `NSStatusItem` 驱动，TCP 接受与会话管理在后台线程运行；
打开菜单不阻塞视频或心跳。状态显示启动中、等待连接、已连接或访问已暂停。
「断开连接」清理当前会话，仍允许新连接；「暂停远程访问」清理当前会话并拒绝新鉴权连接，
直到选择「恢复远程访问」。暂停只保留在当前进程，重启或下次登录会恢复访问。
鉴权后的暂停连接直接关闭，不新增手机协议状态码，也不启动编码器或触发权限请求。

录屏与控制权限分开显示，并可直接打开对应系统设置；菜单检查权限不请求授权。
「未生效」表示当前进程的检测结果，可能来自未授权、代码身份变化或进程缓存；
系统设置中已开启但未生效时可选「重启服务／刷新权限」。菜单还可查看日志和打开服务目录。
诊断画面模式禁用权限入口，避免将测试图案误认为已获得桌面录屏或控制权限。

「重启服务」先释放输入、关闭连接并清理编码器，再由服务监督器拉起新进程。
Linux systemd 单元设置 `TERMISH_SYSTEMD_SERVICE=1`、`Restart=on-failure`，重启动作以失败码退出
让监督器拉起替代进程；正常退出不会立即重启。桌面自启动或独立运行则保留原参数启动替代进程。
macOS LaunchAgent 设置 `RunAtLoad=true`、
`KeepAlive={SuccessfulExit:false}`，异常退出会重启，「退出 Termish Helper」正常退出后不会立即拉起。
退出不移除自启动配置，下次登录仍自动启动；手机重新安装/显式启动服务也会再次启动。

鉴权前不启动编码器、不返回状态或画面；健康客户端占用时返回 `THS1 + 3`。
macOS 缺录屏权限返回 `THS1 + 4` 并关闭连接，不创建编码器或占用画面会话。
连接首包仍为未分帧的 `THS1 + status`；后续权限更新为 `[4B 大端长度=5][THS1 + status]`，
由唯一视频写入线程在完整视频包之间发送，手机解析后更新控制权限提示。
6 秒未续租会关闭连接并释放编码器；反馈触发恢复保留 TCP 与帧序号，并发送新关键帧。
断开或退出补发鼠标释放事件，清理 FFmpeg/GStreamer；Linux 子进程设置父进程死亡信号。
Wayland Portal 会话在服务退出时关闭。

日志记录版本、端口、拒绝、编码器启动/恢复和关闭原因，不记录 token 或输入内容。
先查 `--check-config`、服务进程与首帧，再查手机解码、反馈和重连日志。服务迁移不代表
此前偶发断连已经全部解决。

## 验证

```bash
./gradlew screenServiceTest
make test-integration
# Linux：隔离 D-Bus mock（无需真实桌面或授权）
cargo test --locked --manifest-path screenService/rust/Cargo.toml \
  portal_scopes_video_and_input_and_passes_owned_fd -- --ignored --test-threads=1
# Linux：独立虚拟桌面的真实捕获、Tab、Unicode 剪贴板与断线释放
xvfb-run -a -s '-screen 0 640x360x24' cargo test --locked \
  --manifest-path screenService/rust/Cargo.toml real_x11 -- --ignored --test-threads=1
# 私有会话总线：托盘协议、宿主重启、暂停/断开/退出及 systemd 退出码
cargo test --locked --manifest-path screenService/rust/Cargo.toml \
  --test linux_desktop -- --ignored --test-threads=1 --skip management_window
# 无托盘宿主时的中英文管理窗口，使用虚拟桌面
xvfb-run -a -s '-screen 0 960x720x24' cargo test --locked \
  --manifest-path screenService/rust/Cargo.toml management_window -- --ignored --test-threads=1
# 初始化成功但实际硬件编码失败：不断开连接，回退后帧序号继续递增
xvfb-run -a -s '-screen 0 640x360x24' cargo test --locked \
  --manifest-path screenService/rust/Cargo.toml hardware_failure -- --ignored --test-threads=1
```

测试依赖 Rust、FFmpeg；Linux 场景另需 dbus-daemon、Xvfb、xauth、xev、xdotool、xclip、zenity、desktop-file-utils；中文窗口截图需 CJK 字体。
合成画面测试使用临时 HOME/配置、独立 token 与随机端口。手机测试验证 SFTP 安装与 TCP
视频解析。真实安装会影响账号已有服务，默认 SKIP，仅显式设置
`TERMISH_TEST_INSTALL_SCREEN=1` 时运行。真实系统授权、锁屏/休眠、Wayland 桌面行为需人工验证。
端到端用例与报告保存在本地 `.aiadb/`，不随仓库分发。
