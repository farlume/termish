from __future__ import annotations

import asyncio
import json
import os
import pathlib
import signal
import shutil
import time
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Dict, List, Optional

from __init__ import VERSION


EventSink = Callable[[Dict[str, Any]], Awaitable[None]]
ApprovalHandler = Callable[[Dict[str, Any]], Awaitable[Dict[str, Any]]]

# Agent CLIs emit one JSON object per line. Tool results (especially Codex
# command_execution events) can legitimately exceed asyncio's 64 KiB default
# StreamReader limit before we get a chance to trim the visible output.
AGENT_JSONL_LIMIT = 16 * 1024 * 1024
AGENT_START_TIMEOUT_SECONDS = 30
AGENT_PROBE_TIMEOUT_SECONDS = 8

AGENT_PACKAGES = {
    "codex": "@openai/codex",
    "claude": "@anthropic-ai/claude-code",
    "gemini": "@google/gemini-cli",
    "opencode": "opencode-ai",
    "pi": "@earendil-works/pi-coding-agent",
}

AGENT_LABELS = {
    "codex": "Codex",
    "claude": "Claude Code",
    "gemini": "Gemini CLI",
    "opencode": "OpenCode",
    "pi": "Pi",
}


def bridge_home() -> pathlib.Path:
    configured = os.environ.get("TERMISH_AGENT_HOME")
    return pathlib.Path(configured).expanduser() if configured else pathlib.Path.home() / ".local/share/termish-agent"


def candidate_bin_dirs() -> List[pathlib.Path]:
    home = pathlib.Path.home()
    nvm_bins = sorted((home / ".nvm/versions/node").glob("*/bin"), reverse=True)
    return [
        bridge_home() / "npm/node_modules/.bin",
        home / ".local/bin",
        home / ".npm-global/bin",
        home / ".bun/bin",
        home / ".pi/agent/bin",
        *nvm_bins,
        pathlib.Path("/opt/homebrew/bin"),
        pathlib.Path("/usr/local/bin"),
    ]


def resolve_binary(name: str) -> Optional[str]:
    found = shutil.which(name)
    if found:
        return found
    candidates = [directory / name for directory in candidate_bin_dirs()]
    return next((str(path) for path in candidates if path.is_file()), None)


def command_environment() -> Dict[str, str]:
    environment = os.environ.copy()
    directories = [str(path) for path in candidate_bin_dirs() if path.is_dir()]
    existing = environment.get("PATH", "")
    environment["PATH"] = os.pathsep.join(directories + ([existing] if existing else []))
    return environment


def safe_json(value: Any, limit: int = 1000) -> str:
    try:
        text = json.dumps(value, ensure_ascii=False, separators=(",", ":"))
    except (TypeError, ValueError):
        text = str(value)
    return truncate(text, limit)


def truncate(text: str, limit: int = 4000) -> str:
    if len(text) <= limit:
        return text
    return text[:limit] + "\n… [truncated]"


def text_blocks(content: Any, kind: str = "text", field: str = "text") -> str:
    if isinstance(content, str):
        return content
    if not isinstance(content, list):
        return ""
    return "".join(
        str(block.get(field, ""))
        for block in content
        if isinstance(block, dict) and block.get("type") == kind and isinstance(block.get(field), str)
    )


@dataclass
class AgentRun:
    agent: str
    cwd: str
    model: Optional[str] = None
    resume_id: Optional[str] = None
    provider: Optional[str] = None
    api_key: Optional[str] = None
    base_url: Optional[str] = None
    provider_type: Optional[str] = None
    pi_provider: Optional[str] = None
    anthropic_base_url: Optional[str] = None
    approval_handler: Optional[ApprovalHandler] = None
    process: Optional[asyncio.subprocess.Process] = None
    aborted: bool = False


@dataclass(frozen=True)
class AgentReadiness:
    status: str
    message: str = ""

    @property
    def ready(self) -> bool:
        return self.status == "ready"


def _provider_env_key(provider_id: str) -> str:
    """provider id → 环境变量名（如 `openai` → `OPENAI_API_KEY`）。"""
    return provider_id.upper().replace("-", "_").replace(" ", "_") + "_API_KEY"


def _resolve_claude_base_url(state: AgentRun) -> Optional[str]:
    """claude 可用的 anthropic 兼容端点：
    - anthropic 类型 → baseUrl（缺省官方端点）
    - openai 类型带 anthropicBaseUrl → 用显式 anthropic 兼容端点（如 DeepSeek /anthropic）
    - 旧 deepseek 类型 → baseUrl 兜底拼 /anthropic（迁移兼容）
    """
    if state.provider_type == "anthropic":
        return state.base_url or "https://api.anthropic.com"
    if state.anthropic_base_url:
        return state.anthropic_base_url
    # 旧会话/旧测试：provider_type 缺失但 provider 是 deepseek（兼容迁移）
    if state.provider_type in (None, "deepseek") and state.provider == "deepseek":
        return (state.base_url or "https://api.deepseek.com").rstrip("/") + "/anthropic"
    return None


