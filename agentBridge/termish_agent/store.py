from __future__ import annotations

import asyncio
import json
import pathlib
import re
import sqlite3
import time
import uuid
from collections import deque
from dataclasses import dataclass, field
from typing import Any, Awaitable, Callable, Deque, Dict, List, Optional

from adapters import (
    ADAPTERS,
    AgentRun,
    AgentReadiness,
    bridge_home,
    fetch_models,
    install_agent,
    install_plan,
    list_agents,
    provider_supported,
    resolve_binary,
)
from native_history import NativeSession, find_native_session, list_native_sessions


Broadcast = Callable[[Dict[str, Any]], Awaitable[None]]
EVENT_JOURNAL_LIMIT = 2048
DEFAULT_MESSAGE_PAGE_SIZE = 120
MAX_MESSAGE_PAGE_SIZE = 300
ATTACHMENT_DIRECTORY = ".termish/attachments"
ATTACHMENT_TTL_SECONDS = 30 * 24 * 60 * 60
_SENSITIVE_ASSIGNMENT = re.compile(
    r'(?i)(["\']?(?:api[_-]?key|access[_-]?token|auth[_-]?token|authorization|password|secret)'
    r'["\']?\s*[:=]\s*["\']?)([^"\'\s,;}]+)'
)
_BEARER_TOKEN = re.compile(r"(?i)(bearer\s+)([^\s,;\"']+)")
_ARTIFACT_EXTENSIONS = {
    ".csv": "spreadsheet",
    ".doc": "document",
    ".docx": "document",
    ".gif": "image",
    ".htm": "web",
    ".html": "web",
    ".jpeg": "image",
    ".jpg": "image",
    ".markdown": "markdown",
    ".md": "markdown",
    ".odp": "presentation",
    ".ods": "spreadsheet",
    ".odt": "document",
    ".pdf": "pdf",
    ".png": "image",
    ".ppt": "presentation",
    ".pptx": "presentation",
    ".rtf": "document",
    ".svg": "image",
    ".webp": "image",
    ".xls": "spreadsheet",
    ".xlsm": "spreadsheet",
    ".xlsx": "spreadsheet",
}
_ARTIFACT_SUFFIX_PATTERN = "|".join(
    re.escape(value[1:]) for value in sorted(_ARTIFACT_EXTENSIONS, key=len, reverse=True)
)
_QUOTED_ARTIFACT_PATH = re.compile(
    rf"""(?ix)(?:"([^"\n]+\.(?:{_ARTIFACT_SUFFIX_PATTERN}))"|'([^'\n]+\.(?:{_ARTIFACT_SUFFIX_PATTERN}))'|`([^`\n]+\.(?:{_ARTIFACT_SUFFIX_PATTERN}))`)"""
)
_PLAIN_ARTIFACT_PATH = re.compile(
    rf'''(?ix)((?:~?/|\.{1,2}/)?[^\s"'`<>|]+\.(?:{_ARTIFACT_SUFFIX_PATTERN}))'''
)


def detect_artifacts(cwd: str, texts: List[str], started_at_ms: int) -> List[Dict[str, Any]]:
    """Find real document-like files created or modified by one tool activity."""
    candidates: List[str] = []
    for text in texts:
        value = str(text)
        for match in _QUOTED_ARTIFACT_PATH.finditer(value):
            candidates.append(next(group for group in match.groups() if group is not None))
        candidates.extend(match.group(1) for match in _PLAIN_ARTIFACT_PATH.finditer(value))

    artifacts: List[Dict[str, Any]] = []
    seen: set[str] = set()
    base = pathlib.Path(cwd).expanduser()
    for candidate in candidates:
        raw = candidate.strip().strip("()[]{}<>,;:")
        if raw.startswith("file://"):
            raw = raw[7:]
        path = pathlib.Path(raw).expanduser()
        if not path.is_absolute():
            path = base / path
        try:
            resolved = path.resolve()
            stat = resolved.stat()
        except (OSError, RuntimeError):
            continue
        suffix = resolved.suffix.lower()
        if not resolved.is_file() or suffix not in _ARTIFACT_EXTENSIONS:
            continue
        # Filesystems with one-second mtime precision need a small tolerance.
        if int(stat.st_mtime * 1000) + 2_000 < started_at_ms:
            continue
        key = str(resolved)
        if key in seen:
            continue
        seen.add(key)
        artifacts.append(
            {
                "name": resolved.name,
                "path": key,
                "size": stat.st_size,
                "kind": _ARTIFACT_EXTENSIONS[suffix],
            }
        )
    return artifacts


