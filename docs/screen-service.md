# 远程画面服务

> **English summary:** the phone installs precompiled Rust companions over SSH/SFTP for macOS and Linux (X11/Wayland), on arm64 and x86_64. Rust owns authenticated TCP video, native input, portal consent and encoder lifecycle; no remote compiler or interpreter is required.

## 手机安装与依赖

手机沿用 SSH 登录，探测系统、架构和图形会话，通过 SFTP 分块上传到私有临时文件。
安装器校验 SHA-256、可执行性与部署版本后，安装或复用 FFmpeg，生成或复用 token，
由 Rust 校验配置并原子启用可执行文件，再注册图形会话服务和验证回环端口。
不支持或未打包的架构会返回明确错误。

macOS 用 FFmpeg avfoundation/VideoToolbox，X11 用 x11grab/libx264 和 XTEST，文本粘贴依赖 xclip。
Wayland 用 Rust D-Bus RemoteDesktop/ScreenCast Portal 同一授权会话获取画面和输入权限，
将授权后的 PipeWire FD 交给 GStreamer，视频管道输入 FFmpeg 编码；无需 Xwayland 抓根窗口。
Portal 响应订阅先于请求，避免快速授权响应丢失；坐标使用授权流的逻辑尺寸。

macOS 屏幕录制、辅助功能与 Wayland Portal 均需系统授权。新的可执行文件身份可能需要重新授权。
服务运行在图形登录会话中，锁屏、休眠、注销及操作系统安全限制仍然适用。

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

Linux 至少安装对应 musl 目标，使用 Rust 自带链接器，无需交叉 C 编译器或 X11 开发库。
默认构建本机已安装且可用的目标；相同源码指纹的导入产物会保留。CI 先在 Mac 构建全部四种
目标，再通过 artifact 传给 Android 构建。构建期间源码变化、清单和二进制 SHA 不匹配均失败。

`screenServiceBuild` 依赖 `screenServiceRustBuild`，在 Kotlin 编译与资源复制前打包。
清单位于 `screenService/build/binaries/`，生成 Kotlin 位于
`composeApp/build/generated/screenService/kotlin/`，文件资源位于
`composeResources/files/termish-screen/`。生成产物不提交。

部署版本修改 `service.properties` 的 `RELAY_VERSION`，独立于应用版本。

## 配置与服务路径

安装目录为 `~/Library/Application Support/termish/`：

```text
screen-service             # Rust 可执行文件，0700
screen-service.NOTICE      # 完整第三方版权声明
screen-service.backend     # rust
screen-service.json        # 配置，0600
```

重装更新端口与 FFmpeg 路径，保留 token、日志/PID/画质配置路径。JSON 和 LaunchAgent XML
由 Rust 生成，中文、引号和 shell/XML 字符不经展开；无效配置不覆盖原文件。

```bash
screen-service --version
screen-service --config /path/to/screen-service.json --check-config
screen-service --config /path/to/screen-service.json
screen-service --display-state
```

`--version`/`--help` 不需要 FFmpeg/token；`--check-config` 不绑定端口、启动编码器或请求授权，
也不输出 token。

| 配置 | 默认值与用途 |
| --- | --- |
| `port` | 17321 探测、17323 TCP 视频，仅 IPv4 回环 |
| `ffmpeg` | 安装时解析绝对路径 |
| `token_file` | `~/.termish-screen.token`，256-bit token |
| `log_file` | macOS `~/Library/Logs/termish-screen.err`，Linux `~/.termish-screen.err` |
| `encoder_pid_file` | `~/.termish-screen-ffmpeg.pid`，诊断及自身子进程清理 |
| `stream_config_file` | `~/.termish-screen.conf`，手机 FPS/画质设置 |

手机部署保留默认端口，独立调试可用 `--port`/`--ffmpeg` 覆盖。原来的 UDP 兼容入口已移除，
手机使用 TCP 协议。

## 生命周期与诊断

macOS 使用 `dev.termish.screen` LaunchAgent，Linux 使用同名 systemd 用户服务，
不可用时退回桌面自启动。Linux 启动器重新探测图形会话与 Xauthority。

鉴权前不启动编码器、不返回状态或画面；健康客户端占用时返回 `THS1 + 3`。
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
```

测试依赖 Rust、FFmpeg；Linux 场景另需 dbus-daemon、Xvfb、xauth、xev、xdotool、xclip。
合成画面测试使用临时 HOME/配置、独立 token 与随机端口。手机测试验证 SFTP 安装与 TCP
视频解析。真实安装会影响账号已有服务，默认 SKIP，仅显式设置
`TERMISH_TEST_INSTALL_SCREEN=1` 时运行。真实系统授权、锁屏/休眠、Wayland 桌面行为需人工验证。
端到端用例与报告保存在本地 `.aiadb/`，不随仓库分发。