class AgentAdapter:
    id: str

    def available(self) -> bool:
        return resolve_binary(self.id) is not None

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        raise NotImplementedError

    async def readiness(self, state: AgentRun) -> AgentReadiness:
        if not self.available():
            return AgentReadiness("not_installed", f"{self.id} is not installed")
        if state.provider and state.api_key:
            return AgentReadiness("ready")
        return AgentReadiness("unknown")

    def environment(self, state: AgentRun) -> Dict[str, str]:
        return command_environment()

    async def _probe(
        self,
        state: AgentRun,
        args: List[str],
    ) -> tuple[int, str, str]:
        binary = resolve_binary(self.id)
        if not binary:
            return 127, "", f"{self.id} is not installed"
        try:
            process = await asyncio.create_subprocess_exec(
                binary,
                *args,
                cwd=state.cwd,
                stdout=asyncio.subprocess.PIPE,
                stderr=asyncio.subprocess.PIPE,
                env=self.environment(state),
            )
            stdout, stderr = await asyncio.wait_for(
                process.communicate(),
                timeout=AGENT_PROBE_TIMEOUT_SECONDS,
            )
        except asyncio.TimeoutError:
            process.kill()
            await process.wait()
            return 124, "", "readiness check timed out"
        return (
            process.returncode or 0,
            stdout.decode("utf-8", errors="replace").strip(),
            stderr.decode("utf-8", errors="replace").strip(),
        )

    async def abort(self, state: AgentRun) -> None:
        state.aborted = True
        process = state.process
        if process and process.returncode is None:
            try:
                if os.name == "posix":
                    os.killpg(process.pid, signal.SIGTERM)
                else:
                    process.terminate()
            except ProcessLookupError:
                return
            try:
                await asyncio.wait_for(process.wait(), timeout=3)
            except asyncio.TimeoutError:
                if process.returncode is None:
                    if os.name == "posix":
                        os.killpg(process.pid, signal.SIGKILL)
                    else:
                        process.kill()
                    await process.wait()

    async def _spawn_jsonl(
        self,
        state: AgentRun,
        args: List[str],
        prompt_stdin: Optional[str],
        on_message: Callable[[Dict[str, Any]], Awaitable[None]],
        emit: EventSink,
    ) -> None:
        binary = resolve_binary(self.id)
        if not binary:
            raise RuntimeError(f"{self.id} is not installed")
        state.aborted = False
        process = await asyncio.create_subprocess_exec(
            binary,
            *args,
            cwd=state.cwd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=self.environment(state),
            limit=AGENT_JSONL_LIMIT,
            start_new_session=os.name == "posix",
        )
        state.process = process
        if prompt_stdin is not None and process.stdin:
            process.stdin.write(prompt_stdin.encode("utf-8"))
            await process.stdin.drain()
        if process.stdin:
            process.stdin.close()

        stderr_task = asyncio.create_task(process.stderr.read()) if process.stderr else None
        if process.stdout:
            first_line = True
            while True:
                try:
                    if first_line:
                        raw = await asyncio.wait_for(
                            process.stdout.readline(),
                            timeout=AGENT_START_TIMEOUT_SECONDS,
                        )
                    else:
                        raw = await process.stdout.readline()
                except asyncio.TimeoutError:
                    await self.abort(state)
                    stderr = (await stderr_task).decode("utf-8", errors="replace") if stderr_task else ""
                    state.process = None
                    tail = "\n".join(stderr.strip().splitlines()[-3:])
                    raise RuntimeError(
                        f"{self.id} did not start within {AGENT_START_TIMEOUT_SECONDS}s; "
                        "check that the agent is signed in"
                        + (f"\n{tail}" if tail else "")
                    )
                first_line = False
                if not raw:
                    break
                try:
                    message = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    continue
                if isinstance(message, dict):
                    await on_message(message)
        code = await process.wait()
        stderr = (await stderr_task).decode("utf-8", errors="replace") if stderr_task else ""
        state.process = None
        if state.aborted:
            await emit({"type": "cancelled"})
            return
        if code != 0:
            tail = "\n".join(stderr.strip().splitlines()[-3:])
            raise RuntimeError(f"{self.id} exited with code {code}" + (f"\n{tail}" if tail else ""))


