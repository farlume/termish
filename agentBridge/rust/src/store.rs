use crate::{adapters, history, util::*};
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::{
    collections::{BTreeMap, BTreeSet, VecDeque},
    fs,
    path::{Path, PathBuf},
    sync::Arc,
    time::Duration,
};
use tokio::sync::{broadcast, oneshot, watch, Mutex};
pub type Shared = Arc<Mutex<Store>>;
#[derive(Serialize, Deserialize)]
pub struct Session {
    pub id: String,
    pub agent: String,
    pub cwd: String,
    pub title: String,
    pub model: Option<String>,
    pub provider: Option<String>,
    pub provider_id: Option<String>,
    pub resume_id: Option<String>,
    pub created_at: u64,
    #[serde(default)]
    pub messages: Vec<Value>,
    #[serde(skip)]
    pub busy: bool,
    #[serde(skip)]
    pub turn: String,
    #[serde(skip)]
    stream: String,
    #[serde(skip)]
    answer: String,
    #[serde(skip)]
    thinking: String,
    #[serde(skip)]
    reasoning: String,
    #[serde(skip)]
    sequence: u64,
    #[serde(skip)]
    tools: BTreeMap<String, Value>,
    #[serde(skip)]
    pub approvals: BTreeMap<String, Value>,
    #[serde(skip)]
    replies: BTreeMap<String, oneshot::Sender<Value>>,
    #[serde(skip)]
    cursor: u64,
    #[serde(skip)]
    journal: VecDeque<Value>,
    #[serde(skip)]
    cancel: Option<watch::Sender<bool>>,
}
impl Session {
    fn new(
        agent: &str,
        cwd: String,
        title: String,
        model: Option<String>,
        selected_provider: (Option<String>, Option<String>),
        resume_id: Option<String>,
        created_at: u64,
    ) -> Self {
        Self {
            id: id(),
            agent: agent.into(),
            cwd,
            title,
            model,
            provider: selected_provider.0,
            provider_id: selected_provider.1,
            resume_id,
            created_at,
            messages: Vec::new(),
            busy: false,
            turn: String::new(),
            stream: String::new(),
            answer: String::new(),
            thinking: String::new(),
            reasoning: String::new(),
            sequence: 0,
            tools: BTreeMap::new(),
            approvals: BTreeMap::new(),
            replies: BTreeMap::new(),
            cursor: 0,
            journal: VecDeque::new(),
            cancel: None,
        }
    }
    fn info(&self, epoch: &str) -> Value {
        json!({"sessionId":self.id,"agent":self.agent,"cwd":self.cwd,"title":self.title,"model":self.model,"provider":self.provider_id,"providerType":self.provider,"busy":self.busy,"createdAt":self.created_at,"messageCount":self.messages.len(),"eventEpoch":epoch,"eventCursor":self.cursor,"waitingApproval":!self.approvals.is_empty()})
    }
    fn add(&mut self, role: &str, body: &str, mut meta: Value) {
        let seq = self
            .messages
            .last()
            .and_then(|v| v["messageSeq"].as_u64())
            .map(|n| n + 1)
            .unwrap_or(0);
        meta["role"] = json!(role);
        meta["text"] = json!(body);
        if meta["ts"].is_null() {
            meta["ts"] = json!(now());
        }
        meta["messageSeq"] = json!(seq);
        if meta["messageId"].is_null() {
            meta["messageId"] = json!(format!("{}:{seq}", self.id));
        }
        self.messages.push(meta);
    }
    fn persist(&self) -> Result<()> {
        atomic_json(
            &bridge_home()
                .join("data/sessions")
                .join(format!("{}.json", self.id)),
            &serde_json::to_value(self)?,
        )
    }
    fn flush_answer(&mut self) {
        if !self.stream.is_empty() {
            let body = std::mem::take(&mut self.stream);
            let activity = std::mem::take(&mut self.answer);
            self.add("assistant",&body,json!({"messageId":activity,"activityId":activity,"turnId":self.turn,"agent":self.agent}));
        }
    }
    fn flush_thinking(&mut self) {
        if !self.thinking.is_empty() {
            let body = std::mem::take(&mut self.thinking);
            let activity = std::mem::take(&mut self.reasoning);
            self.add("thinking",&body,json!({"messageId":activity,"activityId":activity,"turnId":self.turn,"sequence":self.sequence,"agent":self.agent}));
        } else {
            self.reasoning.clear();
        }
    }
    fn active(&self) -> Vec<Value> {
        let mut out = Vec::new();
        if !self.thinking.is_empty() {
            out.push(json!({"role":"thinking","text":self.thinking,"messageId":self.reasoning,"activityId":self.reasoning,"turnId":self.turn,"sequence":self.sequence,"agent":self.agent,"running":true}));
        }
        for (key, v) in &self.tools {
            out.push(json!({"role":"tool","text":"","messageId":key,"activityId":key,"turnId":self.turn,"sequence":v["sequence"],"agent":self.agent,"toolName":v["name"],"toolInput":v["input"],"startedAt":v["startedAt"],"running":true}));
        }
        if !self.stream.is_empty() {
            out.push(json!({"role":"assistant","text":self.stream,"messageId":self.answer,"activityId":self.answer,"turnId":self.turn,"agent":self.agent,"running":true}));
        }
        out
    }
}
pub struct Store {
    pub sessions: BTreeMap<String, Session>,
    pub events: broadcast::Sender<Value>,
    epoch: String,
    installing: BTreeSet<String>,
}
impl Store {
    pub fn open() -> Result<Shared> {
        let dir = bridge_home().join("data/sessions");
        private_dir(&dir)?;
        let mut sessions = BTreeMap::new();
        for entry in fs::read_dir(dir)? {
            let path = entry?.path();
            if path.extension().is_some_and(|s| s == "json") {
                let session: Session = serde_json::from_slice(&fs::read(&path)?)?;
                if session.id.len() != 12 || !session.id.bytes().all(|b| b.is_ascii_hexdigit()) {
                    return Err("invalid stored session ID".into());
                }
                sessions.insert(session.id.clone(), session);
            }
        }
        let (events, _) = broadcast::channel(4096);
        let store = Self {
            sessions,
            events,
            epoch: id(),
            installing: BTreeSet::new(),
        };
        store.cleanup_attachments();
        Ok(Arc::new(Mutex::new(store)))
    }
    fn require(&mut self, sid: &str) -> Result<&mut Session> {
        self.sessions
            .get_mut(sid)
            .ok_or_else(|| "session not found".into())
    }
    fn publish(&mut self, sid: &str, mut message: Value) {
        if let Some(s) = self.sessions.get_mut(sid) {
            s.cursor += 1;
            message["eventEpoch"] = json!(self.epoch);
            message["eventSeq"] = json!(s.cursor);
            s.journal.push_back(message.clone());
            while s.journal.len() > 2048 {
                s.journal.pop_front();
            }
            let _ = self.events.send(message);
        }
    }
    fn cleanup_attachments(&self) {
        let referenced = self.attachment_references(None);
        let roots: BTreeSet<PathBuf> = self
            .sessions
            .values()
            .filter_map(|s| fs::canonicalize(Path::new(&s.cwd).join(".termish/attachments")).ok())
            .collect();
        let cutoff = now().saturating_sub(30 * 24 * 60 * 60 * 1000);
        for root in roots {
            if let Ok(items) = fs::read_dir(root) {
                for entry in items.flatten() {
                    let path = entry.path();
                    if let Ok(meta) = fs::symlink_metadata(&path) {
                        let old = meta
                            .modified()
                            .ok()
                            .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                            .map(|d| (d.as_millis() as u64) < cutoff)
                            .unwrap_or(false);
                        if !meta.is_dir() && old && !referenced.contains(&path) {
                            let _ = fs::remove_file(path);
                        }
                    }
                }
            }
        }
    }
    fn attachment_references(&self, exclude: Option<&str>) -> BTreeSet<PathBuf> {
        self.sessions
            .values()
            .filter(|s| Some(s.id.as_str()) != exclude)
            .flat_map(|s| {
                s.messages.iter().flat_map(|m| {
                    m["attachments"]
                        .as_array()
                        .into_iter()
                        .flatten()
                        .filter_map(|a| safe_attachment(s, text(a, "path")))
                })
            })
            .collect()
    }
}
pub fn safe_attachment(s: &Session, name: &str) -> Option<PathBuf> {
    let path = Path::new(name);
    if path.parent() != Some(Path::new(".termish/attachments")) || path.file_name().is_none() {
        return None;
    }
    let root = fs::canonicalize(Path::new(&s.cwd).join(".termish/attachments")).ok()?;
    let candidate = fs::canonicalize(Path::new(&s.cwd).join(path)).ok()?;
    if candidate.parent() == Some(root.as_path()) && candidate.is_file() {
        Some(candidate)
    } else {
        None
    }
}
fn option(v: &Value, k: &str) -> Option<String> {
    let s = text(v, k).trim();
    if s.is_empty() {
        None
    } else {
        Some(s.into())
    }
}
fn provider_match(s: &Session, p: &Value) -> Result<()> {
    if option(p, "id") != s.provider_id || option(p, "type") != s.provider {
        return Err("session provider does not match the selected provider".into());
    }
    if s.provider_id.is_some() && text(p, "apiKey").trim().is_empty() {
        return Err("API key is missing".into());
    }
    Ok(())
}
#[derive(Clone)]
pub struct Run {
    pub agent: String,
    pub cwd: String,
    pub model: Option<String>,
    pub resume_id: Option<String>,
    pub provider: Value,
}
impl Run {
    fn from(s: &Session, provider: &Value) -> Self {
        Self {
            agent: s.agent.clone(),
            cwd: s.cwd.clone(),
            model: s.model.clone(),
            resume_id: s.resume_id.clone(),
            provider: provider.clone(),
        }
    }
}
pub async fn emit(shared: &Shared, sid: &str, mut event: Value, secret: &[String]) -> Result<()> {
    redact(&mut event, secret);
    let mut store = shared.lock().await;
    let s = store.require(sid)?;
    event["turnId"] = json!(s.turn);
    event["ts"] = json!(now());
    match text(&event, "type") {
        "thinking_start" | "thinking_delta" | "thinking" => {
            if s.reasoning.is_empty() {
                s.flush_answer();
                s.reasoning = id();
                s.answer.clear();
                s.sequence += 1;
            }
            event["activityId"] = json!(s.reasoning);
            event["sequence"] = json!(s.sequence);
            if text(&event, "type") == "thinking_delta" {
                s.thinking.push_str(text(&event, "text"));
            }
            if text(&event, "type") == "thinking" {
                s.thinking = text(&event, "text").into();
                s.flush_thinking();
            }
        }
        "tool_start" => {
            s.flush_thinking();
            s.flush_answer();
            s.answer.clear();
            s.sequence += 1;
            let key = option(&event, "toolId").unwrap_or_else(id);
            event["activityId"] = json!(key);
            event["sequence"] = json!(s.sequence);
            s.tools.insert(key,json!({"name":event["name"],"input":text(&event,"args"),"startedAt":now(),"sequence":s.sequence}));
        }
        "tool_end" => {
            let key = option(&event, "toolId").unwrap_or_else(id);
            let pending = s.tools.remove(&key).unwrap_or_else(|| json!({}));
            let started = pending["startedAt"].as_u64().unwrap_or(now());
            let artifacts = if event["isError"] == true {
                Vec::new()
            } else {
                detect_artifacts(
                    &s.cwd,
                    &[text(&pending, "input"), text(&event, "output")],
                    started,
                )
            };
            event["activityId"] = json!(key);
            event["sequence"] = pending["sequence"].clone();
            event["toolInput"] = pending["input"].clone();
            event["startedAt"] = json!(started);
            event["completedAt"] = json!(now());
            event["artifacts"] = json!(artifacts);
            s.add("tool",text(&event,"output"),json!({"messageId":key,"activityId":key,"turnId":s.turn,"sequence":pending["sequence"],"agent":s.agent,"toolName":event["name"],"toolInput":pending["input"],"startedAt":started,"completedAt":now(),"isError":event["isError"],"artifacts":artifacts}));
        }
        "delta" | "assistant_message" => {
            if s.answer.is_empty() {
                s.flush_thinking();
                s.answer = id();
            }
            event["activityId"] = json!(s.answer);
            if text(&event, "type") == "delta" {
                s.stream.push_str(text(&event, "text"));
            } else {
                let body = text(&event, "text");
                if !body.is_empty() {
                    s.add("assistant",body,json!({"messageId":s.answer,"activityId":s.answer,"turnId":s.turn,"agent":s.agent}));
                    s.stream.clear();
                    s.answer.clear();
                }
            }
        }
        "error" => {
            s.flush_thinking();
            s.flush_answer();
            s.add(
                "error",
                text(&event, "message"),
                json!({"messageId":id(),"turnId":s.turn,"agent":s.agent}),
            );
        }
        "settled" => {
            s.flush_thinking();
            s.flush_answer();
        }
        _ => (),
    }
    s.persist()?;
    store.publish(sid, json!({"type":"event","sessionId":sid,"event":event}));
    Ok(())
}
pub async fn approval(
    shared: &Shared,
    sid: &str,
    mut request: Value,
    cancel: &mut watch::Receiver<bool>,
    secret: &[String],
) -> Result<Value> {
    let key = id();
    let (sender, receiver) = oneshot::channel();
    let timeout = request["timeoutMs"]
        .as_u64()
        .filter(|n| *n > 0)
        .unwrap_or(24 * 60 * 60 * 1000);
    {
        let mut store = shared.lock().await;
        let s = store.require(sid)?;
        request["approvalId"] = json!(key);
        request["sessionId"] = json!(sid);
        request["turnId"] = json!(s.turn);
        request["agent"] = json!(s.agent);
        if text(&request, "cwd").is_empty() {
            request["cwd"] = json!(s.cwd);
        }
        redact(&mut request, secret);
        s.approvals.insert(key.clone(), request.clone());
        s.replies.insert(key.clone(), sender);
    }
    request["type"] = json!("approval_request");
    emit(shared, sid, request, secret).await?;
    let reply = if *cancel.borrow() {
        json!({"decision":"cancel"})
    } else {
        tokio::select! {r=receiver=>r.unwrap_or_else(|_|json!({"decision":"cancel"})),_=tokio::time::sleep(Duration::from_millis(timeout))=>json!({"decision":"cancel"}),_=cancel.changed()=>json!({"decision":"cancel"})}
    };
    {
        let mut store = shared.lock().await;
        let s = store.require(sid)?;
        s.approvals.remove(&key);
        s.replies.remove(&key);
    }
    emit(
        shared,
        sid,
        json!({"type":"approval_resolved","approvalId":key}),
        secret,
    )
    .await?;
    Ok(reply)
}
pub async fn abort(shared: &Shared, sid: &str) -> Result<()> {
    {
        let mut store = shared.lock().await;
        let s = store.require(sid)?;
        if let Some(c) = &s.cancel {
            let _ = c.send(true);
        }
        s.replies.clear();
    }
    for _ in 0..100 {
        if !shared.lock().await.require(sid)?.busy {
            return Ok(());
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
    Err("Agent is still stopping".into())
}
pub async fn shutdown(shared: Shared) {
    let ids: Vec<String> = shared.lock().await.sessions.keys().cloned().collect();
    for sid in ids {
        let _ = abort(&shared, &sid).await;
    }
}
pub async fn dispatch(shared: Shared, method: &str, p: &Value) -> Result<Value> {
    let sid = text(p, "sessionId");
    match method {
        "agents.list" => {
            let s = shared.lock().await;
            Ok(json!({"agents":adapters::list(),"installing":s.installing}))
        }
        "agents.installPlan" => adapters::install_plan(text(p, "agent")),
        "agents.install" => {
            let agent = text(p, "agent").to_owned();
            adapters::install_plan(&agent)?;
            let mut store = shared.lock().await;
            let old = !store.installing.insert(agent.clone());
            drop(store);
            if !old {
                let shared = shared.clone();
                tokio::spawn(async move {
                    let result = adapters::install(&shared, &agent).await;
                    let mut store = shared.lock().await;
                    if let Err(e) = result {
                        let _=store.events.send(json!({"type":"event","event":{"type":"install_error","agent":agent,"message":e.to_string()}}));
                    }
                    store.installing.remove(&agent);
                });
            }
            Ok(json!({"accepted":true,"alreadyRunning":old}))
        }
        "providers.fetchModels" => adapters::fetch_models(p).await,
        "sessions.nativeList" => {
            let list = history::list().await?;
            let store = shared.lock().await;
            Ok(
                json!({"sessions":list.iter().map(|n|{let mut v=n.info();v["importedSessionId"]=json!(store.sessions.values().find(|s|s.agent==n.agent&&s.resume_id.as_deref()==Some(&n.resume)).map(|s|&s.id));v}).collect::<Vec<_>>()}),
            )
        }
        "sessions.nativeImport" => {
            let mut native = history::list()
                .await?
                .into_iter()
                .find(|n| n.agent == text(p, "agent") && n.native == text(p, "nativeId"))
                .ok_or("native Agent session was not found")?;
            history::load_messages(&mut native).await?;
            let mut store = shared.lock().await;
            if let Some(s) = store
                .sessions
                .values()
                .find(|s| s.agent == native.agent && s.resume_id.as_deref() == Some(&native.resume))
            {
                return Ok(s.info(&store.epoch));
            }
            let mut session = Session::new(
                &native.agent,
                native.cwd,
                native.title,
                None,
                (None, None),
                Some(native.resume),
                native.updated,
            );
            for (role, body, ts) in native.messages {
                session.add(
                    &role,
                    &body,
                    json!({"nativeImported":true,"agent":session.agent,"ts":ts}),
                );
            }
            session.persist()?;
            let result = session.info(&store.epoch);
            store.sessions.insert(session.id.clone(), session);
            Ok(result)
        }
        "sessions.list" => {
            let store = shared.lock().await;
            let mut sessions: Vec<_> = store.sessions.values().collect();
            sessions.sort_by_key(|s| std::cmp::Reverse(s.created_at));
            Ok(json!({"sessions":sessions.iter().map(|s|s.info(&store.epoch)).collect::<Vec<_>>()}))
        }
        "sessions.create" => {
            let agent = text(p, "agent");
            if adapters::resolve(agent).is_none() {
                return Err(format!("agent is not installed: {agent}").into());
            }
            let provider = &p["provider"];
            if option(provider, "id").is_some() != option(provider, "type").is_some() {
                return Err("provider id and type must be supplied together".into());
            }
            if !adapters::provider_supported(agent, provider) {
                return Err("provider is not compatible with agent".into());
            }
            let cwd = if text(p, "cwd").is_empty() {
                let path = bridge_home().join("workspaces").join(id());
                private_dir(&path)?;
                path
            } else {
                fs::canonicalize(expand(text(p, "cwd")))?
            };
            if !cwd.is_dir() {
                return Err("working directory does not exist".into());
            }
            let title = format!(
                "{agent} · {}",
                cwd.file_name().unwrap_or_default().to_string_lossy()
            );
            let session = Session::new(
                agent,
                cwd.to_string_lossy().into(),
                title,
                option(p, "model"),
                (option(provider, "type"), option(provider, "id")),
                None,
                now(),
            );
            session.persist()?;
            let mut store = shared.lock().await;
            let result = session.info(&store.epoch);
            store.sessions.insert(session.id.clone(), session);
            Ok(result)
        }
        "sessions.get" => {
            let mut store = shared.lock().await;
            let epoch = store.epoch.clone();
            let s = store.require(sid)?;
            let limit = p["messageLimit"].as_u64().unwrap_or(120).clamp(1, 300) as usize;
            let before = p["beforeSeq"].as_u64();
            let available: Vec<_> = s
                .messages
                .iter()
                .filter(|m| before.is_none_or(|b| m["messageSeq"].as_u64().unwrap_or(0) < b))
                .collect();
            let more = available.len() > limit;
            let start = available.len().saturating_sub(limit);
            let mut messages: Vec<Value> =
                available[start..].iter().map(|m| (*m).clone()).collect();
            let oldest = messages
                .first()
                .map(|m| m["messageSeq"].clone())
                .unwrap_or(Value::Null);
            if before.is_none() && s.busy {
                messages.extend(s.active());
            }
            let mut result = s.info(&epoch);
            result["messages"] = json!(messages);
            result["hasMoreMessages"] = json!(more);
            result["oldestMessageSeq"] = oldest;
            result["approvals"] = json!(s.approvals.values().collect::<Vec<_>>());
            Ok(result)
        }
        "events.replay" => {
            let mut store = shared.lock().await;
            let epoch = store.epoch.clone();
            let s = store.require(sid)?;
            let after = p["after"].as_u64().unwrap_or(0);
            let oldest = s
                .journal
                .front()
                .and_then(|v| v["eventSeq"].as_u64())
                .unwrap_or(s.cursor + 1);
            let reset = text(p, "eventEpoch") != epoch
                || after > s.cursor
                || after < oldest.saturating_sub(1);
            Ok(
                json!({"eventEpoch":epoch,"eventCursor":s.cursor,"reset":reset,"events":if reset{Vec::new()}else{s.journal.iter().filter(|v|v["eventSeq"].as_u64().unwrap_or(0)>after).cloned().collect()}}),
            )
        }
        "sessions.rename" | "sessions.model" => {
            let mut store = shared.lock().await;
            let epoch = store.epoch.clone();
            let s = store.require(sid)?;
            if method == "sessions.rename" {
                let title = truncate(text(p, "title").trim(), 120);
                if title.is_empty() {
                    return Err("session title is empty".into());
                }
                s.title = title;
            } else {
                if s.busy {
                    return Err("session is busy".into());
                }
                s.model = option(p, "model");
            }
            s.persist()?;
            Ok(s.info(&epoch))
        }
        "sessions.delete" => {
            abort(&shared, sid).await?;
            let mut store = shared.lock().await;
            let referenced = store.attachment_references(Some(sid));
            let s = store.require(sid)?;
            for m in &s.messages {
                for a in m["attachments"].as_array().into_iter().flatten() {
                    if let Some(path) = safe_attachment(s, text(a, "path")) {
                        if !referenced.contains(&path) {
                            let _ = fs::remove_file(path);
                        }
                    }
                }
            }
            fs::remove_file(
                bridge_home()
                    .join("data/sessions")
                    .join(format!("{sid}.json")),
            )?;
            store.sessions.remove(sid);
            let _ = store
                .events
                .send(json!({"type":"session_deleted","sessionId":sid}));
            Ok(json!({"ok":true}))
        }
        "agents.check" => {
            let run = {
                let mut store = shared.lock().await;
                let s = store.require(sid)?;
                provider_match(s, &p["provider"])?;
                Run::from(s, &p["provider"])
            };
            adapters::readiness(&run).await
        }
        "prompt.send" => {
            let mut run = {
                let mut store = shared.lock().await;
                let s = store.require(sid)?;
                if s.busy {
                    return Err("session is busy".into());
                }
                provider_match(s, &p["provider"])?;
                Run::from(s, &p["provider"])
            };
            if text(p, "message").trim().is_empty() {
                return Err("message is empty".into());
            }
            adapters::readiness(&run).await?;
            let mut store = shared.lock().await;
            let s = store.require(sid)?;
            if s.busy {
                return Err("session is busy".into());
            }
            let mut prompt = text(p, "message").to_owned();
            let mut seen = BTreeSet::new();
            let attachments:Vec<Value>=p["attachments"].as_array().into_iter().flatten().filter(|a|!text(a,"name").is_empty()&&safe_attachment(s,text(a,"path")).is_some()&&seen.insert(text(a,"path").to_owned())).map(|a|json!({"name":a["name"],"path":a["path"],"size":a["size"].as_u64().unwrap_or(0)})).collect();
            if s.messages.is_empty() {
                s.title = prompt
                    .lines()
                    .next()
                    .unwrap_or("")
                    .split_whitespace()
                    .collect::<Vec<_>>()
                    .join(" ")
                    .chars()
                    .take(60)
                    .collect();
            }
            s.turn = id();
            s.busy = true;
            s.sequence = 0;
            s.journal.clear();
            s.add(
                "user",
                &prompt,
                json!({"messageId":id(),"turnId":s.turn,"attachments":attachments}),
            );
            if let Err(error) = s.persist() {
                s.busy = false;
                s.messages.pop();
                return Err(error);
            }
            if !attachments.is_empty() {
                prompt.push_str("\n\nFiles attached by the user (paths are relative to the working directory):\n");
                for a in attachments {
                    prompt.push_str(&format!("- {}: {}\n", text(&a, "name"), text(&a, "path")));
                }
            }
            let (cancel, receiver) = watch::channel(false);
            s.cancel = Some(cancel);
            let turn = s.turn.clone();
            store.publish(sid, json!({"type":"busy","sessionId":sid,"busy":true}));
            drop(store);
            let shared = shared.clone();
            let sid = sid.to_owned();
            tokio::spawn(async move {
                let secret = secrets(&run.provider);
                let result = adapters::run(&shared, &sid, &mut run, &prompt, receiver).await;
                if let Err(e) = result {
                    let _ = emit(
                        &shared,
                        &sid,
                        json!({"type":"error","message":e.to_string()}),
                        &secret,
                    )
                    .await;
                }
                let _ = emit(&shared, &sid, json!({"type":"settled"}), &secret).await;
                let mut store = shared.lock().await;
                if let Ok(s) = store.require(&sid) {
                    s.resume_id = run.resume_id;
                    s.busy = false;
                    s.cancel = None;
                    s.tools.clear();
                    s.approvals.clear();
                    s.replies.clear();
                    if let Err(e) = s.persist() {
                        let _=store.events.send(json!({"type":"event","sessionId":sid,"event":{"type":"error","message":e.to_string()}}));
                    }
                }
                store.publish(&sid, json!({"type":"busy","sessionId":sid,"busy":false}));
            });
            Ok(json!({"accepted":true,"turnId":turn}))
        }
        "prompt.abort" => {
            abort(&shared, sid).await?;
            Ok(json!({"ok":true}))
        }
        "approval.respond" => {
            let mut store = shared.lock().await;
            let s = store.require(sid)?;
            let key = text(p, "approvalId");
            let request = s
                .approvals
                .get(key)
                .ok_or("approval request is no longer pending")?;
            let decision = text(p, "decision");
            if ![
                "allow_once",
                "allow_session",
                "deny",
                "cancel",
                "submit",
                "select",
            ]
            .contains(&decision)
                && !request["options"]
                    .as_array()
                    .is_some_and(|a| a.iter().any(|v| v == decision))
            {
                return Err("invalid approval decision".into());
            }
            let reply = s
                .replies
                .remove(key)
                .ok_or("approval request is no longer pending")?;
            let _ = reply.send(json!({"decision":decision,"value":p["value"]}));
            Ok(json!({"ok":true}))
        }
        _ => Err(format!("unknown method: {method}").into()),
    }
}
fn detect_artifacts(cwd: &str, texts: &[&str], started: u64) -> Vec<Value> {
    use std::sync::LazyLock;
    static PATHS: LazyLock<regex::Regex> = LazyLock::new(|| {
        regex::Regex::new(r#"(?i)"([^"\n]+\.(?:csv|docx?|gif|html?|jpe?g|markdown|md|odp|ods|odt|pdf|png|pptx?|rtf|svg|webp|xls[xm]?))"|'([^'\n]+\.(?:csv|docx?|gif|html?|jpe?g|markdown|md|odp|ods|odt|pdf|png|pptx?|rtf|svg|webp|xls[xm]?))'|([^\s"'`<>|]+\.(?:csv|docx?|gif|html?|jpe?g|markdown|md|odp|ods|odt|pdf|png|pptx?|rtf|svg|webp|xls[xm]?))"#).unwrap()
    });
    let mut out = Vec::new();
    let mut seen = BTreeSet::new();
    for text in texts {
        for caps in PATHS.captures_iter(text) {
            let raw = caps
                .iter()
                .skip(1)
                .flatten()
                .next()
                .unwrap()
                .as_str()
                .trim_matches(['(', ')', '[', ']', '{', '}', '<', '>', ',', ';', ':']);
            let path = expand(raw.strip_prefix("file://").unwrap_or(raw));
            let path = if path.is_absolute() {
                path
            } else {
                Path::new(cwd).join(path)
            };
            if let Ok(path) = fs::canonicalize(path) {
                if let Ok(meta) = path.metadata() {
                    let modified = meta
                        .modified()
                        .ok()
                        .and_then(|t| t.duration_since(std::time::UNIX_EPOCH).ok())
                        .map(|d| d.as_millis() as u64)
                        .unwrap_or(0);
                    if !meta.is_file() || modified + 2000 < started || !seen.insert(path.clone()) {
                        continue;
                    }
                    let ext = path
                        .extension()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .to_ascii_lowercase();
                    let kind = match ext.as_str() {
                        "csv" | "ods" | "xls" | "xlsx" | "xlsm" => "spreadsheet",
                        "pdf" => "pdf",
                        "ppt" | "pptx" | "odp" => "presentation",
                        "html" | "htm" => "web",
                        "md" | "markdown" => "markdown",
                        "doc" | "docx" | "odt" | "rtf" => "document",
                        _ => "image",
                    };
                    out.push(json!({"name":path.file_name().unwrap().to_string_lossy(),"path":path,"size":meta.len(),"kind":kind}));
                }
            }
        }
    }
    out
}
