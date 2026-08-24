# Termish Agent Bridge

Termish Agent Bridge is the small, remote companion used by Termish's native
Agent UI. It is deliberately implemented with the Python standard library
only: no `pip`, virtualenv, HTTP server, or public listening port is required.

The source tree is packaged as a Python zipapp and uploaded by Termish over
SFTP. The app then opens `connect` through an authenticated raw SSH exec
channel and exchanges newline-delimited JSON with the background daemon.

```text
Termish -> SSH exec -> termish-agent.pyz connect -> Unix socket -> daemon
                                                              -> TUI agents
```

## Development

```bash
python3 -m unittest discover -s agentBridge/tests
python3 agentBridge/build.py
```

The build writes the app-bundled artifact to
`composeApp/src/commonMain/composeResources/files/termish-agent.pyz`.

Gradle builds the zipapp before Compose resources are copied, and `make test`
runs both the Bridge tests and the Kotlin desktop tests. CI uses the same tasks.

## Remote layout and lifecycle

Termish installs the artifact for the authenticated SSH user at:

```text
~/.local/share/termish-agent/
├── current/termish-agent.pyz
├── data/sessions.db
├── npm/                         # optional user-owned Agent CLI installs
└── runtime/{agent.sock,agent.pid,agent.log,agent.version}
```

The app compares the installed Bridge version with its bundled minimum and
offers an in-app update when needed. `restart` replaces the daemon without
requiring systemd or root. Runtime/data directories are mode `0700`, the Unix
socket and SQLite database are mode `0600`, and the daemon sets `umask 077`.

## Protocol

`connect` relays NDJSON between SSH stdin/stdout and the private Unix socket.
Protocol version 2 provides:

- `system.hello`
- `agents.list`, `agents.installPlan`, `agents.install`
- `sessions.list`, `sessions.create`, `sessions.get`, `sessions.rename`,
  `sessions.delete`
- `prompt.send` (including uploaded attachment references), `prompt.abort`
- turn-scoped normalized events for text/thinking deltas, tool start/end,
  errors, cancellation, settlement, installation output, and busy state;
  activity IDs, ordering, inputs, and timestamps preserve the execution timeline

Sessions, turn IDs, normalized activities, and messages are persisted in SQLite. The daemon owns the
Agent subprocess, so closing the SSH relay does not cancel an active turn.
Attachments are uploaded by Termish over SFTP into the selected workspace's
private `.termish/attachments/` directory; the Bridge persists their metadata
and adds relative paths to the Agent prompt.

Provider selection carries a local configuration ID separately from its adapter
type. API keys are supplied only for the active prompt and are never persisted
by the Bridge. DeepSeek currently supports Claude Code (Anthropic-compatible
endpoint), OpenCode, and Pi.

Adapters cover Codex, Claude Code, Gemini CLI, OpenCode, and Pi RPC. They retain
each Agent's existing permission configuration and never add an auto-approval
flag. Interactive approval requests are not yet part of protocol version 2;
headless Agent modes that cannot surface them must fail closed instead of being
silently approved. The resolver supports common user installs, including NVM,
and in-app npm installs never use `sudo`.