class CodexAdapter(AgentAdapter):
    id = "codex"

    def environment(self, state: AgentRun) -> Dict[str, str]:
        environment = command_environment()
        if state.provider and state.api_key:
            environment["OPENAI_API_KEY"] = state.api_key
            if state.base_url:
                environment["OPENAI_BASE_URL"] = state.base_url
        return environment

    async def readiness(self, state: AgentRun) -> AgentReadiness:
        base = await super().readiness(state)
        if base.status != "unknown":
            return base
        code, stdout, stderr = await self._probe(state, ["login", "status"])
        detail = "\n".join(part for part in (stdout, stderr) if part).strip()
        if code == 0 and "logged in" in detail.lower():
            return AgentReadiness("ready", detail)
        return AgentReadiness(
            "login_required",
            detail or "Codex is not signed in. Run `codex login` on the host first.",
        )

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        binary = resolve_binary(self.id)
        if not binary:
            raise RuntimeError("codex is not installed")
        process = await asyncio.create_subprocess_exec(
            binary,
            "app-server",
            "--stdio",
            cwd=state.cwd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=self.environment(state),
            limit=AGENT_JSONL_LIMIT,
            start_new_session=os.name == "posix",
        )
        state.process = process
        state.aborted = False
        stderr_task = asyncio.create_task(process.stderr.read()) if process.stderr else None
        if not process.stdin or not process.stdout:
            raise RuntimeError("Codex App Server streams are unavailable")

        async def send(message: Dict[str, Any]) -> None:
            process.stdin.write((json.dumps(message, ensure_ascii=False) + "\n").encode("utf-8"))
            await process.stdin.drain()

        async def read(first: bool = False) -> Dict[str, Any]:
            while True:
                raw = (
                    await asyncio.wait_for(process.stdout.readline(), AGENT_START_TIMEOUT_SECONDS)
                    if first
                    else await process.stdout.readline()
                )
                if not raw:
                    raise EOFError("Codex App Server closed its output")
                try:
                    value = json.loads(raw.decode("utf-8"))
                except (UnicodeDecodeError, json.JSONDecodeError):
                    continue
                if isinstance(value, dict):
                    return value

        async def request(request_id: str, method: str, params: Dict[str, Any]) -> Dict[str, Any]:
            await send({"id": request_id, "method": method, "params": params})
            while True:
                message = await read(first=request_id == "termish-initialize")
                if message.get("id") != request_id:
                    continue
                error = message.get("error")
                if isinstance(error, dict):
                    raise RuntimeError(str(error.get("message") or safe_json(error)))
                result = message.get("result")
                return result if isinstance(result, dict) else {}

        async def answer_approval(message: Dict[str, Any]) -> None:
            request_id = message.get("id")
            method = str(message.get("method", ""))
            params = message.get("params") if isinstance(message.get("params"), dict) else {}
            if not request_id:
                return

            if method == "item/tool/requestUserInput":
                answers: Dict[str, Dict[str, List[str]]] = {}
                questions = params.get("questions") if isinstance(params.get("questions"), list) else []
                raw_timeout = params.get("autoResolutionMs")
                timeout_ms = raw_timeout if isinstance(raw_timeout, int) and raw_timeout > 0 else None
                deadline = time.monotonic() + timeout_ms / 1000 if timeout_ms else None
                for raw_question in questions:
                    if not isinstance(raw_question, dict):
                        continue
                    question_id = str(raw_question.get("id") or "")
                    if not question_id:
                        continue
                    raw_options = raw_question.get("options")
                    option_items = raw_options if isinstance(raw_options, list) else []
                    labels = [
                        str(option.get("label"))
                        for option in option_items
                        if isinstance(option, dict) and option.get("label") is not None
                    ]
                    descriptions = [
                        f"{option.get('label')} — {option.get('description')}"
                        for option in option_items
                        if isinstance(option, dict) and option.get("description")
                    ]
                    approval = {
                        "kind": "select" if labels else "input",
                        "title": str(raw_question.get("header") or "Input required"),
                        "message": str(raw_question.get("question") or ""),
                        "details": "\n".join(descriptions),
                        "options": labels,
                        "secret": bool(raw_question.get("isSecret", False)),
                        "allowCustom": bool(raw_question.get("isOther", False)),
                    }
                    if deadline is not None:
                        remaining_ms = max(0, int((deadline - time.monotonic()) * 1000))
                        if remaining_ms == 0:
                            break
                        approval["timeoutMs"] = remaining_ms
                    response = (
                        await state.approval_handler(approval)
                        if state.approval_handler
                        else {"decision": "cancel"}
                    )
                    if response.get("decision") == "cancel":
                        break
                    value = response.get("value")
                    answers[question_id] = {
                        "answers": [str(value)] if value is not None else []
                    }
                await send({"id": request_id, "result": {"answers": answers}})
                return

            if method == "mcpServer/elicitation/request":
                # MCP form schemas can contain multiple typed fields. Until the
                # mobile client has a schema renderer, fail closed with the
                # protocol's native decline response instead of returning an
                # invalid approval-shaped object.
                await send({"id": request_id, "result": {"action": "decline"}})
                return

            if method == "currentTime/read":
                await send({"id": request_id, "result": {"currentTimeAt": int(time.time())}})
                return

            legacy_approval = method in {"execCommandApproval", "applyPatchApproval"}
            if method in {"item/commandExecution/requestApproval", "execCommandApproval"}:
                network_context = params.get("networkApprovalContext")
                is_network = isinstance(network_context, dict)
                kind = "network" if is_network else "command"
                command = params.get("command")
                if isinstance(command, list):
                    command = " ".join(map(str, command))
                if not command and isinstance(params.get("commandActions"), list):
                    command = "\n".join(
                        str(action.get("command", ""))
                        for action in params["commandActions"]
                        if isinstance(action, dict)
                    )
                raw_decisions = params.get("availableDecisions")
                available = raw_decisions if isinstance(raw_decisions, list) else None
                options = (
                    [
                        option
                        for option, native in (
                            ("allow_once", "accept"),
                            ("allow_session", "acceptForSession"),
                            ("deny", "decline"),
                            ("cancel", "cancel"),
                        )
                        if native in available
                    ]
                    if available is not None
                    else ["allow_once", "allow_session", "deny", "cancel"]
                )
                details = []
                if is_network:
                    host = str(network_context.get("host") or "")
                    protocol = str(network_context.get("protocol") or "")
                    destination = "://".join(value for value in (protocol, host) if value)
                    if destination:
                        details.append(destination)
                if params.get("additionalPermissions") is not None:
                    details.append(safe_json(params.get("additionalPermissions"), 4000))
                approval = {
                    "kind": kind,
                    "title": "Approve network access" if is_network else "Approve command",
                    "message": str(
                        params.get("reason")
                        or (
                            "Codex requests access to this network destination."
                            if is_network
                            else "Codex requests permission to run this command."
                        )
                    ),
                    "command": str(command or ""),
                    "cwd": str(params.get("cwd") or state.cwd),
                    "details": "\n".join(details),
                    "options": options,
                }
            elif method in {"item/fileChange/requestApproval", "applyPatchApproval"}:
                kind = "file_change"
                approval = {
                    "kind": kind,
                    "title": "Approve file changes",
                    "message": str(params.get("reason") or "Codex requests permission to change files."),
                    "cwd": str(params.get("grantRoot") or state.cwd),
                    "details": safe_json(params.get("fileChanges"), 4000),
                    "options": ["allow_once", "allow_session", "deny", "cancel"],
                }
            elif method == "item/permissions/requestApproval":
                kind = "permissions"
                approval = {
                    "kind": kind,
                    "title": "Approve permissions",
                    "message": str(params.get("reason") or "Codex requests additional sandbox permissions."),
                    "details": safe_json(params.get("permissions"), 4000),
                    "cwd": str(params.get("cwd") or state.cwd),
                    "options": ["allow_once", "allow_session", "deny", "cancel"],
                }
            else:
                # No dynamic client tools or token/attestation capabilities are
                # advertised during initialize, so these requests are not
                # expected. Return a structured tool failure if a newer server
                # still asks for one; all other unknown requests fail closed.
                result = (
                    {"success": False, "contentItems": []}
                    if method == "item/tool/call"
                    else {"decision": "decline"}
                )
                await send({"id": request_id, "result": result})
                return

            response = (
                await state.approval_handler(approval)
                if state.approval_handler
                else {"decision": "deny"}
            )
            decision = str(response.get("decision", "deny"))
            if kind == "permissions":
                accepted = decision in {"allow_once", "allow_session"}
                result = {
                    "permissions": params.get("permissions") if accepted else {},
                    "scope": "session" if decision == "allow_session" else "turn",
                }
            elif legacy_approval:
                mapped = {
                    "allow_once": "approved",
                    "allow_session": "approved_for_session",
                    "deny": {"denied": {"rejection": "Denied by user"}},
                    "cancel": "abort",
                }.get(decision, {"denied": {"rejection": "Denied by user"}})
                result = {"decision": mapped}
            else:
                mapped = {
                    "allow_once": "accept",
                    "allow_session": "acceptForSession",
                    "deny": "decline",
                    "cancel": "cancel",
                }.get(decision, "decline")
                result = {"decision": mapped}
            await send({"id": request_id, "result": result})

        streamed_answers: set[str] = set()
        streamed_reasoning: set[str] = set()
        settled = False
        try:
            await request(
                "termish-initialize",
                "initialize",
                {
                    "clientInfo": {
                        "name": "termish_agent_bridge",
                        "title": "Termish Agent Bridge",
                        "version": VERSION,
                    }
                },
            )
            await send({"method": "initialized", "params": {}})
            if state.resume_id:
                result = await request(
                    "termish-thread",
                    "thread/resume",
                    {"threadId": state.resume_id},
                )
            else:
                thread_params: Dict[str, Any] = {"cwd": state.cwd}
                if state.model:
                    thread_params["model"] = state.model
                result = await request("termish-thread", "thread/start", thread_params)
            thread = result.get("thread") if isinstance(result.get("thread"), dict) else {}
            thread_id = str(thread.get("id") or state.resume_id or "")
            if not thread_id:
                raise RuntimeError("Codex App Server did not return a thread id")
            state.resume_id = thread_id
            turn_params: Dict[str, Any] = {
                "threadId": thread_id,
                "input": [{"type": "text", "text": prompt}],
                "cwd": state.cwd,
            }
            if state.model:
                turn_params["model"] = state.model
            await send({"id": "termish-turn", "method": "turn/start", "params": turn_params})

            while True:
                message = await read()
                method = str(message.get("method", ""))
                if message.get("id") == "termish-turn" and isinstance(message.get("error"), dict):
                    raise RuntimeError(str(message["error"].get("message") or "Codex turn failed"))
                if message.get("id") is not None and method:
                    await answer_approval(message)
                    continue
                params = message.get("params") if isinstance(message.get("params"), dict) else {}
                if method == "item/agentMessage/delta":
                    item_id = str(params.get("itemId") or "")
                    streamed_answers.add(item_id)
                    await emit({"type": "delta", "text": str(params.get("delta") or "")})
                elif method in ("item/reasoning/summaryTextDelta", "item/reasoning/textDelta"):
                    item_id = str(params.get("itemId") or "")
                    if item_id not in streamed_reasoning:
                        streamed_reasoning.add(item_id)
                        await emit({"type": "thinking_start"})
                    await emit({"type": "thinking_delta", "text": str(params.get("delta") or "")})
                elif method in ("item/started", "item/completed"):
                    item = params.get("item") if isinstance(params.get("item"), dict) else {}
                    phase = "started" if method == "item/started" else "completed"
                    item_type = str(item.get("type") or "")
                    item_id = str(item.get("id") or "") or None
                    if item_type == "commandExecution":
                        if phase == "started":
                            await emit(
                                {
                                    "type": "tool_start",
                                    "name": "bash",
                                    "args": truncate(str(item.get("command") or ""), 1000),
                                    "toolId": item_id,
                                }
                            )
                        else:
                            code = item.get("exitCode")
                            await emit(
                                {
                                    "type": "tool_end",
                                    "name": "bash",
                                    "output": truncate(str(item.get("aggregatedOutput") or "")),
                                    "isError": isinstance(code, int) and code != 0,
                                    "toolId": item_id,
                                }
                            )
                    elif item_type == "fileChange":
                        details = safe_json(item.get("changes"), 4000)
                        if phase == "started":
                            await emit(
                                {
                                    "type": "tool_start",
                                    "name": "file_change",
                                    "args": details,
                                    "toolId": item_id,
                                }
                            )
                        else:
                            await emit(
                                {
                                    "type": "tool_end",
                                    "name": "file_change",
                                    "output": details,
                                    "isError": item.get("status") in {"failed", "declined"},
                                    "toolId": item_id,
                                }
                            )
                    elif item_type == "agentMessage" and phase == "completed" and item_id not in streamed_answers:
                        await emit(
                            {
                                "type": "assistant_message",
                                "text": str(item.get("text") or ""),
                                "toolCalls": [],
                            }
                        )
                    elif item_type == "reasoning" and phase == "completed" and item_id not in streamed_reasoning:
                        summary = item.get("summary")
                        text = "\n".join(map(str, summary)) if isinstance(summary, list) else str(summary or "")
                        if text:
                            await emit({"type": "thinking", "text": text})
                elif method == "error":
                    error = params.get("error") if isinstance(params.get("error"), dict) else {}
                    await emit({"type": "error", "message": str(error.get("message") or "Codex failed")})
                elif method == "turn/completed":
                    turn = params.get("turn") if isinstance(params.get("turn"), dict) else {}
                    status = str(turn.get("status") or "completed")
                    if status == "failed":
                        error = turn.get("error") if isinstance(turn.get("error"), dict) else {}
                        await emit({"type": "error", "message": str(error.get("message") or "Codex turn failed")})
                    elif status == "interrupted":
                        await emit({"type": "cancelled"})
                    settled = True
                    await emit({"type": "settled"})
                    break
        except asyncio.TimeoutError as exc:
            raise RuntimeError(
                f"codex did not start within {AGENT_START_TIMEOUT_SECONDS}s; check that the agent is signed in"
            ) from exc
        except EOFError as exc:
            stderr = (await stderr_task).decode("utf-8", errors="replace") if stderr_task else ""
            tail = "\n".join(stderr.strip().splitlines()[-3:])
            raise RuntimeError(str(exc) + (f"\n{tail}" if tail else "")) from exc
        finally:
            if process.stdin and not process.stdin.is_closing():
                process.stdin.close()
            try:
                await asyncio.wait_for(process.wait(), timeout=3)
            except asyncio.TimeoutError:
                try:
                    process.terminate()
                except ProcessLookupError:
                    pass
                await process.wait()
            if stderr_task and not stderr_task.done():
                await stderr_task
            state.process = None
        if state.aborted:
            await emit({"type": "cancelled"})
        elif not settled:
            await emit({"type": "settled"})


