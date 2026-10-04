use serde_json::{json, Value};
use std::{
    fs,
    io::{BufRead, BufReader, Write},
    os::unix::{fs::PermissionsExt, net::UnixStream},
    path::PathBuf,
    process::{Child, Command, Stdio},
    time::{Duration, Instant},
};
struct Server {
    dir: PathBuf,
    child: Child,
}
impl Drop for Server {
    fn drop(&mut self) {
        if let Ok(mut client) = UnixStream::connect(self.socket()) {
            let _ = client.write_all(b"{\"id\":0,\"method\":\"system.shutdown\"}\n");
            let end = Instant::now() + Duration::from_secs(5);
            while self.socket().exists() && Instant::now() < end {
                std::thread::sleep(Duration::from_millis(20));
            }
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
        let _ = fs::remove_dir_all(&self.dir);
    }
}
impl Server {
    fn start() -> Self {
        let dir = std::env::temp_dir().join(format!(
            "ta-{}-{}",
            std::process::id(),
            uuid::Uuid::new_v4().simple().to_string()[..6].to_owned()
        ));
        fs::create_dir_all(dir.join("bin")).unwrap();
        fs::create_dir_all(dir.join("home")).unwrap();
        let script = r##"#!/bin/sh
if [ "$1" = "login" ]; then echo 'Logged in'; exit 0; fi
if [ "$1" = "auth" ]; then echo '{"loggedIn":true}'; exit 0; fi
case "$(basename "$0")" in
codex)
  while IFS= read -r message; do
    case "$message" in
    *termish-initialize*) printf '%s\n' '{"id":"termish-initialize","result":{}}' ;;
    *termish-thread*) printf '%s\n' '{"id":"termish-thread","result":{"thread":{"id":"native-codex"}}}' ;;
    *termish-turn*) printf '%s\n' '{"id":77,"method":"item/commandExecution/requestApproval","params":{"command":"echo test","cwd":"/tmp","availableDecisions":["accept","decline","cancel"]}}' ;;
    *'"id":77'*)
      printf '%s\n' '{"method":"item/agentMessage/delta","params":{"itemId":"a","delta":"中文🙂"}}' '{"method":"item/started","params":{"item":{"id":"tool","type":"commandExecution","command":"echo test"}}}' '{"method":"item/completed","params":{"item":{"id":"tool","type":"commandExecution","aggregatedOutput":"done","exitCode":0}}}' '{"method":"turn/completed","params":{"turn":{"status":"completed"}}}'
      exit 0 ;;
    esac
  done ;;
claude)
  IFS= read -r prompt
  case "$prompt" in *wait*) sleep 30 ;; esac
  printf '%s\n' '{"type":"system","subtype":"init","session_id":"native-claude"}' '{"type":"assistant","message":{"content":[{"type":"thinking","thinking":"plan"},{"type":"text","text":"中文🙂"},{"type":"tool_use","id":"t","name":"read","input":{"file":"a"}}]}}' '{"type":"user","message":{"content":[{"type":"tool_result","tool_use_id":"t","content":"done"}]}}' '{"type":"result","session_id":"native-claude"}' ;;
gemini)
  printf '%s\n' '{"type":"init","session_id":"native-gemini"}' '{"type":"message","role":"assistant","content":"中文🙂","delta":true}' '{"type":"tool_use","tool_id":"t","tool_name":"read","parameters":{}}' '{"type":"tool_result","tool_id":"t","output":"done","status":"success"}' '{"type":"result","status":"success"}' ;;
opencode)
  printf '%s\n' '{"type":"reasoning","sessionID":"native-opencode","part":{"text":"plan"}}' '{"type":"text","sessionID":"native-opencode","part":{"text":"中文🙂"}}' '{"type":"tool_use","part":{"id":"t","tool":"read","state":{"input":{},"output":"done","status":"completed"}}}' ;;
pi)
  IFS= read -r state; IFS= read -r prompt
  printf '%s\n' '{"type":"response","id":"termish-state","success":true,"data":{"sessionFile":"native-pi"}}' '{"type":"response","id":"termish-prompt","success":true}' '{"type":"extension_ui_request","id":"confirm","method":"confirm","title":"Confirm"}'
  IFS= read -r approval
  printf '%s\n' '{"type":"message_update","assistantMessageEvent":{"type":"thinking_delta","delta":"plan"}}' '{"type":"message_update","assistantMessageEvent":{"type":"text_delta","delta":"中文🙂"}}' '{"type":"tool_execution_start","toolCallId":"t","toolName":"read","args":{}}' '{"type":"tool_execution_end","toolCallId":"t","toolName":"read","result":{"content":[{"type":"text","text":"done"}]},"isError":false}' '{"type":"agent_settled"}' ;;
