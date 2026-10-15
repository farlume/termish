# 远程画面服务

> **English summary:** macOS uses a Python session relay, native ScreenCaptureKit/CoreGraphics capture, VideoToolbox encoding, and session-scoped power assertions. Linux keeps the precompiled Rust X11/Wayland service. Both use authenticated, loopback-only H.264 video over SSH. Closed-lid recovery requires real-device validation and macOS capture consent.

## 手机安装与依赖

手机沿用 SSH 登录，探测系统、架构和图形会话，通过 SFTP 分块上传到私有临时文件。
安装器校验 SHA-256、可执行性与部署版本后，安装或复用 FFmpeg，生成或复用 token，
由 Rust 校验配置并原子启用可执行文件，再注册图形会话服务和验证回环端口。
不支持或未打包的架构会返回明确错误。
FFmpeg 配置保留包管理器的绝对路径入口及符号链接，不解析到具体版本目录，
避免 Homebrew 升级依赖后仍调用旧二进制。既有配置若指向失效的 Cellar 版本，
可重新安装服务以更新路径，或将配置中的 `ffmpeg` 改为当前稳定入口后重启服务。
SSH 安装命令显式使用 `/bin/sh`，避免账号登录 shell（如 zsh）的未匹配通配符规则
中断安装；进度与错误输出合并读取，保留最后的成功标记或失败原因。
Linux 原生服务启动验证成功后清理旧 Python 脚本及对应缓存。macOS 保留并更新
`screen-relay.py`，安装失败恢复原脚本、原启动配置、原后台程序和后端标记；配置、token、日志保留。

macOS 的 `dev.termish.screen` GUI LaunchAgent 运行
`/usr/bin/python3 ~/Library/Application Support/termish/screen-relay.py`。部署版本 63 起优先由
Helper 的 `--capture-native FPS WIDTH BITRATE` 子进程抓屏和编码，ScreenCaptureKit 无首帧或不可用时
尝试 CoreGraphics 显示流，VideoToolbox 输出 Annex-B H.264。原生子进程不存在时保留 AVFoundation
回退，设备编号从 FFmpeg 的视频设备列表识别，禁止退回摄像头或把音频编号当成屏幕。
Python 使用标准库调用 CGEvent，无需 PyObjC；安装器不启动菜单栏服务。
手机上传的原生工具包仍校验 SHA-256、签名和部署版本；Python 脚本随已认证 SSH 安装正文部署。
X11 使用 Rust x11grab/XTEST，文本粘贴依赖 xclip。
Linux 默认先用合成色块探测 NVENC、QSV、VAAPI；FFmpeg 列出编码器不代表驱动能初始化。
探测有总时限，断开连接可取消；驱动或设备不可用时回退 libx264。实际推流期间硬件编码器退出
或长时间无输出，同一 TCP 会话内切回软件编码并保留帧序号；软件采集本身失败仍会结束连接。
系统需有对应显卡驱动及设备访问权限，服务不强制安装或更换驱动。
Wayland 用 Rust D-Bus RemoteDesktop/ScreenCast Portal 同一授权会话获取画面和输入权限，
将授权后的 PipeWire FD 交给 GStreamer，视频管道输入 FFmpeg 编码；无需 Xwayland 抓根窗口。
Portal 响应订阅先于请求，避免快速授权响应丢失；坐标使用授权流的逻辑尺寸。
`CreateSession` 响应中的 `session_handle` 按 Portal 的兼容性约定为字符串（`s`），
校验后再转为后续方法需要的对象路径（`o`）；不能直接按对象路径变体解码。
连接结束时关闭其 Portal 会话；重连等待旧连接清理完毕后重新获取授权，避免复用已失效的
会话。`OpenPipeWireRemote` 拒绝时也会清除缓存。同一连接内调整码率继续复用当前授权。

macOS 原生抓屏需要 Helper 的系统录屏许可。服务先尝试实际采集，不用额外预检阻断抓屏；
失败后才区分缺少许可（状态 4）与显示设备暂不可用。缺少许可不会不断重试并反复弹窗，
手机提示在系统设置中允许 Helper 后重连。系统 Python 的控制权限与原生抓屏权限分别管理。
控制使用系统 Python 身份的 CGEvent 事件发送权限；缺控制权限仍可观看画面，首次实际控制
才请求一次事件发送权限，心跳不触发权限请求。手机在缺控制权限后的显式重连会刷新同一
LaunchAgent，已有许可可在新进程中生效。程序不修改 TCC 数据库或系统安全设置。

Wayland 的 Portal 授权流程保持不变。服务仍运行在图形登录会话，锁屏、休眠和注销限制适用。

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

Darwin 安装工具包仍支持 `TERMISH_SCREEN_MACOS_SIGN_IDENTITY`，可使用固定 Developer ID
证书签名并校验完整应用包；未设置时使用 ad hoc 签名，manifest 保留签名信息。
原生采集也位于该包内，正式分发应使用固定 Developer ID 证书，避免更新 ad hoc 包后丢失
录屏授权身份。本地开发可用已有 Apple Development 身份验收，不能据此宣称已完成公开分发签名。
证书私钥不提交到仓库。