class ClaudeAdapter(AgentAdapter):
    id = "claude"

    def environment(self, state: AgentRun) -> Dict[str, str]:
        environment = command_environment()
        if state.provider and state.api_key:
            base_url = _resolve_claude_base_url(state)
            if base_url:
                update = {
                    "ANTHROPIC_BASE_URL": base_url,
                    "ANTHROPIC_AUTH_TOKEN": state.api_key,
                    "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB": "1",
                    "CLAUDE_CODE_EFFORT_LEVEL": "max",
                }
                if state.model:
                    update["ANTHROPIC_MODEL"] = state.model
                environment.update(update)
        return environment

    async def readiness(self, state: AgentRun) -> AgentReadiness:
        base = await super().readiness(state)
        if base.status != "unknown":
            return base
        code, stdout, stderr = await self._probe(state, ["auth", "status", "--json"])
        detail = "\n".join(part for part in (stdout, stderr) if part).strip()
        try:
            status = json.loads(stdout)
        except (TypeError, json.JSONDecodeError):
            status = None
        if isinstance(status, dict) and status.get("loggedIn") is False:
            return AgentReadiness(
                "login_required",
                "Claude Code is not signed in. Run `claude auth login` on the host first.",
            )
        if code == 0:
            return AgentReadiness("ready", detail)
        lowered = detail.lower()
        if "unknown" in lowered or "unrecognized" in lowered or "invalid option" in lowered:
            return AgentReadiness("unknown", detail)
        return AgentReadiness(
            "login_required",
            detail or "Claude Code is not signed in. Run `claude auth login` on the host first.",
        )

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        args = ["-p", "--output-format", "stream-json", "--verbose"]
        if state.resume_id:
            args.extend(["--resume", state.resume_id])
        if state.model:
            args.extend(["--model", state.model])
        tool_names: Dict[str, str] = {}
        settled = False

        async def on_message(message: Dict[str, Any]) -> None:
            nonlocal settled
            kind = message.get("type")
            if kind == "system" and message.get("subtype") == "init" and message.get("session_id"):
                state.resume_id = str(message["session_id"])
            elif kind == "stream_event":
                event = message.get("event") or {}
                delta = event.get("delta") or {}
                if event.get("type") == "content_block_delta" and delta.get("type") == "text_delta":
                    await emit({"type": "delta", "text": str(delta.get("text", ""))})
                elif event.get("type") == "content_block_delta" and delta.get("type") == "thinking_delta":
                    await emit({"type": "thinking_delta", "text": str(delta.get("thinking", ""))})
            elif kind == "assistant":
                content = (message.get("message") or {}).get("content")
                if isinstance(content, list):
                    for block in content:
                        if not isinstance(block, dict):
                            continue
                        block_type = block.get("type")
                        if block_type == "thinking" and isinstance(block.get("thinking"), str):
                            await emit({"type": "thinking", "text": block["thinking"]})
                        elif block_type == "text" and isinstance(block.get("text"), str):
                            await emit({"type": "assistant_message", "text": block["text"], "toolCalls": []})
                        elif block_type == "tool_use":
                            tool_id = str(block.get("id")) if block.get("id") else None
                            name = str(block.get("name", "tool"))
                            if tool_id:
                                tool_names[tool_id] = name
                            await emit(
                                {
                                    "type": "tool_start",
                                    "name": name,
                                    "args": safe_json(block.get("input")),
                                    "toolId": tool_id,
                                }
                            )
                elif isinstance(content, str) and content:
                    await emit({"type": "assistant_message", "text": content, "toolCalls": []})
            elif kind == "user":
                content = (message.get("message") or {}).get("content")
                if isinstance(content, list):
                    for block in content:
                        if isinstance(block, dict) and block.get("type") == "tool_result":
                            tool_id = str(block.get("tool_use_id")) if block.get("tool_use_id") else None
                            output = block.get("content")
                            await emit({"type": "tool_end", "name": tool_names.get(tool_id or "", "tool"), "output": truncate(text_blocks(output) if isinstance(output, list) else str(output or "")), "isError": block.get("is_error") is True, "toolId": tool_id})
            elif kind == "result":
                if message.get("session_id"):
                    state.resume_id = str(message["session_id"])
                settled = True
                await emit({"type": "settled"})
            elif kind == "error":
                await emit({"type": "error", "message": str((message.get("error") or {}).get("message", "Claude failed"))})

        await self._spawn_jsonl(state, args, prompt + "\n", on_message, emit)
        if not state.aborted and not settled:
            await emit({"type": "settled"})


