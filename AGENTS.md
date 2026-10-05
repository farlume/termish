# AGENTS.md — Termish

Kotlin Multiplatform mobile SSH client (Android / iOS). Pure-Kotlin
terminal emulator + Compose Multiplatform shared UI; the SSH transport is swapped
per platform against battle-tested engines: sshj + BouncyCastle on JVM, libssh2 +
OpenSSL on iOS, and a pure-Kotlin Mosh client (`dev.termish.mosh`).
Stack: Kotlin 2.1.21 · Compose Multiplatform 1.8.1 · AGP 8.9.2 · Gradle 8.14.2.

The README introduces the app; full build/test docs live in `CONTRIBUTING.md`
(Build & Test). All workflow tasks are
defined in the root `build.gradle.kts`; `Makefile` targets are thin aliases, so
`make X` and `./gradlew <task>` are equivalent. Tests and lint run locally;
CI is reserved for building, packaging and publishing.

## Common commands

```bash
make run                 # build + install debug APK on device/emulator and launch
make test                # unit tests (crypto RFC vectors + terminal emulator)
make test-integration    # transport integration tests (auto-starts local sshd)
make lint                # Android lint
make release             # signed release APK + AAB (requires .env signing secrets)
make bump V=1.0.1        # bump Android and iOS versions (preview: DRY=1)
make ios-native          # one-time cross-compile OpenSSL + libssh2 → iosApp/native/
make ios-framework       # Kotlin framework (simulator + device debug)
```

**Android 模拟器必须用 `-gpu host` 启动**：本机是 KVM 虚拟机，直通了 Intel
Arc A380（`/dev/dri/renderD129`，headless EGL/Vulkan 已验证可用）。禁止
`-gpu swiftshader_indirect` / `lavapipe` 软渲染——软渲染把 GPU 的活全压给
CPU，模拟器常年 200%+ CPU、机器发热。AVD `termish` 的 `hw.gpu.mode` 已设为
`host`；启动命令不要再显式传软渲染参数（`-gpu` 参数会覆盖 AVD 配置）。

Test sshd: `./scripts/test-sshd.sh` (127.0.0.1:22222, generates ephemeral ed25519 keys).
Demo server (screenshots / herdr+pi): Docker container `termish-demo` on the dev
machine — credentials & ports are kept in local test assets (`.aiadb/`, gitignored),
not in this repo. Android emulator reaches the host at `10.0.2.2`; iOS simulator
(shares host network) at `127.0.0.1` — never `10.0.2.2` on iOS.

## Architecture & conventions

- **`commonMain/term/` is a pure-Kotlin, zero-platform-dependency terminal
  emulator** (buffer / state machine / color / selection) — never pull platform
  APIs or Compose dependencies into it
- **`commonMain/mosh/` protocol layer is term-free**: `MoshTransport` /
  `UserStream` / `Fragmentation` / `Messages` / `Ocb` / `Aes` / `MoshCrypto` /
  `KmpMoshSession` must never `import dev.termish.term` — only the shadow
  layer (`ShadowTerminal`, `PredictionLayer`) may use the emulator (a mosh
  client must mirror the server framebuffer; reusing our own emulator is
  deliberate, not a shortcut). Keep this boundary: it's what makes the
  protocol layer extractable as a standalone library
- Platform code only lives behind expect/actual seams: `ssh/SshSession`, `util/`,
  `data/SecretStore`; `androidMain/` owns the Android JVM transport engine
- The input pipeline treats **IME composing text as a first-class citizen**:
  composing text never reaches the wire, only committed text is diffed, and
  backspace semantics are split between composing/committed — read the existing
  implementation in `TerminalScreen` / `TerminalView` before touching input logic
- UI design tokens live in `ui/theme/` (Dimens, palettes) — no ad-hoc dp/alpha
  literals in new UI code
- User-facing strings go through `AppStrings` (Chinese + English), never hardcoded
- **Code style**: Kotlin official style, `import` 短名（禁止全限定名调用）、
  import 按字母序（`.editorconfig` 基线 + ktlint 已接入，`make lint-kt` 检查/`ktlintFormat` 自动修）
- New screenshots in `docs/screenshots/` use `<topic>-{en,zh}.png` or `.webp`
  naming; en = English UI, zh = Chinese UI, every topic has a matching pair.
  The README gallery shows the Herdr terminal (including remote screen), Hosts,
  optional Agent chat and SFTP as equally sized iOS device captures. Reuse
  suitable public demo screenshots, including assets in `docs/appstore/`, and
  keep personal accounts and private host details out of the selection.

