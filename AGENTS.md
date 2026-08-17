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
- Screenshots in `docs/screenshots/` follow `topic-dark-zh` naming
  (topic = hosts/terminal/settings/sftp/agent/ios-…, dark/light, zh only for
  Chinese UI); README shows 6 per language (3×2), English README uses English
  screenshots, Chinese README uses Chinese ones

## Testing discipline

- Any change to `term/` (escape sequences, buffer, wide-char behavior) → add a
  matching unit test in `commonTest/`
- Any change to the transport layer (sshj / libssh2 / mosh / SFTP) → run
  `make test-integration`
- Integration tests **self-detect** 127.0.0.1:22222 and SKIP gracefully when sshd
  is absent — skipping is not a failure, don't try to "fix" it

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
- The signing keystore was rebuilt on 2026-08-17 (alias `termish`, CN=Termish) **before any public release** — old `mssh` alias is gone, no upgrade-path constraint. The pre-release legacy keystore is archived at `~/Documents/秘钥/termish-mssh-legacy-20260817.jks`. Once any build is published, the **private key must never change** (breaks signature consistency and upgrade installs) — back it up in `~/Documents/秘钥/` and never commit `.env` / `*.jks`
- Version numbers are synced in three places (Android / desktop / iOS): always use
  `make bump`, never edit by hand; a pushed `vX.Y.Z` tag is validated by CI against
  the checked-in version
- Uncaught Kotlin exceptions on iOS are dumped to `<NSTemporaryDirectory>/termish-diag.log`
- `.env` and `*.jks` are git-ignored — never commit real secrets, and don't
  `git add -f` them

## Commits

- Commit messages are written in **Chinese**, short summary + body explaining
  **why** (see `git log` for the house style)
- Before a PR: `make test` and `make lint`; transport-layer changes also run
  `make test-integration`