class GeminiAdapter(AgentAdapter):
    id = "gemini"

    async def readiness(self, state: AgentRun) -> AgentReadiness:
        base = await super().readiness(state)
        if base.status != "unknown":
            return base
        environment = self.environment(state)
        if any(environment.get(name) for name in ("GEMINI_API_KEY", "GOOGLE_API_KEY")):
            return AgentReadiness("ready")
        credentials = environment.get("GOOGLE_APPLICATION_CREDENTIALS")
        if credentials and pathlib.Path(credentials).expanduser().is_file():
            return AgentReadiness("ready")

        settings_path = pathlib.Path.home() / ".gemini" / "settings.json"
        try:
            settings = json.loads(settings_path.read_text(encoding="utf-8"))
        except (OSError, UnicodeDecodeError, json.JSONDecodeError):
            settings = {}
        security = settings.get("security") if isinstance(settings.get("security"), dict) else {}
        auth = security.get("auth") if isinstance(security.get("auth"), dict) else {}
        selected = auth.get("selectedType") or settings.get("selectedAuthType")
        if selected:
            # Current Gemini releases may migrate OAuth tokens from the legacy
            # file into an OS keyring, so the selected auth method is the only
            # stable, non-secret readiness signal available to a headless host.
            return AgentReadiness("ready", str(selected))
        return AgentReadiness(
            "login_required",
            "Gemini CLI has no authentication method configured. Run `gemini` on the host and sign in, or configure GEMINI_API_KEY.",
        )

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        args = ["-p", prompt, "--output-format", "stream-json"]
        if state.resume_id:
            args.extend(["--resume", state.resume_id])
        if state.model:
            args.extend(["--model", state.model])
        tools: Dict[str, str] = {}
        settled = False

        async def on_message(message: Dict[str, Any]) -> None:
            nonlocal settled
            kind = message.get("type")
            if kind == "init" and message.get("session_id"):
                state.resume_id = str(message["session_id"])
            elif kind == "message" and message.get("role") == "assistant":
                text = str(message.get("content", ""))
                await emit({"type": "delta" if message.get("delta") else "assistant_message", "text": text, **({} if message.get("delta") else {"toolCalls": []})})
            elif kind == "tool_use":
                tool_id = str(message.get("tool_id")) if message.get("tool_id") else None
                name = str(message.get("tool_name", "tool"))
                if tool_id:
                    tools[tool_id] = name
                await emit({"type": "tool_start", "name": name, "args": safe_json(message.get("parameters")), "toolId": tool_id})
            elif kind == "tool_result":
                tool_id = str(message.get("tool_id")) if message.get("tool_id") else None
                await emit({"type": "tool_end", "name": tools.get(tool_id or "", "tool"), "output": truncate(str(message.get("output") or "")), "isError": message.get("status") == "error", "toolId": tool_id})
            elif kind == "result":
                settled = True
                if message.get("status") == "success":
                    await emit({"type": "settled"})
                else:
                    await emit({"type": "error", "message": str((message.get("error") or {}).get("message", "Gemini failed"))})
            elif kind == "error":
                await emit({"type": "error", "message": str(message.get("message", "Gemini failed"))})

        await self._spawn_jsonl(state, args, None, on_message, emit)
        if not state.aborted and not settled:
            await emit({"type": "settled"})


