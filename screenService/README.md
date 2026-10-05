# Termish Screen Service

> **English summary:** a precompiled Rust companion captures and controls macOS, X11 or consented Wayland desktops through an authenticated, loopback-only H.264 connection tunneled over SSH. On macOS, its menu bar provides connection status, pause/resume, disconnect, permission settings, restart, logs and quit.

- `rust/`：TCP 服务、FFmpeg 生命周期、macOS CGEvent、X11 XTEST、Wayland D-Bus Portal。
- `service.properties`：部署版本与默认端口，Rust 构建工具共享读取。
- `install.sh`：依赖探测/安装及图形会话服务注册。
- `native-install.sh`：上传二进制的 SHA-256 与版本校验。
- `rust/tests/`：独立配置、合成视频、鉴权、反馈恢复和子进程清理测试。
- `macos/MenuBar.m`：AppKit 菜单栏桥接，服务和会话管理仍在 Rust 中执行。

```bash
make screen-service
./gradlew screenServiceTest
sh scripts/service-build.sh build-screen --all  # Mac + Xcode SDK，四种目标
```

手机内置 macOS/Linux arm64 与 x86_64 的二进制，通过 SSH/SFTP 安装，无需在电脑端编译。
FFmpeg 仍负责视频编码，X11 文本粘贴依赖 xclip，Wayland 视频依赖 PipeWire/GStreamer，
macOS 服务打包为带 Termish 图标的 `Termish Helper.app`，由 LaunchAgent 启动并显示菜单栏图标；
配置留在应用包外，手机仍可自动安装。桌面录屏/控制授权仍由操作系统管理。
菜单可查看连接状态、断开连接、暂停/恢复远程访问、进入权限设置、重启服务、查看日志与退出。
暂停仅作用于当前进程，重启后恢复访问；退出清理当前会话且不会立即自动拉起，下次登录仍自动启动。
构建与诊断详见[远程画面服务文档](../docs/screen-service.md)。
