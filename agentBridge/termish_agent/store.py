from __future__ import annotations

import asyncio
import json
import pathlib
import sqlite3
import time
import uuid
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Dict, List, Optional

from adapters import ADAPTERS, AgentRun, bridge_home, install_agent, install_plan, list_agents, provider_supported


Broadcast = Callable[[Dict[str, Any]], Awaitable[None]]


@dataclass
class Session:
    id: str
    agent: str
    cwd: str
    title: str
    model: Optional[str]
    provider: Optional[str]
    provider_config_id: Optional[str]
    resume_id: Optional[str]
    created_at: int
    busy: bool = False
    run: Optional[AgentRun] = None
    task: Optional[asyncio.Task] = None
    stream_text: str = ""
    current_turn_id: Optional[str] = None
    current_reasoning_id: Optional[str] = None
    current_answer_id: Optional[str] = None
    activity_sequence: int = 0
    pending_tools: Dict[str, Dict[str, Any]] = field(default_factory=dict)


class SessionStore:
    def __init__(self, broadcast: Broadcast) -> None:
        self.broadcast = broadcast
        data_dir = bridge_home() / "data"
        data_dir.mkdir(parents=True, exist_ok=True, mode=0o700)
        data_dir.chmod(0o700)
        db_path = data_dir / "sessions.db"
        self.db = sqlite3.connect(db_path)
        db_path.chmod(0o600)
        self.db.row_factory = sqlite3.Row
        self.db.execute("PRAGMA journal_mode=WAL")
        self.db.executescript(
            """
            CREATE TABLE IF NOT EXISTS sessions (
              id TEXT PRIMARY KEY,
              agent TEXT NOT NULL,
              cwd TEXT NOT NULL,
              title TEXT NOT NULL,
              model TEXT,
              provider TEXT,
              provider_config_id TEXT,
              resume_id TEXT,
              created_at INTEGER NOT NULL
            );
            CREATE TABLE IF NOT EXISTS messages (
              session_id TEXT NOT NULL REFERENCES sessions(id) ON DELETE CASCADE,
              seq INTEGER NOT NULL,
              role TEXT NOT NULL,
              text TEXT NOT NULL,
              meta TEXT NOT NULL DEFAULT '{}',
              ts INTEGER NOT NULL,
              PRIMARY KEY(session_id, seq)
            );
            """
        )
        columns = {row[1] for row in self.db.execute("PRAGMA table_info(sessions)")}
        if "provider" not in columns:
            self.db.execute("ALTER TABLE sessions ADD COLUMN provider TEXT")
        if "provider_config_id" not in columns:
            self.db.execute("ALTER TABLE sessions ADD COLUMN provider_config_id TEXT")
        self.db.execute("PRAGMA foreign_keys=ON")
        self.sessions: Dict[str, Session] = {}
        self.installing: set[str] = set()
        self._restore()

    def _restore(self) -> None:
        for row in self.db.execute("SELECT * FROM sessions ORDER BY created_at DESC"):
            self.sessions[row["id"]] = Session(
                id=row["id"],
                agent=row["agent"],
                cwd=row["cwd"],
                title=row["title"],
                model=row["model"],
                provider=row["provider"],
                provider_config_id=row["provider_config_id"],
                resume_id=row["resume_id"],
                created_at=row["created_at"],
            )

    def close(self) -> None:
        self.db.close()

    async def shutdown(self) -> None:
        for session in list(self.sessions.values()):
            if session.task and not session.task.done():
                await self.abort(session.id)
        self.close()

    def list_sessions(self) -> List[Dict[str, Any]]:
        return [self.session_info(session) for session in sorted(self.sessions.values(), key=lambda value: value.created_at, reverse=True)]

    def session_info(self, session: Session) -> Dict[str, Any]:
        count = self.db.execute("SELECT COUNT(*) FROM messages WHERE session_id=?", (session.id,)).fetchone()[0]
        return {
            "sessionId": session.id,
            "agent": session.agent,
            "cwd": session.cwd,
            "title": session.title,
            "model": session.model,
            "provider": session.provider_config_id,
            "providerType": session.provider,
            "busy": session.busy,
            "createdAt": session.created_at,
            "messageCount": count,
        }

    def create(
        self,
        agent: str,
        cwd: Optional[str],
        model: Optional[str],
        provider: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        adapter = ADAPTERS.get(agent)
        if not adapter:
            raise RuntimeError(f"agent is not supported: {agent}")
        if not adapter.available():
            raise RuntimeError(f"agent is not installed: {agent}")
        provider = provider if isinstance(provider, dict) else {}
        provider_type = str(provider.get("type", "")).strip() or None
        provider_config_id = str(provider.get("id", "")).strip() or None
        if bool(provider_type) != bool(provider_config_id):
            raise RuntimeError("provider id and type must be supplied together")
        if not provider_supported(agent, provider_type):
            raise RuntimeError(f"provider {provider_type} is not compatible with {agent}")
        session_id = uuid.uuid4().hex[:12]
        if cwd:
            path = pathlib.Path(cwd).expanduser().resolve()
            if not path.is_dir():
                raise RuntimeError(f"working directory does not exist: {path}")
        else:
            path = bridge_home() / "workspaces" / session_id
            path.mkdir(parents=True, exist_ok=True)
        created_at = int(time.time() * 1000)
        session = Session(
            session_id,
            agent,
            str(path),
            f"{agent} · {path.name}",
            model,
            provider_type,
            provider_config_id,
            None,
            created_at,
        )
        self.sessions[session_id] = session
        self.db.execute(
            "INSERT INTO sessions(id,agent,cwd,title,model,provider,provider_config_id,resume_id,created_at) VALUES(?,?,?,?,?,?,?,?,?)",
            (
                session.id,
                session.agent,
                session.cwd,
                session.title,
                session.model,
                session.provider,
                session.provider_config_id,
                session.resume_id,
                session.created_at,
            ),
        )
        self.db.commit()
        return self.session_info(session)

    def messages(self, session_id: str) -> List[Dict[str, Any]]:
        self.require(session_id)
        rows = self.db.execute("SELECT seq,role,text,meta,ts FROM messages WHERE session_id=? ORDER BY seq", (session_id,))
        result = []
        for row in rows:
            item = {
                "role": row["role"],
                "text": row["text"],
                "ts": row["ts"],
                "messageId": f'{session_id}:{row["seq"]}',
            }
            try:
                item.update(json.loads(row["meta"]))
            except json.JSONDecodeError:
                pass
            result.append(item)
        return result

    def add_message(self, session_id: str, role: str, text: str, **meta: Any) -> None:
        seq = self.db.execute("SELECT COALESCE(MAX(seq),-1)+1 FROM messages WHERE session_id=?", (session_id,)).fetchone()[0]
        self.db.execute(
            "INSERT INTO messages(session_id,seq,role,text,meta,ts) VALUES(?,?,?,?,?,?)",
            (session_id, seq, role, text, json.dumps(meta, ensure_ascii=False), int(time.time() * 1000)),
        )
        self.db.commit()

    def require(self, session_id: str) -> Session:
        session = self.sessions.get(session_id)
        if not session:
            raise RuntimeError("session not found")
        return session

    async def start_prompt(
        self,
        session_id: str,
        message: str,
        attachments: List[Dict[str, Any]],
        provider: Optional[Dict[str, Any]] = None,
    ) -> str:
        session = self.require(session_id)
        if session.busy:
            raise RuntimeError("session is busy")
        if not message.strip():
            raise RuntimeError("message is empty")
        provider = provider or {}
        provider_id = str(provider.get("id", "")).strip() or None
        provider_type = str(provider.get("type", "")).strip() or None
        if provider_id != session.provider_config_id or provider_type != session.provider:
            raise RuntimeError("session provider does not match the selected provider")
        api_key = str(provider.get("apiKey", "")).strip() or None
        if provider_id and not api_key:
            raise RuntimeError(f"API key is missing for provider: {provider_id}")
        base_url = str(provider.get("baseUrl", "")).strip() or None
        message_count = self.db.execute("SELECT COUNT(*) FROM messages WHERE session_id=?", (session.id,)).fetchone()[0]
        if message_count == 0:
            generated_title = " ".join(message.strip().splitlines()[0].split())[:60]
            if generated_title:
                session.title = generated_title
                self.db.execute("UPDATE sessions SET title=? WHERE id=?", (generated_title, session.id))
                self.db.commit()
        session.busy = True
        session.stream_text = ""
        session.current_turn_id = uuid.uuid4().hex[:12]
        session.current_reasoning_id = None
        session.current_answer_id = None
        session.activity_sequence = 0
        session.pending_tools.clear()
        safe_attachments = []
        for attachment in attachments:
            path = str(attachment.get("path", "")).strip()
            name = str(attachment.get("name", "")).strip()
            if path and name:
                safe_attachments.append({"name": name, "path": path, "size": int(attachment.get("size", 0))})
        self.add_message(
            session.id,
            "user",
            message,
            messageId=uuid.uuid4().hex[:12],
            turnId=session.current_turn_id,
            attachments=safe_attachments,
        )
        await self.broadcast({"type": "busy", "sessionId": session.id, "busy": True})
        prompt = message
        if safe_attachments:
            references = "\n".join(f'- {item["name"]}: {item["path"]}' for item in safe_attachments)
            prompt += "\n\nFiles attached by the user (paths are relative to the working directory):\n" + references
        session.task = asyncio.create_task(self._run_prompt(session, prompt, api_key, base_url))
        return session.current_turn_id

    def rename(self, session_id: str, title: str) -> Dict[str, Any]:
        session = self.require(session_id)
        clean = title.strip()[:120]
        if not clean:
            raise RuntimeError("session title is empty")
        session.title = clean
        self.db.execute("UPDATE sessions SET title=? WHERE id=?", (clean, session.id))
        self.db.commit()
        return self.session_info(session)

    async def _run_prompt(
        self,
        session: Session,
        message: str,
        api_key: Optional[str],
        base_url: Optional[str],
    ) -> None:
        adapter = ADAPTERS[session.agent]
        state = AgentRun(session.agent, session.cwd, session.model, session.resume_id, session.provider, api_key, base_url)
        session.run = state
        settled_emitted = False

        async def emit(event: Dict[str, Any]) -> None:
            nonlocal settled_emitted
            event = dict(event)
            event_type = event.get("type")
            if event_type == "settled":
                settled_emitted = True
            now = int(time.time() * 1000)
            turn_id = session.current_turn_id or uuid.uuid4().hex[:12]

            def finish_pending_thinking() -> None:
                activity_id = session.current_reasoning_id
                if not activity_id:
                    return
                self.add_message(
                    session.id,
                    "thinking",
                    "",
                    messageId=activity_id,
                    turnId=turn_id,
                    activityId=activity_id,
                    sequence=session.activity_sequence,
                    agent=session.agent,
                )
                session.current_reasoning_id = None

            event["turnId"] = turn_id
            event.setdefault("ts", now)
            if event_type in ("thinking_start", "thinking_delta", "thinking"):
                if not session.current_reasoning_id:
                    session.current_reasoning_id = uuid.uuid4().hex[:12]
                    session.current_answer_id = None
                    session.activity_sequence += 1
                event["activityId"] = session.current_reasoning_id
                event["sequence"] = session.activity_sequence
            elif event_type in ("tool_start", "tool_end"):
                activity_id = str(event.get("toolId") or uuid.uuid4().hex[:12])
                event["activityId"] = activity_id
                if event_type == "tool_start":
                    finish_pending_thinking()
                    session.current_answer_id = None
                    session.activity_sequence += 1
                    session.pending_tools[activity_id] = {
                        "input": str(event.get("args", "")),
                        "startedAt": now,
                        "sequence": session.activity_sequence,
                    }
                pending_tool = session.pending_tools.get(activity_id, {})
                event["sequence"] = int(pending_tool.get("sequence", session.activity_sequence))
            elif event_type in ("delta", "assistant_message"):
                if not session.current_answer_id:
                    finish_pending_thinking()
                    session.current_answer_id = uuid.uuid4().hex[:12]
                event["activityId"] = session.current_answer_id
            if event_type == "delta":
                session.stream_text += str(event.get("text", ""))
            elif event_type == "assistant_message":
                text = str(event.get("text", ""))
                if text:
                    self.add_message(
                        session.id,
                        "assistant",
                        text,
                        messageId=session.current_answer_id,
                        turnId=turn_id,
                        activityId=session.current_answer_id,
                        agent=session.agent,
                    )
                    session.stream_text = ""
                    session.current_answer_id = None
            elif event_type == "thinking" and event.get("text"):
                self.add_message(
                    session.id,
                    "thinking",
                    str(event["text"]),
                    messageId=session.current_reasoning_id,
                    turnId=turn_id,
                    activityId=session.current_reasoning_id,
                    sequence=event.get("sequence"),
                    agent=session.agent,
                )
                session.current_reasoning_id = None
            elif event_type == "tool_end":
                activity_id = str(event.get("activityId"))
                pending_tool = session.pending_tools.pop(activity_id, {})
                event["toolInput"] = pending_tool.get("input", "")
                event["startedAt"] = pending_tool.get("startedAt", now)
                event["completedAt"] = now
                self.add_message(
                    session.id,
                    "tool",
                    str(event.get("output", "")),
                    messageId=activity_id,
                    turnId=turn_id,
                    activityId=activity_id,
                    sequence=event.get("sequence"),
                    agent=session.agent,
                    toolName=event.get("name"),
                    toolInput=pending_tool.get("input", ""),
                    startedAt=pending_tool.get("startedAt", now),
                    completedAt=now,
                    isError=event.get("isError", False),
                )
            elif event_type == "error":
                finish_pending_thinking()
                self.add_message(
                    session.id,
                    "error",
                    str(event.get("message", "Agent failed")),
                    messageId=uuid.uuid4().hex[:12],
                    turnId=turn_id,
                    agent=session.agent,
                )
            await self.broadcast({"type": "event", "sessionId": session.id, "event": event})

        def persist_stream_text() -> None:
            if not session.stream_text:
                return
            self.add_message(
                session.id,
                "assistant",
                session.stream_text,
                messageId=session.current_answer_id or uuid.uuid4().hex[:12],
                turnId=session.current_turn_id,
                activityId=session.current_answer_id,
                agent=session.agent,
            )
            session.stream_text = ""

        try:
            await emit({"type": "thinking_start"})
            await adapter.run(state, message, emit)
            persist_stream_text()
        except Exception as exc:
            persist_stream_text()
            await emit({"type": "error", "message": str(exc)})
        finally:
            if not settled_emitted:
                await emit({"type": "settled"})
            session.resume_id = state.resume_id
            self.db.execute("UPDATE sessions SET resume_id=? WHERE id=?", (session.resume_id, session.id))
            self.db.commit()
            session.busy = False
            session.run = None
            session.task = None
            session.current_reasoning_id = None
            session.current_answer_id = None
            session.pending_tools.clear()
            await self.broadcast({"type": "busy", "sessionId": session.id, "busy": False})

    async def abort(self, session_id: str) -> None:
        session = self.require(session_id)
        task = session.task
        if session.run:
            await ADAPTERS[session.agent].abort(session.run)
        elif task and not task.done():
            task.cancel()
        if task and task is not asyncio.current_task():
            await asyncio.gather(task, return_exceptions=True)

    async def remove(self, session_id: str) -> None:
        self.require(session_id)
        await self.abort(session_id)
        self.sessions.pop(session_id, None)
        self.db.execute("DELETE FROM sessions WHERE id=?", (session_id,))
        self.db.commit()
        await self.broadcast({"type": "session_deleted", "sessionId": session_id})

    async def _install_agent(self, agent: str) -> None:
        self.installing.add(agent)
        try:
            await install_agent(agent, lambda event: self.broadcast({"type": "event", "event": event}))
        except Exception as exc:
            await self.broadcast(
                {
                    "type": "event",
                    "event": {"type": "install_error", "agent": agent, "message": str(exc)},
                }
            )
        finally:
            self.installing.discard(agent)

    async def dispatch(self, method: str, params: Dict[str, Any]) -> Any:
        if method == "agents.list":
            return {"agents": list_agents()}
        if method == "agents.installPlan":
            return install_plan(str(params.get("agent", "")))
        if method == "agents.install":
            agent = str(params.get("agent", ""))
            if agent not in ADAPTERS:
                raise RuntimeError(f"agent is not supported: {agent}")
            if agent in self.installing:
                return {"accepted": True, "alreadyRunning": True}
            asyncio.create_task(self._install_agent(agent))
            return {"accepted": True}
        if method == "sessions.list":
            return {"sessions": self.list_sessions()}
        if method == "sessions.create":
            return self.create(
                str(params.get("agent", "")),
                params.get("cwd"),
                params.get("model"),
                params.get("provider") if isinstance(params.get("provider"), dict) else None,
            )
        if method == "sessions.get":
            session = self.require(str(params.get("sessionId", "")))
            return {**self.session_info(session), "messages": self.messages(session.id)}
        if method == "sessions.delete":
            await self.remove(str(params.get("sessionId", "")))
            return {"ok": True}
        if method == "sessions.rename":
            return self.rename(str(params.get("sessionId", "")), str(params.get("title", "")))
        if method == "prompt.send":
            attachments = params.get("attachments")
            if not isinstance(attachments, list):
                attachments = []
            provider = params.get("provider")
            if not isinstance(provider, dict):
                provider = {}
            turn_id = await self.start_prompt(
                str(params.get("sessionId", "")),
                str(params.get("message", "")),
                [item for item in attachments if isinstance(item, dict)],
                provider,
            )
            return {"accepted": True, "turnId": turn_id}
        if method == "prompt.abort":
            await self.abort(str(params.get("sessionId", "")))
            return {"ok": True}
        raise RuntimeError(f"unknown method: {method}")