class OpenCodeAdapter(AgentAdapter):
    id = "opencode"

    def environment(self, state: AgentRun) -> Dict[str, str]:
        environment = command_environment()
        if state.provider and state.api_key:
            environment["OPENAI_API_KEY"] = state.api_key
            if state.base_url:
                environment["OPENAI_BASE_URL"] = state.base_url
        return environment

    async def readiness(self, state: AgentRun) -> AgentReadiness:
        base = await super().readiness(state)
        if base.status != "unknown":
            return base
        code, stdout, stderr = await self._probe(state, ["auth", "list"])
        detail = "\n".join(part for part in (stdout, stderr) if part).strip()
        lowered = detail.lower()
        empty = not detail or "0 credentials" in lowered or "no credentials" in lowered
        if code == 0 and not empty:
            return AgentReadiness("ready", detail)
        return AgentReadiness(
            "login_required",
            detail or "OpenCode has no authenticated provider. Run `opencode auth login` on the host first.",
        )

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        args = ["run", prompt, "--format", "json", "--thinking"]
        if state.resume_id:
            args.extend(["--session", state.resume_id])
        if state.model:
            args.extend(["--model", state.model])
        pending_thinking: List[str] = []

        async def flush_thinking() -> None:
            if pending_thinking:
                await emit({"type": "thinking", "text": "\n\n".join(pending_thinking)})
                pending_thinking.clear()

        async def on_message(message: Dict[str, Any]) -> None:
            if message.get("sessionID"):
                state.resume_id = str(message["sessionID"])
            kind = message.get("type")
            part = message.get("part") or {}
            if kind == "reasoning" and isinstance(part.get("text"), str):
                pending_thinking.append(part["text"])
            elif kind == "text" and isinstance(part.get("text"), str):
                await flush_thinking()
                await emit({"type": "delta", "text": part["text"]})
            elif kind == "tool_use":
                await flush_thinking()
                tool = str(part.get("tool", "tool"))
                tool_id = str(part.get("id")) if part.get("id") else None
                tool_state = part.get("state") or {}
                await emit({"type": "tool_start", "name": tool, "args": safe_json(tool_state.get("input")), "toolId": tool_id})
                await emit({"type": "tool_end", "name": tool, "output": truncate(safe_json(tool_state.get("output"), 4000)), "isError": tool_state.get("status") == "error", "toolId": tool_id})

        await self._spawn_jsonl(state, args, None, on_message, emit)
        await flush_thinking()
        if not state.aborted:
            await emit({"type": "settled"})


