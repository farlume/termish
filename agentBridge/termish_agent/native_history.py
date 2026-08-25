"""Best-effort discovery and import of supported Agent CLI histories.

The Agent-owned files remain the source of truth for resume.  Termish only reads
their user/assistant text into its private Bridge database so every Agent can be
rendered by the same UI.  Unknown records are intentionally ignored: native
history formats evolve independently from the Bridge.
"""

from __future__ import annotations

import datetime
import json
import os
import pathlib
import subprocess
import time
from dataclasses import dataclass
from typing import Any, Dict, Iterable, List, Optional


NATIVE_HISTORY_LIMIT = 100
NATIVE_FILES_PER_AGENT = 80
NATIVE_MESSAGES_LIMIT = 1000
NATIVE_CACHE_SECONDS = 5.0
_native_cache: Dict[tuple[str, Optional[str]], tuple[float, List["NativeSession"]]] = {}


@dataclass(frozen=True)
class NativeMessage:
    role: str
    text: str
    timestamp: int


@dataclass(frozen=True)
class NativeSession:
    agent: str
    native_id: str
    resume_id: str
    title: str
    cwd: str
    created_at: int
    updated_at: int
    messages: List[NativeMessage]

    def info(self) -> Dict[str, Any]:
        return {
            "agent": self.agent,
            "nativeId": self.native_id,
            "title": self.title,
            "cwd": self.cwd,
            "createdAt": self.created_at,
            "updatedAt": self.updated_at,
            "messageCount": len(self.messages),
        }


def _timestamp(value: Any, fallback: int = 0) -> int:
    if isinstance(value, (int, float)):
        number = int(value)
        return number * 1000 if 0 < number < 10_000_000_000 else number
    if not isinstance(value, str) or not value.strip():
        return fallback
    text = value.strip().replace("Z", "+00:00")
    try:
        parsed = datetime.datetime.fromisoformat(text)
        if parsed.tzinfo is None:
            parsed = parsed.replace(tzinfo=datetime.timezone.utc)
        return int(parsed.timestamp() * 1000)
    except ValueError:
        return fallback


def _file_timestamp(path: pathlib.Path) -> int:
    try:
        return int(path.stat().st_mtime * 1000)
    except OSError:
        return 0


def _content_text(content: Any, text_fields: Iterable[str] = ("text",)) -> str:
    if isinstance(content, str):
        return content.strip()
    if not isinstance(content, list):
        return ""
    values: List[str] = []
    for block in content:
        if not isinstance(block, dict):
            continue
        for field in text_fields:
            value = block.get(field)
            if isinstance(value, str) and value.strip():
                values.append(value.strip())
                break
    return "\n\n".join(values).strip()


def _title(messages: List[NativeMessage], fallback: str) -> str:
    first = next((item.text for item in messages if item.role == "user" and item.text.strip()), "")
    clean = " ".join(first.split())
    return clean[:60] or fallback[:60]


def _append_message(
    messages: List[NativeMessage],
    role: str,
    text: str,
    timestamp: int,
    merge_consecutive: bool = False,
) -> None:
    clean = text.strip()
    if role not in {"user", "assistant"} or not clean:
        return
    if merge_consecutive and messages and messages[-1].role == role:
        previous = messages[-1]
        messages[-1] = NativeMessage(role, f"{previous.text}\n\n{clean}", timestamp or previous.timestamp)
        return
    if len(messages) < NATIVE_MESSAGES_LIMIT:
        messages.append(NativeMessage(role, clean, timestamp))


def _json_lines(path: pathlib.Path) -> Iterable[Dict[str, Any]]:
    try:
        with path.open("r", encoding="utf-8", errors="replace") as source:
            for line in source:
                if not line.strip():
                    continue
                try:
                    value = json.loads(line)
                except (TypeError, json.JSONDecodeError):
                    continue
                if isinstance(value, dict):
                    yield value
    except OSError:
        return


def _recent_files(root: pathlib.Path, patterns: Iterable[str]) -> List[pathlib.Path]:
    if not root.is_dir():
        return []
    found: Dict[str, pathlib.Path] = {}
    for pattern in patterns:
        try:
            for path in root.glob(pattern):
                if path.is_file():
                    found[str(path)] = path
        except OSError:
            continue
    return sorted(found.values(), key=_file_timestamp, reverse=True)[:NATIVE_FILES_PER_AGENT]