def redact_sensitive_text(text: str, secrets: tuple[Optional[str], ...] = ()) -> str:
    """Remove credentials from errors, tool output, diagnostics, and persisted messages."""
    redacted = str(text)
    for secret in secrets:
        if secret and len(secret) >= 4:
            redacted = redacted.replace(secret, "[REDACTED]")
    redacted = _BEARER_TOKEN.sub(r"\1[REDACTED]", redacted)
    redacted = _SENSITIVE_ASSIGNMENT.sub(r"\1[REDACTED]", redacted)
    return redacted


def redact_sensitive_payload(value: Any, secrets: tuple[Optional[str], ...] = ()) -> Any:
    if isinstance(value, str):
        return redact_sensitive_text(value, secrets)
    if isinstance(value, dict):
        return {key: redact_sensitive_payload(item, secrets) for key, item in value.items()}
    if isinstance(value, list):
        return [redact_sensitive_payload(item, secrets) for item in value]
    if isinstance(value, tuple):
        return tuple(redact_sensitive_payload(item, secrets) for item in value)
    return value


def sensitive_values(value: Any) -> tuple[Optional[str], ...]:
    """Collect only explicitly credential-shaped request fields for exact redaction."""
    found: List[Optional[str]] = []

    def visit(item: Any) -> None:
        if isinstance(item, dict):
            for key, child in item.items():
                normalized = str(key).lower().replace("_", "").replace("-", "")
                if normalized in {
                    "apikey",
                    "accesstoken",
                    "authtoken",
                    "authorization",
                    "password",
                    "secret",
                }:
                    if isinstance(child, str):
                        found.append(child)
                else:
                    visit(child)
        elif isinstance(item, (list, tuple)):
            for child in item:
                visit(child)

    visit(value)
    return tuple(found)


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
    prompt_lock: asyncio.Lock = field(default_factory=asyncio.Lock)
    stream_text: str = ""
    current_turn_id: Optional[str] = None
    current_reasoning_id: Optional[str] = None
    current_reasoning_text: str = ""
    current_answer_id: Optional[str] = None
    activity_sequence: int = 0
    pending_tools: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    pending_approvals: Dict[str, Dict[str, Any]] = field(default_factory=dict)
    approval_futures: Dict[str, asyncio.Future] = field(default_factory=dict)
    event_seq: int = 0
    event_journal: Deque[Dict[str, Any]] = field(
        default_factory=lambda: deque(maxlen=EVENT_JOURNAL_LIMIT)
    )


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
        self.event_epoch = uuid.uuid4().hex[:12]
        self._restore()
        self._cleanup_stale_attachments()

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

    def _imported_native_session(self, native: NativeSession) -> Optional[Session]:
        return next(
            (
                session
                for session in self.sessions.values()
                if session.agent == native.agent and session.resume_id == native.resume_id
            ),
            None,
        )

    async def list_native_history(self) -> List[Dict[str, Any]]:
        opencode_binary = resolve_binary("opencode")
        native_sessions = await asyncio.to_thread(list_native_sessions, opencode_binary)
        result = []
        for native in native_sessions:
            imported = self._imported_native_session(native)
            result.append(
                {
                    **native.info(),
                    "importedSessionId": imported.id if imported else None,
                }
            )
        return result

    async def import_native_history(self, agent: str, native_id: str) -> Dict[str, Any]:
        if agent not in ADAPTERS:
            raise RuntimeError(f"agent is not supported: {agent}")
        opencode_binary = resolve_binary("opencode")
        native = await asyncio.to_thread(find_native_session, agent, native_id, opencode_binary)
        if not native:
            raise RuntimeError("native Agent session was not found")
        imported = self._imported_native_session(native)
        if imported:
            return self.session_info(imported)

        session_id = uuid.uuid4().hex[:12]
        session = Session(
            session_id,
            native.agent,
            native.cwd,
            native.title,
            None,
            None,
            None,
            native.resume_id,
            native.updated_at or native.created_at or int(time.time() * 1000),
        )
        try:
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
            self.db.executemany(
                "INSERT INTO messages(session_id,seq,role,text,meta,ts) VALUES(?,?,?,?,?,?)",
                [
                    (
                        session.id,
                        index,
                        message.role,
                        message.text,
                        json.dumps(
                            {
                                "messageId": f"native-{index}",
                                "agent": session.agent,
                                "nativeImported": True,
                            },
                            ensure_ascii=False,
                        ),
                        message.timestamp or session.created_at,
                    )
                    for index, message in enumerate(native.messages)
                ],
            )
            self.db.commit()
        except Exception:
            self.db.rollback()
            raise
        self.sessions[session.id] = session
        return self.session_info(session)

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
            "eventEpoch": self.event_epoch,
            "eventCursor": session.event_seq,
            "waitingApproval": bool(session.pending_approvals),
        }

    async def _publish(self, session: Session, envelope: Dict[str, Any]) -> None:
        session.event_seq += 1
        published = dict(envelope)
        published["eventEpoch"] = self.event_epoch
        published["eventSeq"] = session.event_seq
        session.event_journal.append(published)
        await self.broadcast(published)

    def replay_events(
        self,
        session_id: str,
        event_epoch: Optional[str],
        after: int,
    ) -> Dict[str, Any]:
        session = self.require(session_id)
        cursor = session.event_seq
        if event_epoch != self.event_epoch:
            return {
                "eventEpoch": self.event_epoch,
                "eventCursor": cursor,
                "reset": True,
                "events": [],
            }
        oldest = session.event_journal[0]["eventSeq"] if session.event_journal else cursor + 1
        if after < oldest - 1 or after > cursor:
            return {
                "eventEpoch": self.event_epoch,
                "eventCursor": cursor,
                "reset": True,
                "events": [],
            }
        return {
            "eventEpoch": self.event_epoch,
            "eventCursor": cursor,
            "reset": False,
            "events": [event for event in session.event_journal if event["eventSeq"] > after],
        }

    def set_model(self, session_id: str, model: Optional[str]) -> Dict[str, Any]:
        session = self.require(session_id)
        if session.busy:
            raise RuntimeError("session is busy")
        session.model = str(model).strip() or None if model is not None else None
        self.db.execute("UPDATE sessions SET model=? WHERE id=?", (session.model, session.id))
        self.db.commit()
        return self.session_info(session)

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
        anthropic_base_url = str(provider.get("anthropicBaseUrl", "")).strip() or None
        if bool(provider_type) != bool(provider_config_id):
            raise RuntimeError("provider id and type must be supplied together")
        if not provider_supported(agent, provider_type, anthropic_base_url):
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

    def messages_page(
        self,
        session_id: str,
        before_seq: Optional[int] = None,
        limit: int = DEFAULT_MESSAGE_PAGE_SIZE,
        enforce_maximum: bool = True,
    ) -> Dict[str, Any]:
        session = self.require(session_id)
        page_size = max(1, min(int(limit), MAX_MESSAGE_PAGE_SIZE) if enforce_maximum else int(limit))
        query = "SELECT seq,role,text,meta,ts FROM messages WHERE session_id=?"
        values: List[Any] = [session_id]
        if before_seq is not None:
            query += " AND seq<?"
            values.append(before_seq)
        query += " ORDER BY seq DESC LIMIT ?"
        values.append(page_size + 1)
        rows = list(self.db.execute(query, values))
        has_more = len(rows) > page_size
        rows = list(reversed(rows[:page_size]))
        result = []
        for row in rows:
            item = {
                "role": row["role"],
                "text": row["text"],
                "ts": row["ts"],
                "messageId": f'{session_id}:{row["seq"]}',
                "messageSeq": row["seq"],
            }
            try:
                item.update(json.loads(row["meta"]))
            except json.JSONDecodeError:
                pass
            result.append(item)
        if before_seq is None and session.busy:
            result.extend(self._active_messages(session))
        return {
            "messages": result,
            "hasMoreMessages": has_more,
            "oldestMessageSeq": rows[0]["seq"] if rows else None,
        }

    def messages(self, session_id: str) -> List[Dict[str, Any]]:
        """Compatibility helper used by tests and older internal callers."""
        session = self.require(session_id)
        count = self.db.execute("SELECT COUNT(*) FROM messages WHERE session_id=?", (session.id,)).fetchone()[0]
        return self.messages_page(session_id, limit=max(1, count), enforce_maximum=False)["messages"]

    @staticmethod
    def _safe_attachment_path(session: Session, relative_path: str) -> Optional[pathlib.Path]:
        relative = pathlib.PurePosixPath(relative_path)
        expected = pathlib.PurePosixPath(ATTACHMENT_DIRECTORY)
        if relative.parent != expected or relative.name in {"", ".", ".."}:
            return None
        root = (pathlib.Path(session.cwd).expanduser() / ATTACHMENT_DIRECTORY).resolve()
        candidate = (pathlib.Path(session.cwd).expanduser() / pathlib.Path(*relative.parts)).resolve()
        if candidate.parent != root:
            return None
        return candidate

    def _attachment_paths(self, session: Session) -> List[pathlib.Path]:
        paths: List[pathlib.Path] = []
        rows = self.db.execute("SELECT meta FROM messages WHERE session_id=?", (session.id,))
        for row in rows:
            try:
                attachments = json.loads(row["meta"]).get("attachments", [])
            except (AttributeError, json.JSONDecodeError):
                continue
            for attachment in attachments:
                if not isinstance(attachment, dict):
                    continue
                path = self._safe_attachment_path(session, str(attachment.get("path", "")))
                if path is not None and path not in paths:
                    paths.append(path)
        return paths

    def _referenced_attachment_paths(self, excluding_session_id: Optional[str] = None) -> set[pathlib.Path]:
        referenced: set[pathlib.Path] = set()
        for candidate in self.sessions.values():
            if candidate.id == excluding_session_id:
                continue
            referenced.update(self._attachment_paths(candidate))
        return referenced

    def _cleanup_stale_attachments(self) -> None:
        referenced = self._referenced_attachment_paths()
        roots = {
            (pathlib.Path(session.cwd).expanduser() / ATTACHMENT_DIRECTORY).resolve()
            for session in self.sessions.values()
        }
        cutoff = time.time() - ATTACHMENT_TTL_SECONDS
        for root in roots:
            try:
                candidates = list(root.iterdir())
            except OSError:
                continue
            for path in candidates:
                try:
                    if path not in referenced and not path.is_dir() and path.stat().st_mtime < cutoff:
                        path.unlink(missing_ok=True)
                except OSError:
                    pass

    def _remove_session_attachments(self, session: Session) -> None:
        referenced_elsewhere = self._referenced_attachment_paths(excluding_session_id=session.id)
        roots: set[pathlib.Path] = set()
        for path in self._attachment_paths(session):
            roots.add(path.parent)
            if path in referenced_elsewhere:
                continue
            try:
                path.unlink(missing_ok=True)
            except OSError:
                pass
        for root in roots:
            try:
                root.rmdir()
            except OSError:
                pass

    def approvals(self, session_id: str) -> List[Dict[str, Any]]:
        session = self.require(session_id)
        return list(session.pending_approvals.values())

    def _active_messages(self, session: Session) -> List[Dict[str, Any]]:
        """Return an atomic snapshot of in-flight activities for reconnecting clients."""
        turn_id = session.current_turn_id
        active: List[Dict[str, Any]] = []
        if session.current_reasoning_id and session.current_reasoning_text:
            active.append(
                {
                    "role": "thinking",
                    "text": session.current_reasoning_text,
                    "messageId": session.current_reasoning_id,
                    "turnId": turn_id,
                    "activityId": session.current_reasoning_id,
                    "sequence": session.activity_sequence,
                    "agent": session.agent,
                    "running": True,
                }
            )
        for activity_id, tool in sorted(
            session.pending_tools.items(),
            key=lambda item: int(item[1].get("sequence", 0)),
        ):
            active.append(
                {
                    "role": "tool",
                    "text": "",
                    "messageId": activity_id,
                    "turnId": turn_id,
                    "activityId": activity_id,
                    "sequence": tool.get("sequence"),
                    "agent": session.agent,
                    "toolName": tool.get("name"),
                    "toolInput": tool.get("input", ""),
                    "startedAt": tool.get("startedAt"),
                    "running": True,
                }
            )
        if session.current_answer_id and session.stream_text:
            active.append(
                {
                    "role": "assistant",
                    "text": session.stream_text,
                    "messageId": session.current_answer_id,
                    "turnId": turn_id,
                    "activityId": session.current_answer_id,
                    "agent": session.agent,
                    "running": True,
                }
            )
        return active

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

    async def check_readiness(
        self,
        session_id: str,
        provider: Optional[Dict[str, Any]] = None,
    ) -> Dict[str, Any]:
        session = self.require(session_id)
        provider = provider or {}
        provider_id = str(provider.get("id", "")).strip() or None
        provider_type = str(provider.get("type", "")).strip() or None
        if provider_id != session.provider_config_id or provider_type != session.provider:
            raise RuntimeError("session provider does not match the selected provider")
        api_key = str(provider.get("apiKey", "")).strip() or None
        if provider_id and not api_key:
            raise RuntimeError(f"API key is missing for provider: {provider_id}")
        base_url = str(provider.get("baseUrl", "")).strip() or None
        pi_provider = str(provider.get("piProvider", "")).strip() or None
        anthropic_base_url = str(provider.get("anthropicBaseUrl", "")).strip() or None
        readiness_state = AgentRun(
            agent=session.agent,
            cwd=session.cwd,
            model=session.model,
            resume_id=session.resume_id,
            provider=session.provider_config_id,
            api_key=api_key,
            base_url=base_url,
            provider_type=session.provider,
            pi_provider=pi_provider,
            anthropic_base_url=anthropic_base_url,
        )
        adapter = ADAPTERS[session.agent]
        readiness_method = getattr(adapter, "readiness", None)
        readiness = (
            await readiness_method(readiness_state)
            if callable(readiness_method)
            else AgentReadiness("ready")
        )
        if readiness.status in {"not_installed", "login_required"}:
            message = readiness.message or f"{session.agent} is not ready"
            raise RuntimeError(redact_sensitive_text(message, (api_key,)))
        return {
            "ready": True,
            "status": readiness.status,
            "apiKey": api_key,
            "baseUrl": base_url,
            "piProvider": pi_provider,
            "anthropicBaseUrl": anthropic_base_url,
        }

    async def start_prompt(
        self,
        session_id: str,
        message: str,
        attachments: List[Dict[str, Any]],
        provider: Optional[Dict[str, Any]] = None,
    ) -> str:
        session = self.require(session_id)
        async with session.prompt_lock:
            if session.busy:
                raise RuntimeError("session is busy")
            if not message.strip():
                raise RuntimeError("message is empty")
            runtime = await self.check_readiness(session_id, provider)
            if self.sessions.get(session_id) is not session:
                raise RuntimeError("session not found")
            api_key = runtime["apiKey"]
            base_url = runtime["baseUrl"]
            pi_provider = runtime["piProvider"]
            anthropic_base_url = runtime["anthropicBaseUrl"]
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
            session.current_reasoning_text = ""
            session.current_answer_id = None
            session.activity_sequence = 0
            session.pending_tools.clear()
            session.pending_approvals.clear()
            session.approval_futures.clear()
            session.event_journal.clear()
            safe_attachments = []
            seen_attachment_paths = set()
            for attachment in attachments:
                path = str(attachment.get("path", "")).strip()
                name = str(attachment.get("name", "")).strip()
                if self._safe_attachment_path(session, path) is not None and name and path not in seen_attachment_paths:
                    seen_attachment_paths.add(path)
                    safe_attachments.append({"name": name, "path": path, "size": int(attachment.get("size", 0))})
            self.add_message(
                session.id,
                "user",
                message,
                messageId=uuid.uuid4().hex[:12],
                turnId=session.current_turn_id,
                attachments=safe_attachments,
            )
            await self._publish(session, {"type": "busy", "sessionId": session.id, "busy": True})
            prompt = message
            if safe_attachments:
                references = "\n".join(f'- {item["name"]}: {item["path"]}' for item in safe_attachments)
                prompt += "\n\nFiles attached by the user (paths are relative to the working directory):\n" + references
            session.task = asyncio.create_task(
                self._run_prompt(session, prompt, api_key, base_url, pi_provider, anthropic_base_url)
            )
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
        pi_provider: Optional[str] = None,
        anthropic_base_url: Optional[str] = None,
    ) -> None:
        adapter = ADAPTERS[session.agent]
        state = AgentRun(
            agent=session.agent,
            cwd=session.cwd,
            model=session.model,
            resume_id=session.resume_id,
            provider=session.provider_config_id,
            api_key=api_key,
            base_url=base_url,
            provider_type=session.provider,
            pi_provider=pi_provider,
            anthropic_base_url=anthropic_base_url,
        )
        session.run = state
        settled_emitted = False

        async def emit(event: Dict[str, Any]) -> None:
            nonlocal settled_emitted
            event = redact_sensitive_payload(dict(event), (api_key,))
            event_type = event.get("type")
            if event_type == "settled":
                settled_emitted = True
            now = int(time.time() * 1000)
            turn_id = session.current_turn_id or uuid.uuid4().hex[:12]

            def finish_pending_thinking() -> None:
                activity_id = session.current_reasoning_id
                if not activity_id:
                    return
                if session.current_reasoning_text:
                    self.add_message(
                        session.id,
                        "thinking",
                        session.current_reasoning_text,
                        messageId=activity_id,
                        turnId=turn_id,
                        activityId=activity_id,
                        sequence=session.activity_sequence,
                        agent=session.agent,
                    )
                session.current_reasoning_id = None
                session.current_reasoning_text = ""

            event["turnId"] = turn_id
            event.setdefault("ts", now)
            if event_type in ("thinking_start", "thinking_delta", "thinking"):
                if not session.current_reasoning_id:
                    # Agent 可能先输出一段可见说明，再进入思考/工具阶段。客户端
                    # 断开时必须能从数据库恢复这段说明，不能只留在内存 stream_text。
                    persist_stream_text()
                    session.current_reasoning_id = uuid.uuid4().hex[:12]
                    session.current_reasoning_text = ""
                    session.current_answer_id = None
                    session.activity_sequence += 1
                event["activityId"] = session.current_reasoning_id
                event["sequence"] = session.activity_sequence
                if event_type == "thinking_delta":
                    session.current_reasoning_text += str(event.get("text", ""))
                elif event_type == "thinking":
                    session.current_reasoning_text = str(event.get("text", ""))
            elif event_type in ("tool_start", "tool_end"):
                activity_id = str(event.get("toolId") or uuid.uuid4().hex[:12])
                event["activityId"] = activity_id
                if event_type == "tool_start":
                    finish_pending_thinking()
                    # Codex 常见顺序：delta（说明下一步）→ tool_start。旧实现此处
                    # 清掉 current_answer_id，却不落盘 stream_text；App 重连后便只
                    # 剩当前工具卡片，上方已经显示过的说明全部消失。
                    persist_stream_text()
                    session.current_answer_id = None
                    session.activity_sequence += 1
                    session.pending_tools[activity_id] = {
                        "name": str(event.get("name", "tool")),
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
                session.current_reasoning_text = ""
            elif event_type == "tool_end":
                activity_id = str(event.get("activityId"))
                pending_tool = session.pending_tools.pop(activity_id, {})
                event["toolInput"] = pending_tool.get("input", "")
                event["startedAt"] = pending_tool.get("startedAt", now)
                event["completedAt"] = now
                artifacts = (
                    []
                    if event.get("isError", False)
                    else detect_artifacts(
                        session.cwd,
                        [str(event["toolInput"]), str(event.get("output", ""))],
                        int(event["startedAt"]),
                    )
                )
                event["artifacts"] = artifacts
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
                    artifacts=artifacts,
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
            await self._publish(session, {"type": "event", "sessionId": session.id, "event": event})

        async def request_approval(request: Dict[str, Any]) -> Dict[str, Any]:
            approval_id = uuid.uuid4().hex[:12]
            public_request = {
                "approvalId": approval_id,
                "sessionId": session.id,
                "turnId": session.current_turn_id,
                "agent": session.agent,
                "kind": str(request.get("kind") or "confirm"),
                "title": str(request.get("title") or "Approval required"),
                "message": str(request.get("message") or ""),
                "command": str(request.get("command") or ""),
                "cwd": str(request.get("cwd") or session.cwd),
                "details": str(request.get("details") or ""),
                "placeholder": str(request.get("placeholder") or ""),
                "prefill": str(request.get("prefill") or ""),
                "options": [str(value) for value in request.get("options", [])],
                "secret": bool(request.get("secret", False)),
                "allowCustom": bool(request.get("allowCustom", False)),
                "timeoutMs": int(request.get("timeoutMs") or 0),
            }
            future = asyncio.get_running_loop().create_future()
            session.pending_approvals[approval_id] = public_request
            session.approval_futures[approval_id] = future
            await emit({"type": "approval_request", **public_request})
            try:
                timeout_ms = public_request["timeoutMs"]
                if timeout_ms > 0:
                    return await asyncio.wait_for(future, timeout=timeout_ms / 1000)
                return await future
            except asyncio.TimeoutError:
                return {"decision": "cancel"}
            finally:
                session.pending_approvals.pop(approval_id, None)
                session.approval_futures.pop(approval_id, None)
                await emit({"type": "approval_resolved", "approvalId": approval_id})

        state.approval_handler = request_approval

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
            session.current_reasoning_text = ""
            session.current_answer_id = None
            session.pending_tools.clear()
            for future in session.approval_futures.values():
                if not future.done():
                    future.set_result({"decision": "cancel"})
            session.pending_approvals.clear()
            session.approval_futures.clear()
            await self._publish(session, {"type": "busy", "sessionId": session.id, "busy": False})

    async def abort(self, session_id: str) -> None:
        session = self.require(session_id)
        task = session.task
        for future in session.approval_futures.values():
            if not future.done():
                future.set_result({"decision": "cancel"})
        if session.run:
            await ADAPTERS[session.agent].abort(session.run)
        elif task and not task.done():
            task.cancel()
        if task and task is not asyncio.current_task():
            await asyncio.gather(task, return_exceptions=True)

    async def respond_approval(
        self,
        session_id: str,
        approval_id: str,
        decision: str,
        value: Optional[str],
    ) -> None:
        session = self.require(session_id)
        future = session.approval_futures.get(approval_id)
        if future is None or future.done():
            raise RuntimeError("approval request is no longer pending")
        allowed = {"allow_once", "allow_session", "deny", "cancel", "submit", "select"}
        if decision not in allowed and decision not in session.pending_approvals[approval_id].get("options", []):
            raise RuntimeError("invalid approval decision")
        future.set_result({"decision": decision, "value": value})

    async def remove(self, session_id: str) -> None:
        session = self.require(session_id)
        async with session.prompt_lock:
            await self.abort(session_id)
            self._remove_session_attachments(session)
            self.sessions.pop(session_id, None)
            self.db.execute("DELETE FROM sessions WHERE id=?", (session_id,))
            self.db.commit()
            await self.broadcast({"type": "session_deleted", "sessionId": session_id})

    async def _install_agent(self, agent: str) -> None:
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
            return {"agents": list_agents(), "installing": sorted(self.installing)}
        if method == "agents.installPlan":
            return install_plan(str(params.get("agent", "")))
        if method == "agents.install":
            agent = str(params.get("agent", ""))
            if agent not in ADAPTERS:
                raise RuntimeError(f"agent is not supported: {agent}")
            if agent in self.installing:
                return {"accepted": True, "alreadyRunning": True}
            self.installing.add(agent)
            asyncio.create_task(self._install_agent(agent))
            return {"accepted": True}
        if method == "providers.fetchModels":
            base_url = str(params.get("baseUrl", "")).strip()
            api_key = str(params.get("apiKey", "")).strip()
            provider_type = str(params.get("type", "openai")).strip() or "openai"
            if not api_key:
                raise RuntimeError("API key is missing")
            loop = asyncio.get_running_loop()
            models = await loop.run_in_executor(None, fetch_models, base_url, api_key, provider_type)
            return {"models": models}
        if method == "sessions.list":
            return {"sessions": self.list_sessions()}
        if method == "sessions.nativeList":
            return {"sessions": await self.list_native_history()}
        if method == "sessions.nativeImport":
            return await self.import_native_history(
                str(params.get("agent", "")),
                str(params.get("nativeId", "")),
            )
        if method == "sessions.create":
            return self.create(
                str(params.get("agent", "")),
                params.get("cwd"),
                params.get("model"),
                params.get("provider") if isinstance(params.get("provider"), dict) else None,
            )
        if method == "sessions.get":
            session = self.require(str(params.get("sessionId", "")))
            before_seq = params.get("beforeSeq")
            page = self.messages_page(
                session.id,
                int(before_seq) if before_seq is not None else None,
                int(params.get("messageLimit", DEFAULT_MESSAGE_PAGE_SIZE)),
            )
            return {
                **self.session_info(session),
                **page,
                "approvals": self.approvals(session.id),
            }
        if method == "events.replay":
            return self.replay_events(
                str(params.get("sessionId", "")),
                str(params.get("eventEpoch", "")).strip() or None,
                int(params.get("after", 0)),
            )
        if method == "sessions.delete":
            await self.remove(str(params.get("sessionId", "")))
            return {"ok": True}
        if method == "sessions.rename":
            return self.rename(str(params.get("sessionId", "")), str(params.get("title", "")))
        if method == "sessions.model":
            return self.set_model(str(params.get("sessionId", "")), params.get("model"))
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
        if method == "agents.check":
            provider = params.get("provider")
            if not isinstance(provider, dict):
                provider = {}
            result = await self.check_readiness(str(params.get("sessionId", "")), provider)
            return {key: value for key, value in result.items() if key in {"ready", "status"}}
        if method == "prompt.abort":
            await self.abort(str(params.get("sessionId", "")))
            return {"ok": True}
        if method == "approval.respond":
            await self.respond_approval(
                str(params.get("sessionId", "")),
                str(params.get("approvalId", "")),
                str(params.get("decision", "")),
                str(params.get("value")) if params.get("value") is not None else None,
            )
            return {"ok": True}
        raise RuntimeError(f"unknown method: {method}")
