# iosApp

iOS 宿主工程（SwiftUI 壳 + Compose UIViewController）。

## 结构

- `iosApp.swift`：SwiftUI `@main`，通过 `MainViewControllerKt.MainViewController()` 挂载 Compose UI。
- `iosApp.xcodeproj`：Xcode 工程，含「Build Kotlin Framework」脚本阶段，构建时自动调用
  `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`。
- `native/`：libssh2 + OpenSSL 静态库与头文件（由 `scripts/build-ios-native.sh` 生成，不入库）。

## 构建

```bash
# 1) 构建原生依赖（首次或依赖版本变更后）
cd .. && ./scripts/build-ios-native.sh

# 2) 用 Xcode 打开并构建（或命令行）
open iosApp.xcodeproj
# 或
xcodebuild -project iosApp.xcodeproj -scheme iosApp -destination 'generic/platform=iOS Simulator' build
```

SSH 传输引擎：`libssh2`（静态链接 OpenSSL），实现见
`composeApp/src/iosMain/kotlin/dev/mssh/ssh/SshSessionLibssh2.kt`。