class PiAdapter(AgentAdapter):
    id = "pi"

    @staticmethod
    def _provider(state: AgentRun) -> Optional[str]:
        if state.pi_provider:
            return state.pi_provider
        # 兼容迁移前直接把 DeepSeek 写入 provider/type 的旧会话。
        if state.provider_type == "deepseek" or state.provider == "deepseek":
            return "deepseek"
        return None

    def environment(self, state: AgentRun) -> Dict[str, str]:
        environment = command_environment()
        provider = self._provider(state)
        if provider and state.api_key:
            environment[_provider_env_key(provider)] = state.api_key
        return environment

    async def readiness(self, state: AgentRun) -> AgentReadiness:
        base = await super().readiness(state)
        if base.status != "unknown":
            return base
        provider = self._provider(state) or "google"
        code, stdout, stderr = await self._probe(
            state,
            ["auth", "check", "--provider", provider, "--json", "--no-refresh"],
        )
        detail = "\n".join(part for part in (stdout, stderr) if part).strip()
        if code == 0:
            return AgentReadiness("ready", detail)
        return AgentReadiness(
            "login_required",
            detail or f"Pi provider `{provider}` is not authenticated. Configure Pi auth on the host first.",
        )

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        binary = resolve_binary(self.id)
        if not binary:
            raise RuntimeError("pi is not installed")
        args = ["--mode", "rpc"]
        if state.resume_id:
            args.extend(["--session", state.resume_id])
        provider = self._provider(state)
        if provider:
            args.extend(["--provider", provider])
        if state.model:
            args.extend(["--model", state.model])
        process = await asyncio.create_subprocess_exec(
            binary,
            *args,
            cwd=state.cwd,
            stdin=asyncio.subprocess.PIPE,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
            env=self.environment(state),
            limit=AGENT_JSONL_LIMIT,
            start_new_session=os.name == "posix",
        )
        state.process = process
        state.aborted = False
        stderr_task = asyncio.create_task(process.stderr.read()) if process.stderr else None
        if not process.stdin or not process.stdout:
            raise RuntimeError("pi RPC streams are unavailable")
        process.stdin.write((json.dumps({"id": "termish-state", "type": "get_state"}) + "\n").encode("utf-8"))
        process.stdin.write((json.dumps({"id": "termish-prompt", "type": "prompt", "message": prompt}) + "\n").encode("utf-8"))
        await process.stdin.drain()
        settled = False
        prompt_accepted = False
        first_line = True
        saw_thinking = False
        while True:
            try:
                if first_line:
                    raw = await asyncio.wait_for(
                        process.stdout.readline(),
                        timeout=AGENT_START_TIMEOUT_SECONDS,
                    )
                else:
                    raw = await process.stdout.readline()
            except asyncio.TimeoutError:
                await self.abort(state)
                stderr = (await stderr_task).decode("utf-8", errors="replace") if stderr_task else ""
                state.process = None
                tail = "\n".join(stderr.strip().splitlines()[-3:])
                raise RuntimeError(
                    f"pi did not start within {AGENT_START_TIMEOUT_SECONDS}s; check that the agent is signed in"
                    + (f"\n{tail}" if tail else "")
                )
            first_line = False
            if not raw:
                break
            try:
                message = json.loads(raw.decode("utf-8"))
            except (UnicodeDecodeError, json.JSONDecodeError):
                continue
            if not isinstance(message, dict):
                continue
            kind = message.get("type")
            if kind == "response":
                if message.get("id") == "termish-state" and message.get("success"):
                    data = message.get("data") or {}
                    if isinstance(data, dict) and data.get("sessionFile"):
                        state.resume_id = str(data["sessionFile"])
                elif message.get("id") == "termish-prompt":
                    if not message.get("success"):
                        raise RuntimeError(str(message.get("error") or "pi rejected the prompt"))
                    prompt_accepted = True
            elif kind == "message_update":
                event = message.get("assistantMessageEvent") or {}
                if not isinstance(event, dict):
                    continue
                event_type = event.get("type")
                if event_type == "text_delta":
                    await emit({"type": "delta", "text": str(event.get("delta", ""))})
                elif event_type == "thinking_start":
                    saw_thinking = True
                    await emit({"type": "thinking_start"})
                elif event_type == "thinking_delta":
                    saw_thinking = True
                    await emit({"type": "thinking_delta", "text": str(event.get("delta", ""))})
            elif kind == "message_end":
                final_message = message.get("message") or {}
                if isinstance(final_message, dict) and final_message.get("role") == "assistant":
                    content = final_message.get("content")
                    thinking = text_blocks(content, "thinking", "thinking") or text_blocks(content, "thinking")
                    if thinking and not saw_thinking:
                        await emit({"type": "thinking", "text": thinking})
                    text = text_blocks(content)
                    if text:
                        await emit({"type": "assistant_message", "text": text})
            elif kind == "tool_execution_start":
                await emit(
                    {
                        "type": "tool_start",
                        "name": str(message.get("toolName", "tool")),
                        "args": safe_json(message.get("args")),
                        "toolId": message.get("toolCallId"),
                    }
                )
            elif kind == "tool_execution_end":
                result = message.get("result") or {}
                output = text_blocks(result.get("content")) if isinstance(result, dict) else str(result)
                await emit(
                    {
                        "type": "tool_end",
                        "name": str(message.get("toolName", "tool")),
                        "output": truncate(output),
                        "isError": message.get("isError") is True,
                        "toolId": message.get("toolCallId"),
                    }
                )
            elif kind == "extension_error":
                await emit({"type": "error", "message": str(message.get("error") or "pi extension failed")})
            elif kind == "extension_ui_request":
                method = str(message.get("method", ""))
                request_id = message.get("id")
                if request_id and method in {"select", "confirm", "input", "editor"}:
                    options = [str(value) for value in message.get("options", [])]
                    if method == "confirm":
                        action_options = ["allow_once", "deny", "cancel"]
                    elif method == "select":
                        action_options = options
                    else:
                        action_options = ["submit", "cancel"]
                    approval = {
                        "kind": method,
                        "title": str(message.get("title") or "Pi requires input"),
                        "message": str(message.get("message") or ""),
                        "placeholder": str(message.get("placeholder") or ""),
                        "prefill": str(message.get("prefill") or ""),
                        "options": action_options,
                        "timeoutMs": int(message.get("timeout") or 0),
                    }
                    response = (
                        await state.approval_handler(approval)
                        if state.approval_handler
                        else {"decision": "cancel"}
                    )
                    decision = str(response.get("decision", "cancel"))
                    if method == "confirm" and decision in {"allow_once", "allow_session"}:
                        rpc_response = {
                            "type": "extension_ui_response",
                            "id": request_id,
                            "confirmed": True,
                        }
                    elif method == "confirm" and decision == "deny":
                        rpc_response = {
                            "type": "extension_ui_response",
                            "id": request_id,
                            "confirmed": False,
                        }
                    elif method in {"select", "input", "editor"} and decision != "cancel":
                        rpc_response = {
                            "type": "extension_ui_response",
                            "id": request_id,
                            "value": str(response.get("value") or decision),
                        }
                    else:
                        rpc_response = {
                            "type": "extension_ui_response",
                            "id": request_id,
                            "cancelled": True,
                        }
                    process.stdin.write(
                        (json.dumps(rpc_response, ensure_ascii=False) + "\n").encode("utf-8")
                    )
                    await process.stdin.drain()
            elif kind == "agent_settled":
                settled = True
                await emit({"type": "settled"})
                break
        if process.stdin:
            process.stdin.close()
        try:
            code = await asyncio.wait_for(process.wait(), timeout=3)
        except asyncio.TimeoutError:
            try:
                process.terminate()
            except ProcessLookupError:
                pass
            code = await process.wait()
        stderr = (await stderr_task).decode("utf-8", errors="replace") if stderr_task else ""
        state.process = None
        if state.aborted:
            await emit({"type": "cancelled"})
            return
        if not prompt_accepted:
            tail = "\n".join(stderr.strip().splitlines()[-3:])
            raise RuntimeError("pi did not accept the prompt" + (f"\n{tail}" if tail else ""))
        if code != 0:
            tail = "\n".join(stderr.strip().splitlines()[-3:])
            raise RuntimeError(f"pi exited with code {code}" + (f"\n{tail}" if tail else ""))
        if not settled:
            await emit({"type": "settled"})