## 文档维护

- `README.md`（英文）与 `README.zh-CN.md`（中文）同步维护结构与事实性内容
- `docs/` 为面向贡献者的开发文档，正文中文、开头英文摘要
- `term/`、`mosh/`、输入交互的行为变更同步更新 `docs/terminal-emulator.md`、
  `docs/mosh.md`、`docs/input-pipeline.md` 中对应的说明
- 项目原始源码与文档以 MIT 开源；第三方版权声明及 `NOTICE`、
  `LICENSES/` 和资源内附带的许可证必须保留

## Development workflow（开发工作流）

分层验证，由快到慢，逐级上升；**日常迭代全走 debug + 模拟器，release 只在功能里程碑/准备验收时构建**：

```bash
# ① 单元测试（秒级）——改动涉及 term/、mosh/、crypto/、逻辑层时必跑
./gradlew :composeApp:testDebugUnitTest

# ② debug 构建 + 模拟器安装启动（分钟级）——日常迭代主力
make run

# ③ 集成测试（分钟级）——传输层（sshj/libssh2/mosh/SFTP）改动时必跑
make test-integration

# ④ 真机抽查（手动）——模拟器测不了的点
#    - IME 中文/日文输入（模拟器常无真实输入法，CJK 组合态管线必须真机）
#    - Mosh 漫游（WiFi↔蜂窝切换续传）、后台保活、断线重连
#    - 性能 / 电池

# ⑤ 签名 release（分钟级）——功能完成/准备验收时构建
make release    # 产物 composeApp/build/outputs/{apk,bundle}/release/
```

规则：

- **release 不是每次改动的必做项**——R8 混淆/资源收缩/签名问题在里程碑验证时暴露即可，日常被 debug 循环拖慢
- **有模拟器时模拟器安装验证优先**（`make run`）；真机按上面 ④ 的点抽查
- **提交与发版必须等用户明确确认**：本地改动攒批即可，**不得擅自** `git commit` / `make bump` / 打 tag / `git push`（历史教训：用户反馈 bug 后直接推送 GitHub 是不被接受的）。完成一个功能块后：本地验证（①/②）+ 汇报结果，**等用户说「提交/发版」再动 git**；中途防丢失用 `git stash` 或 WIP commit（WIP 不 push）
- 改动涉及 `term/` 或 `mosh/` 时先跑 ① 再上模拟器（秒级反馈，别浪费模拟器循环）

### 本地检查与 tag 发布流程

- **检查全部在本地完成**：提交前先运行 `./gradlew ktlintFormat`，再运行
  `make test lint lint-kt`；传输层改动另跑 `make test-integration`。
  修改 Rust 服务时运行对应服务的 `scripts/service-test.sh`；修改发布工具时运行
  `node --test scripts/tests/*.test.mjs`。按改动范围完成上面的模拟器、端到端和真机验证，
  明确记录 PASS / FAIL / SKIP，不能把未验证的场景报为通过。
- **发版仍需用户明确授权**：获得「提交/发版」指令后，用 `make bump V=X.Y.Z`
  同步版本，在本地运行 `make release` 并检查签名和产物；检查通过后提交、打
  `vX.Y.Z` tag，再推送提交和 tag。检查失败先修复，不推送发版 tag。
- **CI 仅构建、打包和发布**：自动入口只保留 `v*` tag，另保留手动构建入口；
  `main` 推送和 PR 不触发流水线。tag 推送后直接构建原生服务、打包安装包、发布
  GitHub Release 并同步 R2，不重复运行单元测试、集成测试或 lint。
- **保留发布产物校验**：tag 与项目版本一致性、签名配置、安装包签名及文件摘要校验
  仍属于打包发布流程，用于避免发布错版、未签名或损坏的文件。
- 用户要求不跟踪 GitHub 状态时，推送成功后结束；如实说明流水线已触发，
  不轮询构建状态，也不把尚未确认的发布结果报为成功。

## Testing discipline

- Any change to `term/` (escape sequences, buffer, wide-char behavior) → add a
  matching unit test in `commonTest/`
- Any change to the transport layer (sshj / libssh2 / mosh / SFTP) → run
  `make test-integration`
