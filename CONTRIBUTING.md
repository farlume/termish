# Contributing to Termish

Termish is an MIT-licensed mobile SSH/Mosh client and native Agent workspace.
Bug reports, documentation improvements and pull requests are welcome.
You can contribute in English or Chinese. 中文使用与构建说明见 [README.zh-CN.md](README.zh-CN.md)。

## Get started

```bash
git clone https://github.com/ttermish/termish.git
cd termish
```

| Component | Development requirements |
| --- | --- |
| Android | JDK 17, Android SDK, and an emulator or device for UI checks |
| iOS | macOS and Xcode; build native dependencies with `make ios-native` |
| Remote services and build tool | Rust via rustup; supported target libraries; Xcode SDK for macOS payloads |
| R2 release tools | Node.js 22 and curl with `--aws-sigv4` support |

Set the Android SDK path in `local.properties` or `ANDROID_HOME`.
Debug builds and tests do not require the maintainer's signing keys, cloud
accounts, private machines or AI provider credentials. Agent integration testing
uses your own configured provider account.

## Build & Test

Run these commands from the repository root.

### Android

```bash
./gradlew :composeApp:assembleDebug            # Build a debug APK
make run                                     # Build, install and launch on a device/emulator
```

### Tests

```bash
./gradlew :composeApp:testDebugUnitTest         # Kotlin unit tests
./gradlew agentBridgeTest                      # Rust Bridge protocol and adapter tests
./gradlew screenServiceTest                    # Rust screen protocol, configuration and lifecycle tests
./gradlew screenServiceRustBuild               # Build precompiled remote Rust payloads
make test-integration                         # Start local sshd and run transport tests
```

Kotlin tests cover the terminal emulator, Mosh, crypto RFC vectors and app logic.
Transport tests detect a local sshd on `127.0.0.1:22222` and skip when it is absent.
For manual transport checks, `./scripts/test-sshd.sh` starts the test server with
ephemeral keys. Bridge tests can also run without Gradle:
`sh scripts/service-test.sh agentBridge`.
Screen service tests can also run with
`sh scripts/service-test.sh screenService`.
Mobile builds now need Rust via rustup and at least one supported service target.
For example, Linux builders use `rustup target add x86_64-unknown-linux-musl`;
macOS builders can build all four targets as described in the screen service guide.

### iOS

Requires macOS and Xcode. Build OpenSSL and libssh2 on first setup or after
changing their versions, then compile the shared frameworks:

```bash
make ios-native                               # Native dependencies for simulator and device
make ios-framework                            # Kotlin simulator and device debug frameworks
open iosApp/iosApp.xcodeproj                    # Select the iosApp scheme and run in Xcode
```

The framework tasks can also be run directly:

```bash
./gradlew :composeApp:linkDebugFrameworkIosSimulatorArm64
./gradlew :composeApp:linkDebugFrameworkIosArm64
```

Choose a simulator or configure your own signing team for a physical device.
iOS builds are checked locally or through the configured Apple distribution
workflow; GitHub Actions does not compile iOS frameworks. Check UI behavior locally.

Run `make help` for other tasks. Release signing is only needed for release
builds; [`.env.example`](.env.example) documents the optional configuration.
Maintainers can find Android release mirroring configuration in the
[R2 release guide](docs/r2-release.md).

## Where changes belong

- `composeApp/src/commonMain/kotlin/dev/termish/term/` contains the pure-Kotlin terminal
  emulator. Keep platform and UI dependencies out of this module.
- `composeApp/src/commonMain/kotlin/dev/termish/mosh/` contains the Mosh protocol
  and prediction layers. Preserve the boundary documented in [AGENTS.md](AGENTS.md).
- `composeApp/src/commonMain/kotlin/dev/termish/ui/` contains shared UI. Use the
  existing theme tokens and put user-facing copy in `AppStrings` in both languages.
- Platform code belongs behind the existing SSH, storage and utility interfaces.
- `agentBridge/rust/` contains the Rust companion and its tests. Target binaries are
  built into the mobile app; see [the Bridge guide](agentBridge/README.md).
- `screenService/` contains the standalone remote-screen service and installer.
  Gradle generates the bundled Kotlin installer before compilation; see the
  [screen service guide](docs/screen-service.md).

## Validate your change

Before a code PR, run `make test` and `make lint`. Format Kotlin with
`./gradlew ktlintFormat` before `make lint-kt`.

- Terminal or Mosh behavior: add a regression test for the affected behavior.
- SSH, SFTP or Mosh transport: run `make test-integration`.
- Agent protocol: check both the Rust Bridge and Kotlin client.
- UI: check the affected platform, both languages and both themes; attach screenshots.
- Documentation-only changes: check links and factual accuracy; no app build is needed.

Report skipped checks and their reason in the PR. Use your own test hosts and
accounts. Never attach passwords, private keys, access tokens or unredacted
terminal history to a public report. See [SECURITY.md](SECURITY.md) for private
vulnerability reporting.

## Documentation

Keep [README.md](README.md) and [README.zh-CN.md](README.zh-CN.md) aligned.
Detailed development documents in `docs/` use Chinese with an English summary.
Update the relevant architecture, terminal, Mosh or input-pipeline document when
behavior changes. Changes to Agent Bridge belong in its protocol documentation.

## Submit a pull request

1. For a substantial feature, open an [issue](https://github.com/ttermish/termish/issues)
   to describe the user problem and proposed scope before implementation.
2. Fork the repository and create a branch for a focused change.
3. Explain the resulting behavior and the checks you ran in the PR template.
4. Keep unrelated refactors, generated build output and personal settings out of the PR.

Existing commit summaries are in Chinese; English contributions are welcome.
Contributions use the project's [MIT License](LICENSE). Preserve third-party
notices in `NOTICE`, `LICENSES/` and bundled resources. Community participation
follows the [Code of Conduct](CODE_OF_CONDUCT.md).

Version bumps, signing and release tags are managed by the maintainers. A normal
contribution does not need a version bump or access to release credentials.