def _is_codex_context(text: str) -> bool:
    prefixes = (
        "<environment_context>",
        "<recommended_plugins>",
        "<permissions instructions>",
        "<skills_instructions>",
        "# AGENTS.md instructions",
    )
    chunks = [chunk.strip() for chunk in text.split("\n\n") if chunk.strip()]
    return bool(chunks) and all(chunk.startswith(prefixes) for chunk in chunks)


def _codex_sessions(home: pathlib.Path) -> List[NativeSession]:
    root = pathlib.Path(os.environ.get("CODEX_HOME", "")).expanduser() if os.environ.get("CODEX_HOME") else home / ".codex"
    result: List[NativeSession] = []
    for path in _recent_files(root / "sessions", ("**/rollout-*.jsonl",)):
        native_id = ""
        cwd = ""
        created_at = 0
        updated_at = _file_timestamp(path)
        messages: List[NativeMessage] = []
        for record in _json_lines(path):
            kind = record.get("type")
            payload = record.get("payload") if isinstance(record.get("payload"), dict) else {}
            timestamp = _timestamp(record.get("timestamp"), updated_at)
            if kind == "session_meta":
                native_id = str(payload.get("id") or payload.get("session_id") or native_id)
                cwd = str(payload.get("cwd") or cwd)
                created_at = _timestamp(payload.get("timestamp"), timestamp)
                continue
            if kind != "response_item" or payload.get("type") != "message":
                continue
            role = str(payload.get("role") or "")
            expected = "input_text" if role == "user" else "output_text"
            content = payload.get("content")
            blocks = [item for item in content if isinstance(item, dict) and item.get("type") == expected] if isinstance(content, list) else []
            text = _content_text(blocks)
            if role == "user" and _is_codex_context(text):
                continue
            _append_message(messages, role, text, timestamp)
            updated_at = max(updated_at, timestamp)
        if native_id and cwd and pathlib.Path(cwd).is_dir() and messages:
            result.append(
                NativeSession(
                    "codex",
                    native_id,
                    native_id,
                    _title(messages, path.stem),
                    cwd,
                    created_at or updated_at,
                    updated_at,
                    messages,
                )
            )
    return result


def _claude_sessions(home: pathlib.Path) -> List[NativeSession]:
    configured = os.environ.get("CLAUDE_CONFIG_DIR")
    root = pathlib.Path(configured).expanduser() if configured else home / ".claude"
    result: List[NativeSession] = []
    for path in _recent_files(root / "projects", ("**/*.jsonl",)):
        native_id = ""
        cwd = ""
        created_at = 0
        updated_at = _file_timestamp(path)
        messages: List[NativeMessage] = []
        for record in _json_lines(path):
            if record.get("isSidechain") is True:
                continue
            kind = str(record.get("type") or "")
            if kind not in {"user", "assistant"}:
                continue
            native_id = str(record.get("sessionId") or native_id or path.stem)
            cwd = str(record.get("cwd") or cwd)
            timestamp = _timestamp(record.get("timestamp"), updated_at)
            created_at = created_at or timestamp
            message = record.get("message") if isinstance(record.get("message"), dict) else {}
            role = str(message.get("role") or kind)
            text = _content_text(message.get("content"))
            _append_message(messages, role, text, timestamp, merge_consecutive=role == "assistant")
            updated_at = max(updated_at, timestamp)
        if native_id and cwd and pathlib.Path(cwd).is_dir() and messages:
            result.append(
                NativeSession(
                    "claude",
                    native_id,
                    native_id,
                    _title(messages, path.stem),
                    cwd,
                    created_at or updated_at,
                    updated_at,
                    messages,
                )
            )
    return result


