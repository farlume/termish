# Termish Screen Service

> **English summary:** macOS uses a Python session relay with native ScreenCaptureKit/CoreGraphics capture, VideoToolbox encoding, and session-scoped power assertions. Linux keeps the Rust X11/Wayland service. Both stream authenticated H.264 video through loopback TCP over SSH.

- `macos/screen-relay.py`：macOS 系统 Python 服务模板，管理认证会话、电源声明、采集子进程和标准库 CGEvent 输入。
- `macos/Capture.m`：ScreenCaptureKit 优先、CoreGraphics 回退、VideoToolbox H.264；支持无录屏权限也可运行的合成图案自检。
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

macOS 用 `/usr/bin/python3` 运行会话服务；认证后唤醒显示器、保持系统与显示器不因闲置休眠，
断开及心跳到期后释放全部电源声明。它不修改持久电源设置，也不解锁系统。
Helper 的原生采集子进程处理画面；需在 macOS 允许 Helper 录屏，并用稳定证书签名保留升级授权身份。
采集失败后才判断是否缺录屏权限，不以额外预检阻止抓屏；缺控制权限仍可观看画面。
显示配置变化后重建采集子进程，保留认证通道和帧序号。静止桌面重复最近帧，避免误触发停流检测。
原生工具不可用时保留 FFmpeg 回退，并从 AVFoundation 列表定位屏幕，不依赖固定设备编号。
合盖恢复受系统实际显示状态和授权影响，需通过真实设备验收，不能以合成图案测试代替。
Linux 继续通过托盘和管理窗口提供暂停、恢复、断开、重启、日志及退出操作。
配置、token 和日志留在应用包外；新版本需在 App 中更新电脑端服务一次。
构建与诊断详见[远程画面服务文档](../docs/screen-service.md)。
