# AGENTS.md — Termish

Kotlin Multiplatform mobile SSH client (Android / iOS / Desktop). Pure-Kotlin
terminal emulator + Compose Multiplatform shared UI; the SSH transport is swapped
per platform against battle-tested engines: sshj + BouncyCastle on JVM, libssh2 +
OpenSSL on iOS, and a pure-Kotlin Mosh client (`dev.termish.mosh`).
Stack: Kotlin 2.1.21 · Compose Multiplatform 1.8.1 · AGP 8.9.2 · Gradle 8.14.2.

Full build/test docs live in `README.md` (Build & Test). All workflow tasks are
defined in the root `build.gradle.kts`; `Makefile` targets are thin aliases — CI
reuses the same Gradle tasks, so `make X` and `./gradlew <task>` are equivalent.

## Common commands

```bash
make run                 # build + install debug APK on device/emulator and launch
make test                # unit tests (crypto RFC vectors + terminal emulator)
make test-integration    # transport integration tests (auto-starts local sshd)
make lint                # Android lint
make release             # signed release APK + AAB (requires .env signing secrets)
make bump V=1.0.1        # bump version across all 3 platforms (preview: DRY=1)
make ios-native          # one-time cross-compile OpenSSL + libssh2 → iosApp/native/
make ios-framework       # Kotlin framework (simulator + device debug)
```

Test sshd: `./scripts/test-sshd.sh` (127.0.0.1:22222, generates ephemeral ed25519 keys).
Demo server (screenshots / herdr+pi): Docker container `termish-demo`,
`termish@127.0.0.1:2223` (password `termish-demo`, fixed UDP 60100 for mosh).
Android emulator reaches it at `10.0.2.2:2223`; iOS simulator (shares host
network) at `127.0.0.1:2223` — never `10.0.2.2` on iOS.

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
  `data/SecretStore`; `jvmSharedMain/` is the JVM engine shared by Android + desktop
- The input pipeline treats **IME composing text as a first-class citizen**:
  composing text never reaches the wire, only committed text is diffed, and
  backspace semantics are split between composing/committed — read the existing
  implementation in `TerminalScreen` / `TerminalView` before touching input logic
- UI design tokens live in `ui/theme/` (Dimens, palettes) — no ad-hoc dp/alpha
  literals in new UI code
- User-facing strings go through `AppStrings` (Chinese + English), never hardcoded
- **Code style**: Kotlin official style, `import` 短名（禁止全限定名调用）、
  import 按字母序（`.editorconfig` 已设基线；仓库公开前接入 ktlint 统一格式化）
- Screenshots in `docs/screenshots/` follow `<topic>-{en,zh}.png` naming
  (topic = hosts/settings/theme/sftp/terminal-ssh/terminal-herdr/…;
  en = English UI, zh = Chinese UI, every topic has a matching pair);
  README shows 6 per language (3×2: hosts/settings/theme on top,
  terminal×2 + sftp below)

## Development workflow（开发工作流）

分层验证，由快到慢，逐级上升；**日常迭代全走 debug + 模拟器，release 只在功能里程碑/准备验收时构建**：

```bash
# ① 单元测试（秒级）——改动涉及 term/、mosh/、crypto/、逻辑层时必跑
./gradlew :composeApp:desktopTest

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
- **提交时机由用户验收驱动**：本地改动不着急 commit，攒批等用户确认后再提交；但每个功能块完成时建议 `git stash` 或 WIP commit 防丢失（当天改动当天有备份）
- 改动涉及 `term/` 或 `mosh/` 时先跑 ① 再上模拟器（秒级反馈，别浪费模拟器循环）

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

## E2E 场景测试（AI 执行）

- 技能：`ai-adb-gen`（生成用例）+ `ai-adb-run`（执行用例）
  （项目级 `.agents/skills/` 跟随仓库、克隆即得；不再安装用户级副本）
- 资产：`.aiadb/cases/`（自然语言用例，ai-adb-gen 产出）
- 连接/会话/通知/网络相关改动后：触发 ai-adb-run（A+B 全量，C/D/E 按影响面）；
  新增页面/功能后：ai-adb-gen 补用例
- 每场景独立报告 PASS/FAIL/SKIP；失败先区分应用 bug 与操作问题

## Traps

- After editing `.env` / signing secrets run `make gradle-stop` (the Gradle daemon
  does not pick up new environment variables)
- iOS native deps (OpenSSL/libssh2) are git-ignored build artifacts, and **CI does
  not build iOS** — verify iOS changes locally: `make ios-native && make ios-framework`,
  then build & install the app:
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
- Version numbers are synced in three places (Android / desktop / iOS): always use
  `make bump`, never edit by hand; a pushed `vX.Y.Z` tag is validated by CI against
  the checked-in version
- Uncaught Kotlin exceptions on iOS are dumped to `<NSTemporaryDirectory>/termish-diag.log`
- `.env` and `*.jks` are git-ignored — never commit real secrets, and don't
  `git add -f` them

## Commits

- Commit messages are written in **Chinese**, short summary + body explaining
  **why** (see `git log` for the house style)
- Commit message 含 `$` 等 shell 敏感字符时用 `-F <file>` 方式提交，
  避免 shell 展开乱码（历史教训：`$TERM` 被展开）
- Before a PR: `make test` and `make lint`; transport-layer changes also run
  `make test-integration`