- Integration tests **self-detect** 127.0.0.1:22222 and SKIP gracefully when sshd
  is absent — skipping is not a failure, don't try to "fix" it

## Debug & edit discipline

- **调试用 `TermLog`，禁止临时 `println`**；临时打点调试完必须移除——
  打点体系已覆盖全链路，需要新打点走 `TermLog`/`TermTrace` 而非临时代码
- **批量脚本（sed/python）改代码后必须编译 + 抽查 diff 替换点**——
  历史教训：变量遮蔽（`s` 顶掉 AppStrings）、缩进错乱、全限定名漂移
  全出在批量替换；批量改完逐处确认替换点语义
- **破坏性环境操作先说明影响**：`pm clear`（清模拟器数据）、停 demo 服务器
  等操作会丢状态，执行前告知用户
- **ktlint 工作流（防反复失败）**：改完代码先 `./gradlew ktlintFormat`
  （自动修复 import 排序/缩进/换行/unused import），再 `./gradlew ktlintCheck`
  确认——**禁止直接 check**（可自动修复的格式问题会导致反复失败，历史教训：
  import 未排序、unused import 残留、Kotlin 字符串里 `${'$'}` 字面转义写错
  显示变量名）。提交前本地 `make lint-kt` 必过
- **Kotlin 字符串插值**：python 批量脚本写入 Kotlin 源码时，模板串里的 `$var`/
  `${expr}` 直接写（python 无 `$` 语义），**不要用 `${'$'}` 转义**——那会让
  用户看到字面变量名（历史教训：帧率档位显示 `$fps`）

## E2E 场景测试（AI 执行）

- 资产：`.aiadb/cases/`（自然语言用例；**本地 gitignored 资产，不随仓库分发**）
- 连接/会话/通知/网络相关改动后：执行对应端到端用例；新增页面/功能后补充用例
- 每场景独立报告 PASS/FAIL/SKIP；失败先区分应用 bug 与操作问题

## Traps

- After editing `.env` / signing secrets run `make gradle-stop` (the Gradle daemon
  does not pick up new environment variables)
- iOS native deps (OpenSSL/libssh2) are git-ignored build artifacts. Verify iOS
  behavior locally with `make ios-native && make ios-framework`, then build &
  install the app:
  ```bash
  cd iosApp
  xcodebuild -project iosApp.xcodeproj -target iosApp -sdk iphonesimulator \
    -configuration Debug -destination 'platform=iOS Simulator,name=iPhone 17 Pro' \
    ARCHS=arm64 ONLY_ACTIVE_ARCH=YES build   # ARCHS=arm64 is REQUIRED on Xcode 26
  xcrun simctl install booted build/Debug-iphonesimulator/Termish.app
  xcrun simctl launch booted dev.termish.app
  ```
  Without `ARCHS=arm64 ONLY_ACTIVE_ARCH=YES`, Xcode 26's `-target` build passes
  `arch=undefined_arch` and the Kotlin framework task fails with
  "Could not infer iOS target architectures"
- The signing keystore was rebuilt on 2026-08-17 (alias `termish`, CN=Termish) **before any public release** — old `mssh` alias is gone, no upgrade-path constraint. The pre-release legacy keystore is archived off-repo (local backup, not in git). Once any build is published, the **private key must never change** (breaks signature consistency and upgrade installs) — keep backups off-repo and never commit `.env` / `*.jks`
- Version numbers are synced across Android and iOS: always use
  `make bump`, never edit by hand; a pushed `vX.Y.Z` tag is validated by CI against
  the checked-in version
- Uncaught Kotlin exceptions on iOS are dumped to `<NSTemporaryDirectory>/termish-diag.log`
- `.env` and `*.jks` are git-ignored — never commit real secrets, and don't
  `git add -f` them

## Commits

- **git 操作全部等用户明确确认**：`commit` / `make bump` / `tag` / `push` 一律在用户说「提交/发版」之后执行；修复反馈、功能迭代完成后先本地验证并汇报，不得擅自推送 GitHub
- Commit messages are written in **Chinese**, short summary + body explaining
  **why** (see `git log` for the house style)
- Commit message 含 `$` 等 shell 敏感字符时用 `-F <file>` 方式提交，
  避免 shell 展开乱码（历史教训：`$TERM` 被展开）
- Before a PR: `make test` and `make lint`; transport-layer changes also run
  `make test-integration`