esac
"##;
        for agent in ["codex", "claude", "gemini", "opencode", "pi"] {
            let p = dir.join("bin").join(agent);
            fs::write(&p, script).unwrap();
            fs::set_permissions(p, fs::Permissions::from_mode(0o700)).unwrap();
        }
        let child = Command::new(env!("CARGO_BIN_EXE_termish-agent"))
            .arg("serve")
            .env("TERMISH_AGENT_HOME", dir.join("bridge"))
            .env("HOME", dir.join("home"))
            .env(
                "PATH",
                format!("{}:/usr/bin:/bin", dir.join("bin").display()),
            )
            .env("GEMINI_API_KEY", "fixture")
            .stdin(Stdio::null())
            .stdout(Stdio::null())
            .stderr(Stdio::inherit())
            .spawn()
            .unwrap();
        let server = Self { dir, child };
        let end = Instant::now() + Duration::from_secs(5);
        while UnixStream::connect(server.socket()).is_err() {
            assert!(Instant::now() < end, "daemon did not start");
            std::thread::sleep(Duration::from_millis(20));
        }
        server
    }
    fn socket(&self) -> PathBuf {
        self.dir.join("bridge/runtime/rust/agent.sock")
    }
    fn client(&self) -> Client {
        let stream = UnixStream::connect(self.socket()).unwrap();
        stream
            .set_read_timeout(Some(Duration::from_secs(10)))
            .unwrap();
        Client {
            writer: stream.try_clone().unwrap(),
            reader: BufReader::new(stream),
            next: 0,
        }
    }
}
struct Client {
    writer: UnixStream,
    reader: BufReader<UnixStream>,
    next: u64,
}
impl Client {
    fn read(&mut self) -> Value {
        let mut line = String::new();
        assert!(
            self.reader.read_line(&mut line).unwrap() > 0,
            "unexpected EOF"
        );
        serde_json::from_str(&line).unwrap()
    }
    fn request(&mut self, method: &str, params: Value) -> Value {
        self.next += 1;
        let key = self.next;
        writeln!(
            self.writer,
            "{}",
            json!({"id":key,"method":method,"params":params})
        )
        .unwrap();
        loop {
            let response = self.read();
            if response["id"] == key {
                assert!(response["error"].is_null(), "{response}");
                return response["result"].clone();
            }
        }
    }
    fn wait_settled(&mut self, sid: &str) {
        let end = Instant::now() + Duration::from_secs(10);
        loop {
            assert!(Instant::now() < end);
            let v = self.read();
            if v["type"] == "busy" && v["sessionId"] == sid && v["busy"] == false {
                return;
            }
        }
    }
}
#[test]
fn all_five_adapters_persist_tools_and_resume_after_reconnect() {
    let server = Server::start();
    let mut client = server.client();
    assert_eq!(client.request("system.hello", json!({}))["runtime"], "rust");
    for agent in ["codex", "claude", "gemini", "opencode", "pi"] {
        let s = client.request(
            "sessions.create",
            json!({"agent":agent,"cwd":server.dir.to_string_lossy()}),
        );
        let sid = s["sessionId"].as_str().unwrap();
        client.request("prompt.send", json!({"sessionId":sid,"message":"test"}));
        if agent == "codex" || agent == "pi" {
            let approval = loop {
                let v = client.read();
                if v["event"]["type"] == "approval_request" {
                    break v["event"].clone();
                }
            };
            assert_eq!(approval["sessionId"], sid);
            drop(client);
            client = server.client();
            let recovered = client.request("sessions.get", json!({"sessionId":sid}));
            assert_eq!(recovered["waitingApproval"], true);
            let a = &recovered["approvals"][0];
            client.request(
                "approval.respond",
                json!({"sessionId":sid,"approvalId":a["approvalId"],"decision":"allow_once"}),
            );
        }
        client.wait_settled(sid);
        let history = client.request("sessions.get", json!({"sessionId":sid}));
        let messages = history["messages"].as_array().unwrap();
        assert!(
            messages
                .iter()
                .any(|m| m["role"] == "assistant" && m["text"] == "中文🙂"),
            "{agent}: {messages:?}"
        );
        assert!(
            messages.iter().any(|m| m["role"] == "tool"),
            "{agent}: {messages:?}"
        );
        let replay = client.request(
            "events.replay",
            json!({"sessionId":sid,"eventEpoch":history["eventEpoch"],"after":0}),
        );
        assert_eq!(replay["reset"], false);
        assert!(!replay["events"].as_array().unwrap().is_empty());
        let file = server
            .dir
            .join("bridge/data/sessions")
            .join(format!("{sid}.json"));
        let persisted: Value = serde_json::from_slice(&fs::read(file).unwrap()).unwrap();
        assert_eq!(
            persisted["resume_id"].as_str().unwrap(),
            format!("native-{agent}")
        );
    }
}
#[test]
fn cancellation_stops_process_group_and_history_pages() {
    let server = Server::start();
    let mut c = server.client();
    let s = c.request(
        "sessions.create",
        json!({"agent":"claude","cwd":server.dir.to_string_lossy()}),
    );
    let sid = &s["sessionId"];
    c.request("prompt.send", json!({"sessionId":sid,"message":"wait"}));
    c.request("prompt.abort", json!({"sessionId":sid}));
    let history = c.request("sessions.get", json!({"sessionId":sid,"messageLimit":1}));
    assert_eq!(history["busy"], false);
    assert_eq!(history["messages"].as_array().unwrap().len(), 1);
    assert_eq!(history["messages"][0]["role"], "user");
    c.request("sessions.delete", json!({"sessionId":sid}));
    assert!(c.request("sessions.list", json!({}))["sessions"]
        .as_array()
        .unwrap()
        .is_empty());
}
#[test]
fn rejects_oversized_input_and_invalid_params() {
    let server = Server::start();
    let mut c = server.client();
    writeln!(
        c.writer,
        "{}",
        json!({"id":1,"method":"system.hello","params":[]})
    )
    .unwrap();
    assert_eq!(c.read()["error"]["code"], "invalid_request");
    c.writer
        .write_all(&vec![b'x'; 8 * 1024 * 1024 + 1])
        .unwrap();
    assert_eq!(c.read()["error"]["code"], "invalid_request");
}
#[test]
fn native_import_reads_agent_history_without_old_bridge_database() {
    let server = Server::start();
    let cwd = server.dir.to_string_lossy();
    let root = server.dir.join("home/.codex/sessions");
    fs::create_dir_all(&root).unwrap();
    let records = [
        json!({"type":"session_meta","timestamp":"2026-10-04T00:00:00Z","payload":{"id":"native-history","cwd":cwd}}),
        json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"# AGENTS.md instructions\n\n<environment_context>"}]}}),
        json!({"type":"response_item","payload":{"type":"message","role":"user","content":[{"type":"input_text","text":"actual question"}]}}),
        json!({"type":"response_item","payload":{"type":"message","role":"assistant","content":[{"type":"output_text","text":"answer"}]}}),
    ];
    fs::write(
        root.join("rollout-fixture.jsonl"),
        records.iter().map(|v| format!("{v}\n")).collect::<String>(),
    )
    .unwrap();
    let mut c = server.client();
    let list = c.request("sessions.nativeList", json!({}));
    assert!(list["sessions"]
        .as_array()
        .unwrap()
        .iter()
        .any(|v| v["nativeId"] == "native-history"));
    let session = c.request(
        "sessions.nativeImport",
        json!({"agent":"codex","nativeId":"native-history"}),
    );
    let page = c.request("sessions.get", json!({"sessionId":session["sessionId"]}));
    assert_eq!(page["messages"].as_array().unwrap().len(), 2);
    assert_eq!(page["messages"][0]["text"], "actual question");
    let again = c.request(
        "sessions.nativeImport",
        json!({"agent":"codex","nativeId":"native-history"}),
    );
    assert_eq!(again["sessionId"], session["sessionId"]);
}
#[test]
fn provider_credentials_are_ephemeral_and_attachment_traversal_is_ignored() {
    let server = Server::start();
    let mut c = server.client();
    let provider = json!({"id":"fixture-provider","type":"anthropic","apiKey":"do-not-persist-key","baseUrl":"https://example.com"});
    let session = c.request(
        "sessions.create",
        json!({"agent":"claude","cwd":server.dir.to_string_lossy(),"provider":provider}),
    );
    let sid = session["sessionId"].as_str().unwrap();
    c.request("prompt.send",json!({"sessionId":sid,"provider":provider,"message":"test","attachments":[{"name":"secret","path":"../../outside","size":4}]}));
    c.wait_settled(sid);
    let bytes = fs::read_to_string(
        server
            .dir
            .join("bridge/data/sessions")
            .join(format!("{sid}.json")),
    )
    .unwrap();
    assert!(!bytes.contains("do-not-persist-key"));
    let history = c.request("sessions.get", json!({"sessionId":sid}));
    assert!(history["messages"][0]["attachments"]
        .as_array()
        .unwrap()
        .is_empty());
}