def _pi_sessions(home: pathlib.Path) -> List[NativeSession]:
    configured = os.environ.get("PI_CODING_AGENT_SESSION_DIR")
    root = pathlib.Path(configured).expanduser() if configured else home / ".pi/agent/sessions"
    result: List[NativeSession] = []
    for path in _recent_files(root, ("**/*.jsonl",)):
        native_id = ""
        cwd = ""
        created_at = 0
        updated_at = _file_timestamp(path)
        messages: List[NativeMessage] = []
        for record in _json_lines(path):
            kind = str(record.get("type") or "")
            if kind == "session":
                native_id = str(record.get("id") or native_id)
                cwd = str(record.get("cwd") or cwd)
                created_at = _timestamp(record.get("timestamp"), updated_at)
                continue
            if kind != "message":
                continue
            message = record.get("message") if isinstance(record.get("message"), dict) else {}
            role = str(message.get("role") or "")
            timestamp = _timestamp(message.get("timestamp"), _timestamp(record.get("timestamp"), updated_at))
            text = _content_text(message.get("content"))
            _append_message(messages, role, text, timestamp, merge_consecutive=role == "assistant")
            updated_at = max(updated_at, timestamp)
        if native_id and cwd and pathlib.Path(cwd).is_dir() and messages:
            result.append(
                NativeSession(
                    "pi",
                    native_id,
                    str(path.resolve()),
                    _title(messages, path.stem),
                    cwd,
                    created_at or updated_at,
                    updated_at,
                    messages,
                )
            )
    return result


def _gemini_record(path: pathlib.Path) -> Optional[NativeSession]:
    metadata: Dict[str, Any] = {}
    message_order: List[str] = []
    message_map: Dict[str, Dict[str, Any]] = {}

    def replace_messages(values: Any) -> None:
        message_order.clear()
        message_map.clear()
        if not isinstance(values, list):
            return
        for item in values:
            if isinstance(item, dict) and item.get("id"):
                key = str(item["id"])
                message_order.append(key)
                message_map[key] = item

    try:
        if path.suffix == ".json":
            value = json.loads(path.read_text(encoding="utf-8", errors="replace"))
            if not isinstance(value, dict):
                return None
            metadata.update(value)
            replace_messages(value.get("messages"))
        else:
            for record in _json_lines(path):
                if record.get("$rewindTo"):
                    key = str(record["$rewindTo"])
                    index = message_order.index(key) if key in message_order else 0
                    for removed in message_order[index:]:
                        message_map.pop(removed, None)
                    del message_order[index:]
                elif isinstance(record.get("$set"), dict):
                    updates = record["$set"]
                    metadata.update(updates)
                    if "messages" in updates:
                        replace_messages(updates["messages"])
                elif record.get("id"):
                    key = str(record["id"])
                    if key not in message_map:
                        message_order.append(key)
                    message_map[key] = record
                elif record.get("sessionId"):
                    metadata.update(record)
    except (OSError, TypeError, json.JSONDecodeError):
        return None

    native_id = str(metadata.get("sessionId") or "")
    if not native_id:
        return None
    project_root_file = path.parent.parent / ".project_root"
    try:
        project_root = project_root_file.read_text(encoding="utf-8").strip()
    except OSError:
        project_root = ""
    directories = metadata.get("directories")
    cwd = project_root or (str(directories[0]) if isinstance(directories, list) and directories else "")
    if not cwd or not pathlib.Path(cwd).is_dir():
        return None
    messages: List[NativeMessage] = []
    for key in message_order:
        item = message_map[key]
        kind = str(item.get("type") or "")
        role = "assistant" if kind == "gemini" else kind
        text = _content_text(item.get("displayContent") or item.get("content"))
        _append_message(messages, role, text, _timestamp(item.get("timestamp"), _file_timestamp(path)))
    if not messages:
        return None
    created_at = _timestamp(metadata.get("startTime"), messages[0].timestamp)
    updated_at = _timestamp(metadata.get("lastUpdated"), _file_timestamp(path))
    summary = str(metadata.get("summary") or "")
    return NativeSession(
        "gemini",
        native_id,
        native_id,
        summary[:60] or _title(messages, path.stem),
        cwd,
        created_at,
        updated_at,
        messages,
    )


def _gemini_sessions(home: pathlib.Path) -> List[NativeSession]:
    result: List[NativeSession] = []
    root = home / ".gemini/tmp"
    for path in _recent_files(root, ("*/chats/session-*.jsonl", "*/chats/session-*.json")):
        session = _gemini_record(path)
        if session:
            result.append(session)
    return result


