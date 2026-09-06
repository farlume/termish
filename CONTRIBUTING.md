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
| Agent Bridge | Python 3; its build and tests use only the standard library |

Set the Android SDK path in `local.properties` or `ANDROID_HOME`.
Debug builds and tests do not require the maintainer's signing keys, cloud
accounts, private machines or AI provider credentials. Agent integration testing
uses your own configured provider account. See [Build & Test](README.md#build--test)
for the full toolchain and iOS build steps.

```bash
make run                                      # Android debug build, install and launch
./gradlew :composeApp:testDebugUnitTest         # Kotlin unit tests
python3 -m unittest discover -s agentBridge/tests
make test-integration                         # Start local sshd and run transport tests
```

Run `make help` for other tasks. Release signing is only needed for release
builds; `.env.example` documents the optional configuration.

## Where changes belong

- `composeApp/src/commonMain/kotlin/dev/termish/term/` contains the pure-Kotlin terminal
  emulator. Keep platform and UI dependencies out of this module.
- `composeApp/src/commonMain/kotlin/dev/termish/mosh/` contains the Mosh protocol
  and prediction layers. Preserve the boundary documented in [AGENTS.md](AGENTS.md).
- `composeApp/src/commonMain/kotlin/dev/termish/ui/` contains shared UI. Use the
  existing theme tokens and put user-facing copy in `AppStrings` in both languages.
- Platform code belongs behind the existing SSH, storage and utility interfaces.
- `agentBridge/` contains the Python companion and its tests. Its zipapp is
  built into the mobile app; see [the Bridge guide](agentBridge/README.md).

## Validate your change

Before a code PR, run `make test` and `make lint`. Format Kotlin with
`./gradlew ktlintFormat` before `make lint-kt`.

- Terminal or Mosh behavior: add a regression test for the affected behavior.
- SSH, SFTP or Mosh transport: run `make test-integration`.
- Agent protocol: check both the Python Bridge and Kotlin client.
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
