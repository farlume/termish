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

Test sshd: `./scripts/test-sshd.sh` (127.0.0.1:2222, generates ephemeral ed25519 keys).

## Architecture & conventions

- **`commonMain/term/` is a pure-Kotlin, zero-platform-dependency terminal
  emulator** (buffer / state machine / color / selection) — never pull platform
  APIs or Compose dependencies into it
- Platform code only lives behind expect/actual seams: `ssh/SshSession`, `util/`,
  `data/SecretStore`; `jvmSharedMain/` is the JVM engine shared by Android + desktop
- The input pipeline treats **IME composing text as a first-class citizen**:
  composing text never reaches the wire, only committed text is diffed, and
  backspace semantics are split between composing/committed — read the existing
  implementation in `TerminalScreen` / `TerminalView` before touching input logic
- UI design tokens live in `ui/theme/` (Dimens, palettes) — no ad-hoc dp/alpha
  literals in new UI code
- User-facing strings go through `AppStrings` (Chinese + English), never hardcoded

## Testing discipline

- Any change to `term/` (escape sequences, buffer, wide-char behavior) → add a
  matching unit test in `commonTest/`
- Any change to the transport layer (sshj / libssh2 / mosh / SFTP) → run
  `make test-integration`
- Integration tests **self-detect** 127.0.0.1:2222 and SKIP gracefully when sshd
  is absent — skipping is not a failure, don't try to "fix" it

## Traps

- After editing `.env` / signing secrets run `make gradle-stop` (the Gradle daemon
  does not pick up new environment variables)
- iOS native deps (OpenSSL/libssh2) are git-ignored build artifacts, and **CI does
  not build iOS** — verify iOS changes locally: `make ios-native && make ios-framework`
- The signing keystore alias is the legacy value `mssh` — **do not change it**
  (renaming breaks signature consistency and upgrade installs)
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