def _opencode_sessions(binary: Optional[str]) -> List[NativeSession]:
    if not binary:
        return []
    try:
        process = subprocess.run(
            [binary, "session", "list", "--format", "json", "--max-count", str(NATIVE_FILES_PER_AGENT)],
            capture_output=True,
            check=False,
            text=True,
            timeout=10,
        )
        values = json.loads(process.stdout) if process.returncode == 0 else []
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
        return []
    if not isinstance(values, list):
        return []
    result: List[NativeSession] = []
    for value in values:
        if not isinstance(value, dict):
            continue
        native_id = str(value.get("id") or value.get("sessionID") or "")
        cwd = str(value.get("directory") or value.get("path") or "")
        timing = value.get("time") if isinstance(value.get("time"), dict) else {}
        created_at = _timestamp(timing.get("created") or value.get("time_created"))
        updated_at = _timestamp(timing.get("updated") or value.get("time_updated"), created_at)
        if native_id and cwd and pathlib.Path(cwd).is_dir():
            result.append(
                NativeSession(
                    "opencode",
                    native_id,
                    native_id,
                    str(value.get("title") or f"OpenCode · {pathlib.Path(cwd).name}")[:60],
                    cwd,
                    created_at or updated_at,
                    updated_at or created_at,
                    [],
                )
            )
    return result


def _load_opencode_messages(session: NativeSession, binary: Optional[str]) -> NativeSession:
    if not binary:
        return session
    try:
        process = subprocess.run(
            [binary, "export", session.native_id],
            capture_output=True,
            check=False,
            text=True,
            timeout=15,
        )
        exported = json.loads(process.stdout) if process.returncode == 0 else {}
    except (OSError, subprocess.SubprocessError, json.JSONDecodeError):
        return session
    values = exported.get("messages") if isinstance(exported, dict) else None
    if not isinstance(values, list):
        return session
    messages: List[NativeMessage] = []
    for value in values:
        if not isinstance(value, dict):
            continue
        info = value.get("info") if isinstance(value.get("info"), dict) else value
        role = str(info.get("role") or "")
        timing = info.get("time") if isinstance(info.get("time"), dict) else {}
        parts = value.get("parts") if isinstance(value.get("parts"), list) else []
        text = _content_text([part for part in parts if isinstance(part, dict) and part.get("type") == "text"])
        _append_message(messages, role, text, _timestamp(timing.get("created")))
    return NativeSession(
        session.agent,
        session.native_id,
        session.resume_id,
        _title(messages, session.title),
        session.cwd,
        session.created_at,
        session.updated_at,
        messages,
    )


def list_native_sessions(
    opencode_binary: Optional[str] = None,
    *,
    force_refresh: bool = False,
) -> List[NativeSession]:
    home = pathlib.Path.home()
    cache_key = (str(home), opencode_binary)
    cached = _native_cache.get(cache_key)
    now = time.monotonic()
    if not force_refresh and cached and now - cached[0] < NATIVE_CACHE_SECONDS:
        return cached[1]
    sessions = [
        *_codex_sessions(home),
        *_claude_sessions(home),
        *_gemini_sessions(home),
        *_opencode_sessions(opencode_binary),
        *_pi_sessions(home),
    ]
    unique: Dict[tuple[str, str], NativeSession] = {}
    for session in sessions:
        key = (session.agent, session.native_id)
        previous = unique.get(key)
        if previous is None or session.updated_at > previous.updated_at:
            unique[key] = session
    result = sorted(unique.values(), key=lambda item: item.updated_at, reverse=True)[:NATIVE_HISTORY_LIMIT]
    _native_cache[cache_key] = (now, result)
    return result


def find_native_session(
    agent: str,
    native_id: str,
    opencode_binary: Optional[str] = None,
) -> Optional[NativeSession]:
    found = next(
        (
            session
            for session in list_native_sessions(opencode_binary, force_refresh=True)
            if session.agent == agent and session.native_id == native_id
        ),
        None,
    )
    if found and found.agent == "opencode":
        return _load_opencode_messages(found, opencode_binary)
    return found
