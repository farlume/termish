<p align="center">
  <img src="iosApp/iosApp/Assets.xcassets/AppIcon.appiconset/AppIcon-1024.png" width="80" height="80" alt="Termish logo" />
</p>

<h1 align="center">Termish</h1>

<p align="center"><strong>AI coding agents and SSH / Mosh, on your phone.</strong></p>
<p align="center">Chat with Codex, Claude Code and other agents. Open a terminal, manage files, and keep working on your own servers.</p>

<p align="center">
  <a href="https://download.termish.dev/downloads/termish.apk"><strong>Download Android</strong></a> ·
  <a href="CONTRIBUTING.md#ios">Build for iOS</a> ·
  <a href="https://termish.dev">Website</a> ·
  <a href="#quick-start">Quick start</a> ·
  <a href="README.zh-CN.md">简体中文</a>
</p>
<p align="center">
  <a href="https://github.com/ttermish/termish/releases/latest"><img src="https://img.shields.io/github/v/release/ttermish/termish?color=10b981" alt="Latest release" /></a>
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="MIT license" /></a>
</p>

## Download

| Platform | Get Termish |
| --- | --- |
| Android 8.0+ | [Download the signed APK](https://download.termish.dev/downloads/termish.apk). Allow installation from this source when prompted. |
| iOS | Build with macOS and Xcode using the [iOS guide](CONTRIBUTING.md#ios). No public App Store download is available yet. |

[Release notes and all assets](https://github.com/ttermish/termish/releases/latest) · [SHA-256 checksums](https://download.termish.dev/downloads/SHA256SUMS)

<table>
  <tr>
    <th>Agent chat</th>
    <th>SSH / Mosh terminal</th>
    <th>SFTP files</th>
  </tr>
  <tr>
    <td align="center"><img src="docs/screenshots/agent-chat-en.webp" width="270" alt="Native conversation with Codex in Termish" /></td>
    <td align="center"><img src="docs/appstore/ios/en/02-terminal.jpg" width="230" alt="Remote htop session with the terminal key toolbar" /></td>
    <td align="center"><img src="docs/appstore/ios/en/03-sftp.jpg" width="230" alt="SFTP browser showing a demo project's folders and files" /></td>
  </tr>
</table>
<p align="center"><sub>Screenshots from the iOS app.</sub></p>

## Quick start

1. **Add your host.** Open Hosts → `+`, enter the SSH address, username and
   password or private key. Verify the server's host key fingerprint on first connection.
2. **Choose how to work.** Open **Agents** on the host card for native chat, tap
   the host to open a terminal, or choose **Connect via SFTP** from the `+` menu.
3. **Start a task.** In Agents, follow the Bridge setup prompt, select an agent
   and a remote working directory, then send a message. You can attach files,
   review tool activity, answer supported approval requests and revisit recent chats.

For Agent chat, the remote host needs `python3` and the agent's own login or a
supported API provider configuration. Missing supported CLIs can be installed
from the app. You do not need a Termish account.

For Mosh, choose **Mosh** in the host's connection settings. The remote needs
`mosh-server` and a reachable UDP port; Termish offers guided installation when
the server is missing. Use a startup command such as `tmux new -A -s main` when
you want terminal programs to survive client disconnects.

## Why Termish?

| Capability | What you can do |
| --- | --- |
| **Native Agent chat** | Use Codex, Claude Code, Gemini CLI, OpenCode and Pi. Follow streaming replies and tool calls, attach files, stop a task and resume conversations. Sending and approvals stay associated with their session when you switch chats. |
| **SSH + Mosh** | Open multiple terminal tabs, run tmux, vim, htop or an agent TUI. Mosh supports network roaming and local echo prediction for typing over slower connections. |
| **SFTP file manager** | Upload, download, search and organize remote files. Transfer folders, save favorites and preview text or Markdown without leaving the app. |
| **Input built for phones** | Use a CTRL / ALT / ESC toolbar, touch gestures and Chinese IME support. Voice input is available with your own speech-recognition provider configuration. |
| **Your hosts, your credentials** | Connect directly to your servers. Store saved secrets with Android Keystore or iOS Keychain. No Termish account, telemetry or hosted relay is required. |

Also included: Chinese and English UI, light and dark themes, terminal palettes,
quick commands, and live viewing of a Mac's screen over SSH after capture-service setup.

## How it works

The mobile app connects to your host over SSH. For native Agent chat, it uploads
its bundled Python Bridge over SFTP and starts it for your remote user. The
Bridge runs your selected agent; messages travel through the authenticated SSH
connection. No Docker, `pip` setup or extra public listening port is needed.

The Bridge keeps conversations on your host and lets an active agent task
continue while your phone is disconnected. Reconnect to reopen its history.
SSH / Mosh terminals and SFTP remain available alongside Agent chat.

See the [Agent Bridge guide](agentBridge/README.md) for the protocol, supported
approval types and remote storage layout.

## Privacy and connection behavior

- **Direct connection:** Termish checks SSH host keys and stores saved credentials
  using platform security facilities. Agent conversations are persisted on your remote host.
- **Optional services:** configured AI and speech-recognition providers receive
  the content required for those features and may charge for usage. Termish's
  MIT license does not include a model subscription or API credits.
- **Background limits:** iOS suspends the app in the background; some Android
  devices also restrict background connections. Termish reconnects on return.
  Use tmux or Mosh for terminal continuity; the Agent Bridge owns its remote tasks.
- **Approvals:** availability depends on the agent's protocol. Unsupported
  interactive operations return an error rather than being silently approved.

[Privacy policy](docs/appstore/privacy-policy.md) · [Report a security issue](SECURITY.md)

## Build & Test

For Android, install JDK 17 and the Android SDK, then set `ANDROID_HOME` or the
SDK path in `local.properties`:

```bash
git clone https://github.com/ttermish/termish.git
cd termish
./gradlew :composeApp:assembleDebug
```

Debug builds do not need release signing keys. See [Contributing](CONTRIBUTING.md)
for unit and integration tests, iOS builds, and development conventions.

## Project and documentation

| Component | Source / guide |
| --- | --- |
| Mobile app | [Shared UI and platform code](composeApp/src) · [Architecture](docs/architecture.md) |
| Agent Bridge | [Python companion and protocol](agentBridge/README.md) |
| Terminal and input | [Terminal emulator](docs/terminal-emulator.md) · [Input pipeline](docs/input-pipeline.md) |
| Connections and files | [SSH](docs/ssh-transport.md) · [Mosh](docs/mosh.md) · [SFTP](docs/sftp.md) |
| Cryptography | [Implementation and threat model](composeApp/src/commonMain/kotlin/dev/termish/crypto/README.md) |
| Website | [termish.dev](https://termish.dev) · [Website repository](https://github.com/ttermish/termish-website) |

Built with Kotlin and Compose Multiplatform, a pure-Kotlin terminal and Mosh
client, sshj on Android, and libssh2 on iOS.

## Contributing and support

Bug reports, documentation improvements and pull requests are welcome in English
or Chinese. Read the [contribution guide](CONTRIBUTING.md) and
[code of conduct](CODE_OF_CONDUCT.md).

[Report a bug or request a feature](https://github.com/ttermish/termish/issues) ·
[Changelog](CHANGELOG.md) · [Contact](mailto:ttermish@gmail.com)

## License

Termish's original source code and documentation are available under the
[MIT License](LICENSE). Third-party components and bundled fonts retain their
own licenses; see [NOTICE](NOTICE) and [LICENSES](LICENSES/).