#[test]
fn native_restart_and_stdio_relay_keep_persisted_sessions() {
    let server = Server::start();
    let mut client = server.client();
    let created = client.request(
        "sessions.create",
        json!({"agent":"claude","cwd":server.dir}),
    );
    let session = created["sessionId"].as_str().unwrap();
    client.request(
        "prompt.send",
        json!({"sessionId":session,"message":"hello"}),
    );
    client.wait_settled(session);
    drop(client);
    let restart = Command::new(env!("CARGO_BIN_EXE_termish-agent"))
        .arg("restart")
        .env("TERMISH_AGENT_HOME", server.dir.join("bridge"))
        .env("HOME", server.dir.join("home"))
        .output()
        .unwrap();
    assert!(
        restart.status.success(),
        "{}",
        String::from_utf8_lossy(&restart.stderr)
    );
    let status: Value = serde_json::from_slice(&restart.stdout).unwrap();
    assert_eq!(status["daemonVersion"], "0.9.0");
    let mut relay = Command::new(env!("CARGO_BIN_EXE_termish-agent"))
        .arg("connect")
        .env("TERMISH_AGENT_HOME", server.dir.join("bridge"))
        .env("HOME", server.dir.join("home"))
        .stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::inherit())
        .spawn()
        .unwrap();
    let mut input = relay.stdin.take().unwrap();
    writeln!(
        input,
        "{}",
        json!({"id":1,"method":"sessions.get","params":{"sessionId":session}})
    )
    .unwrap();
    let mut reader = BufReader::new(relay.stdout.take().unwrap());
    let mut line = String::new();
    reader.read_line(&mut line).unwrap();
    let result: Value = serde_json::from_str(&line).unwrap();
    assert_eq!(result["result"]["sessionId"], session);
    assert!(result["result"]["messages"]
        .as_array()
        .unwrap()
        .iter()
        .any(|m| m["text"] == "中文🙂"));
    drop(input);
    assert!(relay.wait().unwrap().success());
    // Closing raw SSH stdio only disconnects the relay; the daemon is still owned by the host.
    let mut client = server.client();
    assert_eq!(
        client.request("sessions.list", json!({}))["sessions"]
            .as_array()
            .unwrap()
            .len(),
        1
    );
    client.request("system.shutdown", json!({}));
    let end = Instant::now() + Duration::from_secs(5);
    while server.socket().exists() {
        assert!(Instant::now() < end);
        std::thread::sleep(Duration::from_millis(20));
    }
}

