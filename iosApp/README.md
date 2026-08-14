# iosApp

iOS 宿主工程（Xcode）将在下一阶段创建。

计划：
- SwiftUI 壳 + `ComposeUIViewController`（见 `iosMain/MainViewController.kt`）
- 与 `composeApp` XCFramework 联动（`embedAndSignAppleFrameworkForXcode`）
- SSH 传输引擎：`libssh2`（cinterop 包装）或 `NMSSH`

当前 `iosMain` 已提供 `MainViewController`（返回 `ComposeUIViewController { App() }`）。