正式 tag 流程从同名 Release 草稿导入签名机预先构建的
`Termish-X.Y.Z-remote-services.tar.gz`，校验归档内的提交 SHA、源码指纹、每个文件摘要，
并核对两个 Mac Helper 的签名证书必须匹配 `.github/macos-signing-certificate.sha1`。
归档只包含可分发产物和清单，不包含私钥；CI 不重新签名或降级为 ad hoc。
本版现有身份为 Apple Development；签名校验与 Developer ID 公证是不同的验证项。

签名机本地完成所有检查、bump 和提交后，用同一证书构建服务及 APK/AAB：

```bash
TERMISH_SCREEN_MACOS_SIGN_IDENTITY='签名机的固定证书身份' sh scripts/service-build.sh build-screen --all
sh scripts/service-build.sh build-agent --all
python3 scripts/remote-payloads.py verify-macos
python3 scripts/remote-payloads.py pack /tmp/Termish-X.Y.Z-remote-services.tar.gz "$(git rev-parse HEAD)"
```

推送 tag 后立即创建同名 Release 草稿并上传该归档；CI 最多等两分钟，缺包、签名不符或
源码不一致即停止发布。发布任务保留该归档、上传签名 Android 包和摘要后再公开 Release。
更新签名身份时需明确轮换证书指纹，并重新验证升级授权，不将证书私钥传给 GitHub。

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
screen-service.backend     # macOS: python-macos；Linux: rust
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
| `menu_bar` | Linux 桌面采集默认 `true`；`test_pattern` 默认 `false`。macOS Python 会话服务不启动菜单栏；原生菜单可独立诊断 |
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

macOS 恢复旧后台服务，不再自动显示 Helper 菜单栏。手机端的断线、重新连接和更新入口
继续可用；退出连接会释放当前编码器与鼠标按键，随后允许新连接。

「重启服务」先释放输入、关闭连接并清理编码器，再由服务监督器拉起新进程。
Linux systemd 单元设置 `TERMISH_SYSTEMD_SERVICE=1`、`Restart=on-failure`，重启动作以失败码退出
让监督器拉起替代进程；正常退出不会立即重启。桌面自启动或独立运行则保留原参数启动替代进程。
macOS LaunchAgent 设置 `RunAtLoad=true`、
`KeepAlive={SuccessfulExit:false}`，异常退出会重启，正常退出不会立即拉起。
退出不移除自启动配置，下次登录仍自动启动；手机重新安装/显式启动服务也会再次启动。

鉴权前不启动编码器、不返回状态或画面；健康客户端占用时返回 `THS1 + 3`。
`THS1 + 4` 表示实际采集失败且缺少 macOS 录屏许可，手机停止自动重连并提示授权。
连接首包仍为未分帧的 `THS1 + status`；后续权限更新为 `[4B 大端长度=5][THS1 + status]`，
由唯一视频写入线程在完整视频包之间发送，手机解析后更新控制权限提示。
6 秒未续租会关闭连接并释放编码器；反馈触发恢复保留 TCP 与帧序号，并发送新关键帧。
断开或退出补发鼠标释放事件，清理 FFmpeg/GStreamer；Linux 子进程设置父进程死亡信号。
Wayland Portal 会话在服务退出时关闭。

macOS 在 THA1 认证后才创建 `PreventUserIdleDisplaySleep`、`PreventUserIdleSystemSleep` 和用户活动声明；
断开、心跳超时、服务退出都会释放。操作不需要 sudo、不修改持久电源设置、不解锁系统。
显示重配置或原生采集退出后，服务重建子进程并保留 TCP、帧序号和电源租约；静止画面重复最近帧，
避免被传输与渲染看门狗当成停流。无输出的子进程有超时并会被回收。
合盖并不保证系统一定提供显示流，不能用合成图案或唤醒 API 返回成功替代真实采集验收。

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

测试依赖 Rust、Python 3、FFmpeg；Linux 场景另需 dbus-daemon、Xvfb、xauth、xev、xdotool、xclip、zenity、desktop-file-utils；中文窗口截图需 CJK 字体。
macOS Python 回归通过 `python3 -m unittest discover -s screenService/macos/tests -v` 运行，
验证鉴权、帧格式、心跳、占用保护、反馈重启和编码器清理。合成测试使用独立配置、token 与随机端口。手机测试验证 SFTP 安装与 TCP
视频解析。真实安装会影响账号已有服务，默认 SKIP，仅显式设置
`TERMISH_TEST_INSTALL_SCREEN=1` 时运行。真实系统授权、锁屏/休眠、Wayland 桌面行为需人工验证。
端到端用例与报告保存在本地 `.aiadb/`，不随仓库分发。
Mac 上 `scripts/service-test.sh screenService` 还验证原生 VideoToolbox 图案编码、Annex-B 参数集与 FFmpeg 解码，
此项不访问桌面、不需要录屏许可。另需实际验证授权后合盖、锁屏、显示重配置和断开后电源声明释放。