#[test]
fn cli_install_streams_stderr_and_bounds_long_output_without_sudo() {
    let server = Server::start();
    let npm = server.dir.join("bin/npm");
    fs::write(&npm, "#!/bin/sh\nsleep .1\ncase \"$*\" in *'install --prefix '*'--no-fund --no-audit') ;; *) exit 2 ;; esac\nprintf 'installing\\n'\nprintf '安装进度🙂\\n' >&2\ndd if=/dev/zero bs=1024 count=1024 2>/dev/null | tr '\\000' x\nprintf '\\n'\n").unwrap();
    fs::set_permissions(&npm, fs::Permissions::from_mode(0o700)).unwrap();
    let mut client = server.client();
    assert_eq!(
        client.request("agents.installPlan", json!({"agent":"claude"}))["requiresSudo"],
        false
    );
    client.request("agents.install", json!({"agent":"claude"}));
    let mut outputs = String::new();
    loop {
        let message = client.read();
        let event = &message["event"];
        if event["type"] == "install_complete" {
            break;
        }
        assert_ne!(event["type"], "install_error", "{message}");
        if event["type"] == "install_output" {
            let text = event["text"].as_str().unwrap();
            assert!(text.len() <= 65560);
            outputs.push_str(text);
        }
    }
    assert!(outputs.contains("安装进度🙂"));
    assert!(outputs.contains("[output truncated]"));
}
