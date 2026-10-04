use crate::{
    store::{self, Run, Shared},
    util::*,
};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet},
    env,
    path::PathBuf,
    process::Stdio,
    time::Duration,
};
use tokio::{
    io::{AsyncReadExt, AsyncWriteExt, BufReader},
    process::{Child, Command},
    sync::watch,
};
const AGENTS: [(&str, &str, &str); 5] = [
    ("codex", "Codex", "@openai/codex"),
    ("claude", "Claude Code", "@anthropic-ai/claude-code"),
    ("gemini", "Gemini CLI", "@google/gemini-cli"),
    ("opencode", "OpenCode", "opencode-ai"),
    ("pi", "Pi", "@earendil-works/pi-coding-agent"),
];
fn dirs() -> Vec<PathBuf> {
    let h = home();
    let mut dirs = vec![
        bridge_home().join("npm/node_modules/.bin"),
        h.join(".local/bin"),
        h.join(".npm-global/bin"),
        h.join(".bun/bin"),
        h.join(".pi/agent/bin"),
    ];
    let mut nvm: Vec<_> = std::fs::read_dir(h.join(".nvm/versions/node"))
        .into_iter()
        .flatten()
        .flatten()
        .map(|e| e.path().join("bin"))
        .collect();
    nvm.sort_by(|a, b| b.cmp(a));
    dirs.extend(nvm);
    dirs.extend([
        PathBuf::from("/opt/homebrew/bin"),
        PathBuf::from("/usr/local/bin"),
    ]);
    dirs
}
pub fn resolve(name: &str) -> Option<PathBuf> {
    if name.contains('/') || name.is_empty() {
        return None;
    }
    let paths = env::var_os("PATH")
        .map(|p| env::split_paths(&p).collect::<Vec<_>>())
        .unwrap_or_default();
    paths
        .into_iter()
        .chain(dirs())
        .map(|p| p.join(name))
        .find(|p| {
            p.is_file() && {
                use std::os::unix::fs::PermissionsExt;
                p.metadata()
                    .is_ok_and(|m| m.permissions().mode() & 0o111 != 0)
            }
        })
}
fn command(name: &str) -> Result<Command> {
    let binary = resolve(name).ok_or_else(|| format!("{name} is not installed"))?;
    let mut c = Command::new(binary);
    let mut paths = dirs();
    if let Some(p) = env::var_os("PATH") {
        paths.extend(env::split_paths(&p));
    }
    c.env("PATH", env::join_paths(paths)?);
    Ok(c)
}
pub fn provider_supported(agent: &str, p: &Value) -> bool {
    match text(p, "type") {
        "" => true,
        "anthropic" => agent == "claude",
        "openai" => {
            ["opencode", "pi"].contains(&agent)
                || (agent == "claude" && !text(p, "anthropicBaseUrl").is_empty())
        }
        "deepseek" => ["claude", "opencode", "pi"].contains(&agent),
        _ => agent == "claude" && !text(p, "anthropicBaseUrl").is_empty(),
    }
}
fn pi_provider(p: &Value) -> &str {
    if !text(p, "piProvider").is_empty() {
        text(p, "piProvider")
    } else if text(p, "type") == "deepseek" || text(p, "id") == "deepseek" {
        "deepseek"
    } else {
        ""
    }
}
fn environment(c: &mut Command, run: &Run) {
    let p = &run.provider;
    let key = text(p, "apiKey");
    if key.is_empty() {
        return;
    }
    match run.agent.as_str() {
        "codex" | "opencode" => {
            c.env("OPENAI_API_KEY", key);
            if !text(p, "baseUrl").is_empty() {
                c.env("OPENAI_BASE_URL", text(p, "baseUrl"));
            }
        }
        "claude" => {
            let base = if !text(p, "anthropicBaseUrl").is_empty() {
                text(p, "anthropicBaseUrl").to_owned()
            } else if text(p, "type") == "anthropic" {
                if text(p, "baseUrl").is_empty() {
                    "https://api.anthropic.com".into()
                } else {
                    text(p, "baseUrl").into()
                }
            } else if text(p, "type") == "deepseek" || text(p, "id") == "deepseek" {
                format!(
                    "{}/anthropic",
                    if text(p, "baseUrl").is_empty() {
                        "https://api.deepseek.com"
                    } else {
                        text(p, "baseUrl").trim_end_matches('/')
                    }
                )
            } else {
                String::new()
            };
            if !base.is_empty() {
                c.env("ANTHROPIC_BASE_URL", base)
                    .env("ANTHROPIC_AUTH_TOKEN", key)
                    .env("CLAUDE_CODE_SUBPROCESS_ENV_SCRUB", "1")
                    .env("CLAUDE_CODE_EFFORT_LEVEL", "max");
                if let Some(m) = &run.model {
                    c.env("ANTHROPIC_MODEL", m);
                }
            }
        }
        "pi" => {
            let provider = pi_provider(p);
            if !provider.is_empty() {
                c.env(
                    format!(
                        "{}_API_KEY",
                        provider.to_uppercase().replace(['-', ' '], "_")
                    ),
                    key,
                );
            }
        }
        _ => (),
    }
}
pub fn list() -> Vec<Value> {
    AGENTS.iter().map(|(id,label,_)|json!({"id":id,"label":label,"available":resolve(id).is_some(),"supported":true})).collect()
}
pub fn install_plan(agent: &str) -> Result<Value> {
    let (_, _, package) = AGENTS
        .iter()
        .find(|(id, _, _)| *id == agent)
        .ok_or("unknown agent")?;
    let target = bridge_home().join("npm");
    Ok(
        json!({"agent":agent,"package":package,"available":resolve(agent).is_some(),"canInstall":resolve("npm").is_some(),"requiresSudo":false,"target":target,"commandPreview":format!("npm install --prefix {} {package}",target.display())}),
    )
}
async fn stderr_tail(mut stream: tokio::process::ChildStderr) -> String {
    let mut tail = Vec::new();
    let mut buf = [0; 4096];
    loop {
        match stream.read(&mut buf).await {
            Ok(0) | Err(_) => break,
            Ok(n) => {
                tail.extend_from_slice(&buf[..n]);
                if tail.len() > 65536 {
                    tail.drain(..tail.len() - 65536);
                }
            }
        }
    }
    String::from_utf8_lossy(&tail).into_owned()
}
async fn terminate(child: &mut Child, group: Option<u32>) {
    // Child::id becomes None once the leader is reaped, but its descendants may
    // still own stdout/stderr. Retain the process group from spawn through cleanup.
    if let Some(pid) = group {
        unsafe {
            libc::kill(-(pid as i32), libc::SIGTERM);
        }
    }
    if tokio::time::timeout(Duration::from_secs(3), child.wait())
        .await
        .is_err()
    {
        let _ = child.kill().await;
        let _ = child.wait().await;
    }
    if let Some(pid) = group {
        unsafe {
            libc::kill(-(pid as i32), libc::SIGKILL);
        }
    }
}
fn spawn(c: &mut Command) -> Result<Child> {
    spawn_with_output(c, false)
}
fn spawn_with_output(c: &mut Command, merge_stderr: bool) -> Result<Child> {
    c.stdin(Stdio::piped())
        .stdout(Stdio::piped())
        .stderr(Stdio::piped())
        .kill_on_drop(true);
    unsafe {
        c.pre_exec(move || {
            if libc::setsid() < 0 {
                return Err(std::io::Error::last_os_error());
            }
            if merge_stderr && libc::dup2(libc::STDOUT_FILENO, libc::STDERR_FILENO) < 0 {
                return Err(std::io::Error::last_os_error());
            }
            Ok(())
        });
    }
    Ok(c.spawn()?)
}
pub async fn bounded_output(
    c: &mut Command,
    timeout: u64,
    max: usize,
) -> Result<(bool, String, String)> {
    let mut child = spawn(c)?;
    let group = child.id();
    drop(child.stdin.take());
    let mut stdout = child.stdout.take().unwrap();
    let stderr = tokio::spawn(stderr_tail(child.stderr.take().unwrap()));
    let job = async {
        let mut bytes = Vec::new();
        let mut buf = [0; 8192];
        loop {
            let n = stdout.read(&mut buf).await?;
            if n == 0 {
                break;
            }
            if bytes.len() + n > max {
                return Err::<_, Box<dyn std::error::Error + Send + Sync>>(
                    "command output too large".into(),
                );
            }
            bytes.extend_from_slice(&buf[..n]);
        }
        let status = child.wait().await?;
        Ok((
            status.success(),
            String::from_utf8_lossy(&bytes).into_owned(),
        ))
    };
    let result = tokio::time::timeout(Duration::from_secs(timeout), job).await;
    let result = match result {
        Ok(r) => r,
        Err(_) => Err("command timed out".into()),
    };
    if result.is_err() {
        terminate(&mut child, group).await;
    }
    let err = stderr.await.unwrap_or_default();
    result.map(|(ok, out)| (ok, out, err))
}
pub async fn readiness(run: &Run) -> Result<Value> {
    if resolve(&run.agent).is_none() {
        return Err(format!("{} is not installed", run.agent).into());
    }
    if !text(&run.provider, "id").is_empty() && !text(&run.provider, "apiKey").is_empty() {
        return Ok(json!({"ready":true,"status":"ready"}));
    }
    if run.agent == "gemini" {
        let configured = std::fs::read(home().join(".gemini/settings.json"))
            .ok()
            .and_then(|b| serde_json::from_slice::<Value>(&b).ok())
            .unwrap_or(Value::Null);
        if env::var("GEMINI_API_KEY").is_ok_and(|s| !s.is_empty())
            || env::var("GOOGLE_API_KEY").is_ok_and(|s| !s.is_empty())
            || env::var("GOOGLE_APPLICATION_CREDENTIALS").is_ok_and(|s| expand(&s).is_file())
            || configured["security"]["auth"]["selectedType"]
                .as_str()
                .is_some_and(|s| !s.is_empty())
            || !text(&configured, "selectedAuthType").is_empty()
        {
            return Ok(json!({"ready":true,"status":"ready"}));
        }
        return Err("Gemini CLI has no authentication method configured".into());
    }
    let args = match run.agent.as_str() {
        "codex" => vec!["login", "status"],
        "claude" => vec!["auth", "status", "--json"],
        "opencode" => vec!["auth", "list"],
        "pi" => vec![
            "auth",
            "check",
            "--provider",
            if pi_provider(&run.provider).is_empty() {
                "google"
            } else {
                pi_provider(&run.provider)
            },
            "--json",
            "--no-refresh",
        ],
        _ => return Err("unsupported agent".into()),
    };
    let mut c = command(&run.agent)?;
    c.args(args).current_dir(&run.cwd);
    environment(&mut c, run);
    let (ok, out, err) = bounded_output(&mut c, 8, 65536).await?;
    let details = format!("{out}\n{err}");
    let lowered = details.to_lowercase();
    let ready = match run.agent.as_str() {
        "codex" => ok && lowered.contains("logged in"),
        "claude" => {
            ok && serde_json::from_str::<Value>(&out)
                .ok()
                .is_none_or(|v| v["loggedIn"] != false)
        }
        "opencode" => {
            ok && !details.trim().is_empty()
                && !lowered.contains("0 credentials")
                && !lowered.contains("no credentials")
        }
        _ => ok,
    };
    if ready {
        Ok(json!({"ready":true,"status":"ready"}))
    } else if run.agent == "claude"
        && ["unknown", "unrecognized", "invalid option"]
            .iter()
            .any(|s| lowered.contains(s))
    {
        Ok(json!({"ready":true,"status":"unknown"}))
    } else {
        Err(format!(
            "{} is not signed in. {}",
            run.agent,
            truncate(details.trim(), 1000)
        )
        .into())
    }
}
pub async fn install(shared: &Shared, agent: &str) -> Result<()> {
    let plan = install_plan(agent)?;
    let target = bridge_home().join("npm");
    private_dir(&target)?;
    let mut c = command("npm")?;
    c.args(["install", "--prefix"]).arg(target).args([
        text(&plan, "package"),
        "--no-fund",
        "--no-audit",
    ]);
    let mut child = spawn_with_output(&mut c, true)?;
    let group = child.id();
    drop(child.stdin.take());
    let stderr = tokio::spawn(stderr_tail(child.stderr.take().unwrap()));
    let mut reader = BufReader::new(child.stdout.take().unwrap());
    use tokio::io::AsyncBufReadExt;
    let mut tail = String::new();
    let result = match tokio::time::timeout(Duration::from_secs(600), async {
        loop {
            let mut raw = Vec::new();
            let mut truncated = false;
            loop {
                let available = reader.fill_buf().await?;
                if available.is_empty() { break; }
                let newline = available.iter().position(|b| *b == b'\n');
                let consumed = newline.map_or(available.len(), |n| n + 1);
                let kept = consumed.min(65536 - raw.len());
                raw.extend_from_slice(&available[..kept]);
                truncated |= kept < consumed;
                reader.consume(consumed);
                if newline.is_some() { break; }
            }
            if raw.is_empty() { break; }
            let mut output = String::from_utf8_lossy(&raw).into_owned();
            if truncated { output.push_str("\n[output truncated]\n"); }
            tail = truncate(&output, 1000);
            let _ = shared.lock().await.events.send(
                json!({"type":"event","event":{"type":"install_output","agent":agent,"text":output}}),
            );
        }
        if !child.wait().await?.success() { return Err::<(), Box<dyn std::error::Error + Send + Sync>>(format!("npm failed: {tail}").into()); }
        Ok(())
    }).await { Ok(result) => result, Err(_) => Err("npm install timed out".into()) };
    if result.is_err() {
        terminate(&mut child, group).await;
    }
    let _ = stderr.await;
    result?;
    let _ = shared
        .lock()
        .await
        .events
        .send(json!({"type":"event","event":{"type":"install_complete","agent":agent}}));
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn cleanup_reaps_descendants_after_leader_has_exited() {
        let mut command = Command::new("/bin/sh");
        command.args(["-c", "trap '' TERM; sleep 30 & echo ready; exit 0"]);
        let mut child = spawn(&mut command).unwrap();
        let group = child.id();
        drop(child.stdin.take());
        let mut output = BufReader::new(child.stdout.take().unwrap());
        use tokio::io::AsyncBufReadExt;
        let mut ready = String::new();
        output.read_line(&mut ready).await.unwrap();
        assert_eq!(ready, "ready\n");
        assert!(child.wait().await.unwrap().success());
        assert!(child.id().is_none());
        terminate(&mut child, group).await;
        let mut remaining = Vec::new();
        tokio::time::timeout(Duration::from_secs(2), output.read_to_end(&mut remaining))
            .await
            .expect("descendant still holds the output pipe")
            .unwrap();
    }
}
fn curl_quote(s: &str) -> Result<String> {
    if s.contains(['\r', '\n', '\0']) {
        return Err("invalid HTTP configuration".into());
    }
    Ok(format!(
        "\"{}\"",
        s.replace('\\', "\\\\").replace('"', "\\\"")
    ))
}
pub async fn fetch_models(p: &Value) -> Result<Value> {
    let base = text(p, "baseUrl").trim().trim_end_matches('/');
    if !(base.starts_with("https://") || base.starts_with("http://")) {
        return Err("invalid Base URL".into());
    }
    let key = text(p, "apiKey");
    if key.is_empty() {
        return Err("API key is missing".into());
    }
    let host = base.split("://").nth(1).unwrap_or("");
    let url = format!(
        "{base}{}",
        if host.contains('/') {
            "/models"
        } else {
            "/v1/models"
        }
    );
    let mut config = format!("url = {}\n", curl_quote(&url)?);
    if text(p, "type") == "anthropic" {
        config.push_str(&format!(
            "header = {}\nheader = \"anthropic-version: 2023-06-01\"\n",
            curl_quote(&format!("x-api-key: {key}"))?
        ));
    } else {
        config.push_str(&format!(
            "header = {}\n",
            curl_quote(&format!("Authorization: Bearer {key}"))?
        ));
    }
    let mut c = command("curl")?;
    c.args([
        "--silent",
        "--show-error",
        "--fail",
        "--max-time",
        "15",
        "--max-filesize",
        "16777216",
        "--proto",
        "=http,https",
        "--config",
        "-",
    ]);
    let mut child = spawn(&mut c)?;
    let group = child.id();
    let mut input = child.stdin.take().unwrap();
    input.write_all(config.as_bytes()).await?;
    drop(input);
    let stderr = tokio::spawn(stderr_tail(child.stderr.take().unwrap()));
    let mut out = Vec::new();
    let result = tokio::time::timeout(Duration::from_secs(17), async {
        child
            .stdout
            .take()
            .unwrap()
            .take(16777217)
            .read_to_end(&mut out)
            .await?;
        if out.len() > 16777216 {
            return Err::<_, Box<dyn std::error::Error + Send + Sync>>(
                "model response too large".into(),
            );
        }
        if !child.wait().await?.success() {
            return Err("model request failed".into());
        }
        Ok(())
    })
    .await;
    if !matches!(result, Ok(Ok(()))) {
        terminate(&mut child, group).await;
    }
    let _ = stderr.await;
    result.map_err(|_| "model request timed out")??;
    let value: Value = serde_json::from_slice(&out)?;
    let models: Vec<_> = value["data"]
        .as_array()
        .into_iter()
        .flatten()
        .filter_map(|v| v["id"].as_str())
        .filter(|s| !s.is_empty())
        .collect();
    if models.is_empty() {
        return Err("response contains no models (data[].id)".into());
    }
    Ok(json!({"models":models}))
}
#[derive(Default)]
struct Normalizer {
    tools: BTreeMap<String, String>,
    answers: BTreeSet<String>,
    reasoning: BTreeSet<String>,
    thinking: Vec<String>,
    saw_thinking: bool,
    prompt_accepted: bool,
}
impl Normalizer {
    fn events(&mut self, run: &mut Run, m: &Value) -> Vec<Value> {
        let mut out = Vec::new();
        let kind = text(m, "type");
        let mut emit = |v| out.push(v);
        match run.agent.as_str() {
            "codex" => {
                let method = text(m, "method");
                let p = &m["params"];
                match method {
                    "item/agentMessage/delta" => {
                        self.answers.insert(text(p, "itemId").into());
                        emit(json!({"type":"delta","text":text(p,"delta")}));
                    }
                    "item/reasoning/summaryTextDelta" | "item/reasoning/textDelta" => {
                        if self.reasoning.insert(text(p, "itemId").into()) {
                            emit(json!({"type":"thinking_start"}));
                        }
                        emit(json!({"type":"thinking_delta","text":text(p,"delta")}));
                    }
                    "item/started" | "item/completed" => {
                        let item = &p["item"];
                        let completed = method == "item/completed";
                        match text(item, "type") {
                            "commandExecution" | "fileChange" => {
                                let command = text(item, "type") == "commandExecution";
                                let name = if command { "bash" } else { "file_change" };
                                let detail = if command {
                                    if completed {
                                        text(item, "aggregatedOutput").to_owned()
                                    } else {
                                        text(item, "command").into()
                                    }
                                } else {
                                    item["changes"].to_string()
                                };
                                if completed {
                                    emit(
                                        json!({"type":"tool_end","name":name,"output":truncate(&detail,4000),"isError":if command{item["exitCode"].as_i64().is_some_and(|n|n!=0)}else{["failed","declined"].contains(&text(item,"status"))},"toolId":item["id"]}),
                                    );
                                } else {
                                    emit(
                                        json!({"type":"tool_start","name":name,"args":truncate(&detail,if command{1000}else{4000}),"toolId":item["id"]}),
                                    );
                                }
                            }
                            "agentMessage"
                                if completed && !self.answers.contains(text(item, "id")) =>
                            {
                                emit(
                                    json!({"type":"assistant_message","text":text(item,"text"),"toolCalls":[]}),
                                )
                            }
                            "reasoning"
                                if completed && !self.reasoning.contains(text(item, "id")) =>
                            {
                                let body = item["summary"]
                                    .as_array()
                                    .map(|a| {
                                        a.iter()
                                            .map(|v| v.as_str().unwrap_or(""))
                                            .collect::<Vec<_>>()
                                            .join("\n")
                                    })
                                    .unwrap_or_else(|| text(item, "summary").into());
                                if !body.is_empty() {
                                    emit(json!({"type":"thinking","text":body}));
                                }
                            }
                            _ => (),
                        }
                    }
                    "error" => emit(json!({"type":"error","message":text(&p["error"],"message")})),
                    "turn/completed" => {
                        let turn = &p["turn"];
                        if text(turn, "status") == "failed" {
                            emit(json!({"type":"error","message":text(&turn["error"],"message")}));
                        }
                        if text(turn, "status") == "interrupted" {
                            emit(json!({"type":"cancelled"}));
                        }
                        emit(json!({"type":"settled"}));
                    }
                    _ => (),
                }
            }
            "claude" => match kind {
                "system" | "result" => {
                    if !text(m, "session_id").is_empty() {
                        run.resume_id = Some(text(m, "session_id").into());
                    }
                    if kind == "result" {
                        if m["is_error"] == true {
                            emit(json!({"type":"error","message":text(m,"result")}));
                        }
                        emit(json!({"type":"settled"}));
                    }
                }
                "stream_event" => {
                    let d = &m["event"]["delta"];
                    match text(d, "type") {
                        "text_delta" => emit(json!({"type":"delta","text":text(d,"text")})),
                        "thinking_delta" => {
                            emit(json!({"type":"thinking_delta","text":text(d,"thinking")}))
                        }
                        _ => (),
                    }
                }
                "assistant" => {
                    let content = &m["message"]["content"];
                    if let Some(body) = content.as_str() {
                        emit(json!({"type":"assistant_message","text":body,"toolCalls":[]}));
                    }
                    for b in content.as_array().into_iter().flatten() {
                        match text(b, "type") {
                            "thinking" => {
                                emit(json!({"type":"thinking","text":text(b,"thinking")}))
                            }
                            "text" => emit(
                                json!({"type":"assistant_message","text":text(b,"text"),"toolCalls":[]}),
                            ),
                            "tool_use" => {
                                self.tools
                                    .insert(text(b, "id").into(), text(b, "name").into());
                                emit(
                                    json!({"type":"tool_start","name":text(b,"name"),"args":truncate(&b["input"].to_string(),1000),"toolId":b["id"]}),
                                );
                            }
                            _ => (),
                        }
                    }
                }
                "user" => {
                    for b in m["message"]["content"].as_array().into_iter().flatten() {
                        if text(b, "type") == "tool_result" {
                            let body = blocks(&b["content"], "text", "text");
                            emit(
                                json!({"type":"tool_end","name":self.tools.get(text(b,"tool_use_id")).map(String::as_str).unwrap_or("tool"),"output":truncate(&body,4000),"isError":b["is_error"]==true,"toolId":b["tool_use_id"]}),
                            );
                        }
                    }
                }
                "error" => emit(json!({"type":"error","message":text(&m["error"],"message")})),
                _ => (),
            },
            "gemini" => match kind {
                "init" => {
                    if !text(m, "session_id").is_empty() {
                        run.resume_id = Some(text(m, "session_id").into());
                    }
                }
                "message" if text(m, "role") == "assistant" => emit(
                    json!({"type":if m["delta"]==true{"delta"}else{"assistant_message"},"text":text(m,"content"),"toolCalls":[]}),
                ),
                "tool_use" => {
                    self.tools
                        .insert(text(m, "tool_id").into(), text(m, "tool_name").into());
                    emit(
                        json!({"type":"tool_start","name":text(m,"tool_name"),"args":truncate(&m["parameters"].to_string(),1000),"toolId":m["tool_id"]}),
                    );
                }
                "tool_result" => emit(
                    json!({"type":"tool_end","name":self.tools.get(text(m,"tool_id")).map(String::as_str).unwrap_or("tool"),"output":truncate(text(m,"output"),4000),"isError":text(m,"status")=="error","toolId":m["tool_id"]}),
                ),
                "result" => {
                    if text(m, "status") != "success" {
                        emit(json!({"type":"error","message":text(&m["error"],"message")}));
                    }
                    emit(json!({"type":"settled"}));
                }
                "error" => emit(json!({"type":"error","message":text(m,"message")})),
                _ => (),
            },
            "opencode" => {
                if !text(m, "sessionID").is_empty() {
                    run.resume_id = Some(text(m, "sessionID").into());
                }
                let part = &m["part"];
                if kind == "reasoning" {
                    self.thinking.push(text(part, "text").into());
                } else {
                    if !self.thinking.is_empty() {
                        emit(json!({"type":"thinking","text":self.thinking.join("\n\n")}));
                        self.thinking.clear();
                    }
                    match kind {
                        "text" => emit(json!({"type":"delta","text":text(part,"text")})),
                        "tool_use" => {
                            let state = &part["state"];
                            emit(
                                json!({"type":"tool_start","name":text(part,"tool"),"args":truncate(&state["input"].to_string(),1000),"toolId":part["id"]}),
                            );
                            emit(
                                json!({"type":"tool_end","name":text(part,"tool"),"output":truncate(&state["output"].to_string(),4000),"isError":text(state,"status")=="error","toolId":part["id"]}),
                            );
                        }
                        "error" => emit(json!({"type":"error","message":m["error"].to_string()})),
                        _ => (),
                    }
                }
            }
            "pi" => match kind {
                "response" => {
                    if text(m, "id") == "termish-state"
                        && m["success"] == true
                        && !text(&m["data"], "sessionFile").is_empty()
                    {
                        run.resume_id = Some(text(&m["data"], "sessionFile").into());
                    }
                    if text(m, "id") == "termish-prompt" {
                        self.prompt_accepted = m["success"] == true;
                        if !self.prompt_accepted {
                            emit(json!({"type":"error","message":text(m,"error")}));
                            emit(json!({"type":"settled"}));
                        }
                    }
                }
                "message_update" => {
                    let e = &m["assistantMessageEvent"];
                    match text(e, "type") {
                        "text_delta" => emit(json!({"type":"delta","text":text(e,"delta")})),
                        "thinking_start" => {
                            self.saw_thinking = true;
                            emit(json!({"type":"thinking_start"}));
                        }
                        "thinking_delta" => {
                            self.saw_thinking = true;
                            emit(json!({"type":"thinking_delta","text":text(e,"delta")}));
                        }
                        _ => (),
                    }
                }
                "message_end" if text(&m["message"], "role") == "assistant" => {
                    let content = &m["message"]["content"];
                    let mut thinking = blocks(content, "thinking", "thinking");
                    if thinking.is_empty() {
                        thinking = blocks(content, "thinking", "text");
                    }
                    if !thinking.is_empty() && !self.saw_thinking {
                        emit(json!({"type":"thinking","text":thinking}));
                    }
                    let body = blocks(content, "text", "text");
                    if !body.is_empty() {
                        emit(json!({"type":"assistant_message","text":body}));
                    }
                }
                "tool_execution_start" => emit(
                    json!({"type":"tool_start","name":text(m,"toolName"),"args":truncate(&m["args"].to_string(),1000),"toolId":m["toolCallId"]}),
                ),
                "tool_execution_end" => emit(
                    json!({"type":"tool_end","name":text(m,"toolName"),"output":truncate(&blocks(&m["result"]["content"],"text","text"),4000),"isError":m["isError"]==true,"toolId":m["toolCallId"]}),
                ),
                "extension_error" => emit(json!({"type":"error","message":text(m,"error")})),
                "agent_settled" => emit(json!({"type":"settled"})),
                _ => (),
            },
            _ => (),
        }
        out
    }
}
async fn codex_approval(
    shared: &Shared,
    sid: &str,
    run: &Run,
    m: &Value,
    cancel: &mut watch::Receiver<bool>,
    secret: &[String],
) -> Result<Value> {
    let method = text(m, "method");
    let p = &m["params"];
    if method == "currentTime/read" {
        return Ok(json!({"currentTimeAt":now()/1000}));
    }
    if method == "mcpServer/elicitation/request" {
        return Ok(json!({"action":"decline"}));
    }
    if method == "item/tool/requestUserInput" {
        let mut answers = json!({});
        let timeout = p["autoResolutionMs"].as_u64().filter(|n| *n > 0);
        let start = tokio::time::Instant::now();
        for q in p["questions"].as_array().into_iter().flatten() {
            if text(q, "id").is_empty() {
                continue;
            }
            let labels: Vec<_> = q["options"]
                .as_array()
                .into_iter()
                .flatten()
                .filter_map(|o| o["label"].as_str())
                .collect();
            let details = q["options"]
                .as_array()
                .into_iter()
                .flatten()
                .map(|o| format!("{} — {}", text(o, "label"), text(o, "description")))
                .collect::<Vec<_>>()
                .join("\n");
            let remaining = timeout.map(|n| n.saturating_sub(start.elapsed().as_millis() as u64));
            if remaining == Some(0) {
                break;
            }
            let reply=store::approval(shared,sid,json!({"kind":if labels.is_empty(){"input"}else{"select"},"title":text(q,"header"),"message":text(q,"question"),"options":labels,"details":details,"secret":q["isSecret"]==true,"allowCustom":q["isOther"]==true,"timeoutMs":remaining.unwrap_or(0)}),cancel,secret).await?;
            if text(&reply, "decision") == "cancel" {
                break;
            }
            answers[text(q, "id")] =
                json!({"answers":reply["value"].as_str().map(|s|vec![s]).unwrap_or_default()});
        }
        return Ok(json!({"answers":answers}));
    }
    let legacy = ["execCommandApproval", "applyPatchApproval"].contains(&method);
    let (kind, title, details) = match method {
        "item/commandExecution/requestApproval" | "execCommandApproval" => (
            if p["networkApprovalContext"].is_object() {
                "network"
            } else {
                "command"
            },
            "Approve command",
            p["additionalPermissions"].to_string(),
        ),
        "item/fileChange/requestApproval" | "applyPatchApproval" => (
            "file_change",
            "Approve file changes",
            p["fileChanges"].to_string(),
        ),
        "item/permissions/requestApproval" => (
            "permissions",
            "Approve permissions",
            p["permissions"].to_string(),
        ),
        "item/tool/call" => return Ok(json!({"success":false,"contentItems":[]})),
        _ => return Ok(json!({"decision":"decline"})),
    };
    let mut command = if let Some(a) = p["command"].as_array() {
        a.iter()
            .map(|v| v.as_str().unwrap_or(""))
            .collect::<Vec<_>>()
            .join(" ")
    } else {
        text(p, "command").into()
    };
    if command.is_empty() {
        command = p["commandActions"]
            .as_array()
            .into_iter()
            .flatten()
            .map(|a| text(a, "command"))
            .collect::<Vec<_>>()
            .join("\n");
    }
    let options: Vec<&str> = [
        ("allow_once", "accept"),
        ("allow_session", "acceptForSession"),
        ("deny", "decline"),
        ("cancel", "cancel"),
    ]
    .into_iter()
    .filter(|(_, native)| {
        p["availableDecisions"]
            .as_array()
            .is_none_or(|a| a.iter().any(|v| v == native))
    })
    .map(|(label, _)| label)
    .collect();
    let reply=store::approval(shared,sid,json!({"kind":kind,"title":title,"message":text(p,"reason"),"command":command,"cwd":if text(p,"cwd").is_empty(){run.cwd.as_str()}else{text(p,"cwd")},"details":truncate(&details,4000),"options":options}),cancel,secret).await?;
    let decision = text(&reply, "decision");
    if kind == "permissions" {
        return Ok(
            json!({"permissions":if ["allow_once","allow_session"].contains(&decision){p["permissions"].clone()}else{json!({})},"scope":if decision=="allow_session"{"session"}else{"turn"}}),
        );
    }
    if legacy {
        return Ok(
            json!({"decision":match decision{"allow_once"=>json!("approved"),"allow_session"=>json!("approved_for_session"),"cancel"=>json!("abort"),_=>json!({"denied":{"rejection":"Denied by user"}})}}),
        );
    }
    Ok(
        json!({"decision":match decision{"allow_once"=>"accept","allow_session"=>"acceptForSession","cancel"=>"cancel",_=>"decline"}}),
    )
}
async fn pi_approval(
    shared: &Shared,
    sid: &str,
    m: &Value,
    cancel: &mut watch::Receiver<bool>,
    secret: &[String],
) -> Result<Value> {
    let method = text(m, "method");
    if !["select", "confirm", "input", "editor"].contains(&method) {
        return Ok(json!({"type":"extension_ui_response","id":m["id"],"cancelled":true}));
    }
    let options = if method == "confirm" {
        json!(["allow_once", "deny", "cancel"])
    } else if method == "select" {
        m["options"].clone()
    } else {
        json!(["submit", "cancel"])
    };
    let reply=store::approval(shared,sid,json!({"kind":method,"title":text(m,"title"),"message":text(m,"message"),"placeholder":text(m,"placeholder"),"prefill":text(m,"prefill"),"options":options,"timeoutMs":m["timeout"]}),cancel,secret).await?;
    let mut result = json!({"type":"extension_ui_response","id":m["id"]});
    match (method, text(&reply, "decision")) {
        ("confirm", "allow_once" | "allow_session") => result["confirmed"] = json!(true),
        ("confirm", "deny") => result["confirmed"] = json!(false),
        (_, "cancel") => result["cancelled"] = json!(true),
        ("select" | "input" | "editor", decision) => {
            result["value"] = json!(reply["value"].as_str().unwrap_or(decision))
        }
        _ => result["cancelled"] = json!(true),
    }
    Ok(result)
}
pub async fn run(
    shared: &Shared,
    sid: &str,
    run: &mut Run,
    prompt: &str,
    mut cancel: watch::Receiver<bool>,
) -> Result<()> {
    let mut c = command(&run.agent)?;
    c.current_dir(&run.cwd);
    environment(&mut c, run);
    let args: Vec<String> = match run.agent.as_str() {
        "codex" => vec!["app-server".into(), "--stdio".into()],
        "claude" => vec![
            "-p".into(),
            "--output-format".into(),
            "stream-json".into(),
            "--verbose".into(),
        ],
        "gemini" => vec![
            "-p".into(),
            prompt.into(),
            "--output-format".into(),
            "stream-json".into(),
        ],
        "opencode" => vec![
            "run".into(),
            prompt.into(),
            "--format".into(),
            "json".into(),
            "--thinking".into(),
        ],
        "pi" => vec!["--mode".into(), "rpc".into()],
        _ => return Err("unsupported agent".into()),
    };
    c.args(args);
    if run.agent != "codex" {
        if let Some(id) = &run.resume_id {
            c.args([
                if run.agent == "opencode" || run.agent == "pi" {
                    "--session"
                } else {
                    "--resume"
                },
                id,
            ]);
        }
        if let Some(model) = &run.model {
            c.args(["--model", model]);
        }
        if run.agent == "pi" && !pi_provider(&run.provider).is_empty() {
            c.args(["--provider", pi_provider(&run.provider)]);
        }
    }
    let mut child = spawn(&mut c)?;
    let group = child.id();
    let mut input = child.stdin.take().unwrap();
    let mut reader = BufReader::new(child.stdout.take().unwrap());
    let stderr = tokio::spawn(stderr_tail(child.stderr.take().unwrap()));
    let secret = secrets(&run.provider);
    let mut normalized = Normalizer::default();
    let result:Result<()>=async{
        if run.agent=="claude"{input.write_all(format!("{prompt}\n").as_bytes()).await?;input.shutdown().await?;}
        if run.agent=="codex"{send(&mut input,&json!({"id":"termish-initialize","method":"initialize","params":{"clientInfo":{"name":"termish_agent_bridge","title":"Termish Agent Bridge","version":env!("CARGO_PKG_VERSION")}}})).await?;}
        if run.agent=="pi"{send(&mut input,&json!({"id":"termish-state","type":"get_state"})).await?;send(&mut input,&json!({"id":"termish-prompt","type":"prompt","message":prompt})).await?;}
        let mut first=true;let mut handshaking=run.agent=="codex";
        loop{if *cancel.borrow(){store::emit(shared,sid,json!({"type":"cancelled"}),&secret).await?;break;}
            let received=tokio::select!{_=cancel.changed()=>{store::emit(shared,sid,json!({"type":"cancelled"}),&secret).await?;break;},read=async{if first||handshaking{tokio::time::timeout(Duration::from_secs(30),line(&mut reader,16*1024*1024)).await.map_err(|_|"Agent startup timed out")?}else{line(&mut reader,16*1024*1024).await}}=>read};first=false;
            let m=match received{Ok(Some(v))=>v,Ok(None)=>break,Err(e) if e.to_string()=="invalid JSON"=>continue,Err(e)=>return Err(e)};
            if run.agent=="codex"{
                if !m["id"].is_null()&&!text(&m,"method").is_empty(){let response=codex_approval(shared,sid,run,&m,&mut cancel,&secret).await?;send(&mut input,&json!({"id":m["id"],"result":response})).await?;continue;}
                if !m["error"].is_null(){return Err(text(&m["error"],"message").to_owned().into());}
                if text(&m,"id")=="termish-initialize"{send(&mut input,&json!({"method":"initialized","params":{}})).await?;let params=if let Some(id)=&run.resume_id{json!({"threadId":id})}else{let mut p=json!({"cwd":run.cwd});if let Some(model)=&run.model{p["model"]=json!(model);}p};send(&mut input,&json!({"id":"termish-thread","method":if run.resume_id.is_some(){"thread/resume"}else{"thread/start"},"params":params})).await?;continue;}
                if text(&m,"id")=="termish-thread"{let thread=text(&m["result"]["thread"],"id");if thread.is_empty(){return Err("Codex App Server did not return a thread ID".into());}run.resume_id=Some(thread.into());let mut params=json!({"threadId":thread,"input":[{"type":"text","text":prompt}],"cwd":run.cwd});if let Some(model)=&run.model{params["model"]=json!(model);}send(&mut input,&json!({"id":"termish-turn","method":"turn/start","params":params})).await?;handshaking=false;continue;}
            }
            if run.agent=="pi"&&text(&m,"type")=="extension_ui_request"{let response=pi_approval(shared,sid,&m,&mut cancel,&secret).await?;send(&mut input,&response).await?;continue;}
            let events=normalized.events(run,&m);let settled=events.iter().any(|v|text(v,"type")=="settled");for e in events{if text(&e,"type")!="settled"{store::emit(shared,sid,e,&secret).await?;}}
            if settled{break;}
        }
        if !normalized.thinking.is_empty(){store::emit(shared,sid,json!({"type":"thinking","text":normalized.thinking.join("\n\n")}),&secret).await?;}
        Ok(())
    }.await;
    drop(input);
    // Always reap the entire owned process group, including approval/error/timeout paths.
    let status =
        if result.is_ok() && !*cancel.borrow() && !["codex", "pi"].contains(&run.agent.as_str()) {
            tokio::time::timeout(Duration::from_secs(3), child.wait())
                .await
                .ok()
                .and_then(|status| status.ok())
        } else {
            child.try_wait().ok().flatten()
        };
    terminate(&mut child, group).await;
    let tail = stderr.await.unwrap_or_default();
    result?;
    if !*cancel.borrow() {
        if status.is_some_and(|s| !s.success()) {
            return Err(format!("{} failed: {}", run.agent, truncate(&tail, 1000)).into());
        }
        if run.agent == "pi" && !normalized.prompt_accepted {
            return Err(format!("Pi did not accept the prompt: {}", truncate(&tail, 1000)).into());
        }
    }
    Ok(())
}
