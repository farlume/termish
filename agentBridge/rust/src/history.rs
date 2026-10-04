use crate::{adapters, util::*};
use serde_json::{json, Value};
use std::{
    collections::BTreeMap,
    fs,
    path::{Path, PathBuf},
};
pub struct Native {
    pub agent: String,
    pub native: String,
    pub resume: String,
    pub title: String,
    pub cwd: String,
    pub created: u64,
    pub updated: u64,
    pub messages: Vec<(String, String, u64)>,
}
impl Native {
    pub fn info(&self) -> Value {
        json!({"agent":self.agent,"nativeId":self.native,"title":self.title,"cwd":self.cwd,"createdAt":self.created,"updatedAt":self.updated,"messageCount":self.messages.len()})
    }
}
fn ts(v: &Value, fallback: u64) -> u64 {
    if let Some(n) = v.as_u64() {
        if n > 0 && n < 10_000_000_000 {
            n * 1000
        } else {
            n
        }
    } else if let Some(s) = v.as_str() {
        chrono::DateTime::parse_from_rfc3339(s)
            .map(|d| d.timestamp_millis().max(0) as u64)
            .unwrap_or(fallback)
    } else {
        fallback
    }
}
fn modified(p: &Path) -> u64 {
    p.metadata()
        .ok()
        .and_then(|m| m.modified().ok())
        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
fn files(dir: &Path, out: &mut Vec<PathBuf>, depth: u8) {
    if depth > 12 {
        return;
    }
    if let Ok(entries) = fs::read_dir(dir) {
        for e in entries.flatten() {
            let p = e.path();
            if e.file_type().is_ok_and(|t| t.is_dir()) {
                files(&p, out, depth + 1);
            } else if p.extension().is_some_and(|s| s == "jsonl" || s == "json") {
                out.push(p);
            }
        }
    }
}
fn content(v: &Value) -> String {
    if let Some(s) = v.as_str() {
        return s.trim().into();
    }
    v.as_array()
        .into_iter()
        .flatten()
        .filter_map(|b| b["text"].as_str())
        .map(str::trim)
        .filter(|s| !s.is_empty())
        .collect::<Vec<_>>()
        .join("\n\n")
}
fn push(n: &mut Native, role: &str, body: String, time: u64, merge: bool) {
    if !["user", "assistant"].contains(&role) || body.trim().is_empty() {
        return;
    }
    if merge && n.messages.last().is_some_and(|m| m.0 == role) {
        let last = n.messages.last_mut().unwrap();
        last.1.push_str("\n\n");
        last.1.push_str(body.trim());
        last.2 = time;
    } else if n.messages.len() < 1000 {
        n.messages.push((role.into(), body.trim().into(), time));
    }
    n.updated = n.updated.max(time);
}
fn records(path: &Path) -> Vec<Value> {
    fs::read_to_string(path)
        .ok()
        .map(|s| {
            s.lines()
                .filter_map(|l| serde_json::from_str(l).ok())
                .collect()
        })
        .unwrap_or_default()
}
fn title(n: &Native, fallback: &str) -> String {
    n.messages
        .iter()
        .find(|m| m.0 == "user")
        .map(|m| m.1.split_whitespace().collect::<Vec<_>>().join(" "))
        .filter(|s| !s.is_empty())
        .unwrap_or_else(|| fallback.into())
        .chars()
        .take(60)
        .collect()
}
fn parse(agent: &str, path: &Path) -> Option<Native> {
    let updated = modified(path);
    let mut n = Native {
        agent: agent.into(),
        native: String::new(),
        resume: String::new(),
        title: String::new(),
        cwd: String::new(),
        created: 0,
        updated,
        messages: Vec::new(),
    };
    if agent == "gemini" {
        let mut meta = json!({});
        let mut order = Vec::new();
        let mut messages = BTreeMap::new();
        let rows = if path.extension().is_some_and(|s| s == "json") {
            vec![serde_json::from_slice::<Value>(&fs::read(path).ok()?).ok()?]
        } else {
            records(path)
        };
        for r in rows {
            if let Some(key) = r["$rewindTo"].as_str() {
                let index = order.iter().position(|k| k == key).unwrap_or(0);
                for key in order.drain(index..) {
                    messages.remove(&key);
                }
            } else if r["$set"].is_object() || r["sessionId"].is_string() {
                let source = if r["$set"].is_object() {
                    &r["$set"]
                } else {
                    &r
                };
                for (k, v) in source.as_object()? {
                    meta[k] = v.clone();
                }
                if let Some(values) = source["messages"].as_array() {
                    order.clear();
                    messages.clear();
                    for m in values {
                        let key = text(m, "id");
                        if !key.is_empty() {
                            order.push(key.to_owned());
                            messages.insert(key.to_owned(), m.clone());
                        }
                    }
                }
            } else {
                let key = text(&r, "id");
                if !key.is_empty() {
                    if !messages.contains_key(key) {
                        order.push(key.into());
                    }
                    messages.insert(key.into(), r);
                }
            }
        }
        n.native = text(&meta, "sessionId").into();
        n.resume = n.native.clone();
        n.cwd = fs::read_to_string(path.parent()?.parent()?.join(".project_root"))
            .ok()
            .map(|s| s.trim().to_owned())
            .filter(|s| !s.is_empty())
            .unwrap_or_else(|| meta["directories"][0].as_str().unwrap_or("").into());
        for key in order {
            let m = &messages[&key];
            let role = if text(m, "type") == "gemini" {
                "assistant"
            } else {
                text(m, "type")
            };
            let body = if m["displayContent"].is_null() {
                content(&m["content"])
            } else {
                content(&m["displayContent"])
            };
            push(&mut n, role, body, ts(&m["timestamp"], updated), false);
        }
        n.created = ts(
            &meta["startTime"],
            n.messages.first().map(|m| m.2).unwrap_or(updated),
        );
        n.updated = ts(&meta["lastUpdated"], updated);
        n.title = text(&meta, "summary").chars().take(60).collect();
    } else {
        for r in records(path) {
            let kind = text(&r, "type");
            let time = ts(&r["timestamp"], updated);
            match agent {
                "codex" => {
                    let p = &r["payload"];
                    if kind == "session_meta" {
                        n.native = if text(p, "id").is_empty() {
                            text(p, "session_id")
                        } else {
                            text(p, "id")
                        }
                        .into();
                        n.cwd = text(p, "cwd").into();
                        n.created = ts(&p["timestamp"], time);
                    } else if kind == "response_item" && text(p, "type") == "message" {
                        let role = text(p, "role");
                        let expected = if role == "user" {
                            "input_text"
                        } else {
                            "output_text"
                        };
                        let body = p["content"]
                            .as_array()
                            .into_iter()
                            .flatten()
                            .filter(|b| text(b, "type") == expected)
                            .map(|b| text(b, "text"))
                            .collect::<Vec<_>>()
                            .join("\n\n");
                        let context = role == "user"
                            && !body.trim().is_empty()
                            && body
                                .split("\n\n")
                                .filter(|s| !s.trim().is_empty())
                                .all(|s| {
                                    [
                                        "<environment_context>",
                                        "<recommended_plugins>",
                                        "<permissions instructions>",
                                        "<skills_instructions>",
                                        "# AGENTS.md instructions",
                                    ]
                                    .iter()
                                    .any(|prefix| s.trim().starts_with(prefix))
                                });
                        if !context {
                            push(&mut n, role, body, time, false);
                        }
                    }
                }
                "claude" if r["isSidechain"] != true && ["user", "assistant"].contains(&kind) => {
                    if !text(&r, "sessionId").is_empty() {
                        n.native = text(&r, "sessionId").into();
                    }
                    if n.native.is_empty() {
                        n.native = path.file_stem()?.to_string_lossy().into();
                    }
                    if !text(&r, "cwd").is_empty() {
                        n.cwd = text(&r, "cwd").into();
                    }
                    if n.created == 0 {
                        n.created = time;
                    }
                    let m = &r["message"];
                    let role = if text(m, "role").is_empty() {
                        kind
                    } else {
                        text(m, "role")
                    };
                    push(
                        &mut n,
                        role,
                        content(&m["content"]),
                        time,
                        role == "assistant",
                    );
                }
                "pi" => {
                    if kind == "session" {
                        n.native = text(&r, "id").into();
                        n.cwd = text(&r, "cwd").into();
                        n.created = time;
                    } else if kind == "message" {
                        let m = &r["message"];
                        let role = text(m, "role");
                        push(
                            &mut n,
                            role,
                            content(&m["content"]),
                            ts(&m["timestamp"], time),
                            role == "assistant",
                        );
                    }
                }
                _ => (),
            }
        }
        n.resume = if agent == "pi" {
            fs::canonicalize(path).ok()?.to_string_lossy().into()
        } else {
            n.native.clone()
        };
    }
    if n.native.is_empty() || !Path::new(&n.cwd).is_dir() || n.messages.is_empty() {
        return None;
    }
    if n.created == 0 {
        n.created = n.updated;
    }
    if n.title.is_empty() {
        n.title = title(&n, &path.file_stem()?.to_string_lossy());
    }
    Some(n)
}
pub async fn list() -> Result<Vec<Native>> {
    let mut result = tokio::task::spawn_blocking(|| {
        let h = home();
        let codex = std::env::var("CODEX_HOME")
            .map(|s| expand(&s))
            .unwrap_or_else(|_| h.join(".codex"));
        let claude = std::env::var("CLAUDE_CONFIG_DIR")
            .map(|s| expand(&s))
            .unwrap_or_else(|_| h.join(".claude"));
        let pi = std::env::var("PI_CODING_AGENT_SESSION_DIR")
            .map(|s| expand(&s))
            .unwrap_or_else(|_| h.join(".pi/agent/sessions"));
        let mut out = Vec::new();
        for (agent, root) in [
            ("codex", codex.join("sessions")),
            ("claude", claude.join("projects")),
            ("pi", pi),
            ("gemini", h.join(".gemini/tmp")),
        ] {
            let mut paths = Vec::new();
            files(&root, &mut paths, 0);
            paths.sort_by_key(|p| std::cmp::Reverse(modified(p)));
            for p in paths
                .into_iter()
                .filter(|p| {
                    agent != "codex"
                        || p.file_name()
                            .is_some_and(|s| s.to_string_lossy().starts_with("rollout-"))
                })
                .filter(|p| {
                    agent != "gemini"
                        || (p
                            .file_name()
                            .is_some_and(|s| s.to_string_lossy().starts_with("session-"))
                            && p.parent()
                                .is_some_and(|p| p.file_name().is_some_and(|s| s == "chats")))
                })
                .take(80)
            {
                if let Some(n) = parse(agent, &p) {
                    out.push(n);
                }
            }
        }
        out
    })
    .await?;
    if let Some(binary) = adapters::resolve("opencode") {
        let mut c = tokio::process::Command::new(binary);
        c.args(["session", "list", "--format", "json", "--max-count", "80"]);
        if let Ok((true, out, _)) = adapters::bounded_output(&mut c, 10, 16 * 1024 * 1024).await {
            if let Ok(Value::Array(values)) = serde_json::from_str::<Value>(&out) {
                for v in values {
                    let native = if text(&v, "id").is_empty() {
                        text(&v, "sessionID")
                    } else {
                        text(&v, "id")
                    };
                    let cwd = if text(&v, "directory").is_empty() {
                        text(&v, "path")
                    } else {
                        text(&v, "directory")
                    };
                    if !native.is_empty() && Path::new(cwd).is_dir() {
                        let created = ts(&v["time"]["created"], ts(&v["time_created"], 0));
                        let updated = ts(&v["time"]["updated"], ts(&v["time_updated"], created));
                        result.push(Native {
                            agent: "opencode".into(),
                            native: native.into(),
                            resume: native.into(),
                            cwd: cwd.into(),
                            title: truncate(text(&v, "title"), 60),
                            created,
                            updated,
                            messages: Vec::new(),
                        });
                    }
                }
            }
        }
    }
    result.sort_by_key(|n| std::cmp::Reverse(n.updated));
    let mut seen = std::collections::BTreeSet::new();
    result.retain(|n| seen.insert((n.agent.clone(), n.native.clone())));
    result.truncate(100);
    Ok(result)
}
pub async fn load_messages(n: &mut Native) -> Result<()> {
    if n.agent != "opencode" {
        return Ok(());
    }
    let binary = adapters::resolve("opencode").ok_or("opencode is not installed")?;
    let mut c = tokio::process::Command::new(binary);
    c.args(["export", &n.native]);
    let (ok, out, _) = adapters::bounded_output(&mut c, 15, 16 * 1024 * 1024).await?;
    if !ok {
        return Err("OpenCode history export failed".into());
    }
    let value: Value = serde_json::from_str(&out)?;
    for m in value["messages"].as_array().into_iter().flatten() {
        let info = if m["info"].is_object() { &m["info"] } else { m };
        let body = m["parts"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|b| text(b, "type") == "text")
            .map(|b| text(b, "text"))
            .collect::<Vec<_>>()
            .join("\n\n");
        push(
            n,
            text(info, "role"),
            body,
            ts(&info["time"]["created"], 0),
            false,
        );
    }
    n.title = title(n, &n.title);
    Ok(())
}