ADAPTERS: Dict[str, AgentAdapter] = {
    "codex": CodexAdapter(),
    "claude": ClaudeAdapter(),
    "gemini": GeminiAdapter(),
    "opencode": OpenCodeAdapter(),
    "pi": PiAdapter(),
}

PROVIDER_AGENTS = {
    # 旧版 deepseek 配置兼容（迁移前创建的会话）
    "deepseek": {"claude", "opencode", "pi"},
    "openai": {"opencode", "pi"},
    "anthropic": {"claude"},
}


def provider_supported(
    agent_id: str,
    provider_type: Optional[str],
    anthropic_base_url: Optional[str] = None,
) -> bool:
    # OpenAI-compatible providers may additionally expose an Anthropic-compatible
    # endpoint. Claude Code can use that endpoint even though the provider's
    # primary wire type remains `openai` (for example DeepSeek `/anthropic`).
    if agent_id == "claude" and anthropic_base_url:
        return True
    return not provider_type or agent_id in PROVIDER_AGENTS.get(provider_type, set())


def fetch_models(
    base_url: str,
    api_key: str,
    provider_type: str = "openai",
    timeout: float = 15.0,
) -> List[str]:
    """拉取供应商模型列表（GET /models）。经远端 bridge 转发：手机侧无需 CORS。"""
    base = (base_url or "").strip().rstrip("/")
    if not base:
        raise RuntimeError("缺少 Base URL")
    parsed = urllib.parse.urlsplit(base)
    # 供应商给出的 Base URL 若已含 API 路径（/v1、/v4、/api/v3、
    # /v1beta/openai 等），就在该路径下取 models；纯域名才补标准 /v1。
    models_url = f"{base}/models" if parsed.path.rstrip("/") else f"{base}/v1/models"
    headers = {"Content-Type": "application/json"}
    if provider_type == "anthropic":
        headers["x-api-key"] = api_key
        headers["anthropic-version"] = "2023-06-01"
    else:
        headers["Authorization"] = f"Bearer {api_key}"
    request = urllib.request.Request(models_url, headers=headers, method="GET")
    try:
        with urllib.request.urlopen(request, timeout=timeout) as response:
            body = json.loads(response.read().decode("utf-8"))
    except urllib.error.HTTPError as error:
        code = error.code
        error.close()
        raise RuntimeError(f"GET {models_url} → HTTP {code}") from error
    except Exception as error:
        raise RuntimeError(f"GET {models_url} 失败：{error}") from error
    data = body.get("data") if isinstance(body, dict) else None
    models = [str(item.get("id")).strip() for item in data if isinstance(item, dict) and item.get("id")] if isinstance(data, list) else []
    if not models:
        raise RuntimeError("响应中没有模型列表（data[].id）")
    return models


def list_agents() -> List[Dict[str, Any]]:
    return [
        {
            "id": agent_id,
            "label": AGENT_LABELS[agent_id],
            "available": resolve_binary(agent_id) is not None,
            "supported": agent_id in ADAPTERS,
        }
        for agent_id in AGENT_PACKAGES
    ]


def install_plan(agent_id: str) -> Dict[str, Any]:
    package = AGENT_PACKAGES.get(agent_id)
    if not package:
        raise RuntimeError(f"unknown agent: {agent_id}")
    npm = resolve_binary("npm")
    prefix = bridge_home() / "npm"
    return {
        "agent": agent_id,
        "package": package,
        "available": resolve_binary(agent_id) is not None,
        "canInstall": npm is not None,
        "requiresSudo": False,
        "target": str(prefix),
        "commandPreview": f"npm install --prefix {prefix} {package}",
    }


async def install_agent(agent_id: str, emit: EventSink) -> None:
    plan = install_plan(agent_id)
    npm = resolve_binary("npm")
    if not npm:
        raise RuntimeError("npm is not installed")
    prefix = pathlib.Path(plan["target"])
    prefix.mkdir(parents=True, exist_ok=True)
    process = await asyncio.create_subprocess_exec(
        npm,
        "install",
        "--prefix",
        str(prefix),
        str(plan["package"]),
        "--no-fund",
        "--no-audit",
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.STDOUT,
        env=command_environment(),
    )
    if process.stdout:
        while True:
            line = await process.stdout.readline()
            if not line:
                break
            await emit({"type": "install_output", "agent": agent_id, "text": line.decode("utf-8", errors="replace")})
    code = await process.wait()
    if code != 0:
        raise RuntimeError(f"npm exited with code {code}")
    await emit({"type": "install_complete", "agent": agent_id})
