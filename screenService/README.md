# Termish Screen Service

> **English summary:** macOS restores the system-Python GUI LaunchAgent and direct FFmpeg capture path. Linux keeps the Rust X11/Wayland service. Both stream authenticated H.264 video through loopback TCP over SSH.

- `macos/screen-relay.py`：macOS 系统 Python 服务模板，直接启动 FFmpeg AVFoundation/VideoToolbox，标准库 CGEvent 输入。
- `macos/tests/`：Python 服务鉴权、视频包、反馈恢复、心跳与编码器清理回归。
- `rust/`：Linux TCP、FFmpeg、X11 XTEST、Wayland Portal；macOS 安装与状态探测工具保留。
- `service.properties`：部署版本与默认端口，构建工具共享读取。
- `install.sh`、`native-install.sh`：校验上传产物、配置并注册图形会话服务，失败时恢复旧 macOS 服务。

```bash
make screen-service
./gradlew screenServiceTest
sh scripts/service-build.sh build-screen --all
python3 -m unittest discover -s screenService/macos/tests -v
```

macOS 用已有 `/usr/bin/python3` 运行 `screen-relay.py`；脚本更新保留解释器身份，
不再因新 Helper 的录屏预检拒绝抓屏。控制权限仍由 macOS 管理，缺控制权限可继续看画面。
Helper 包仅用于安装配置和显示状态探测，不再自动启动菜单栏。
Linux 继续通过托盘和管理窗口提供暂停、恢复、断开、重启、日志及退出操作。
配置、token 和日志留在应用包外；新版本需在 App 中更新电脑端服务一次。
构建与诊断详见[远程画面服务文档](../docs/screen-service.md)。
