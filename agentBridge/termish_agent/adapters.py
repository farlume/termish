from __future__ import annotations

import asyncio
import json
import os
import pathlib
import signal
import shutil
from dataclasses import dataclass
from typing import Any, Awaitable, Callable, Dict, List, Optional


EventSink = Callable[[Dict[str, Any]], Awaitable[None]]

# Agent CLIs emit one JSON object per line. Tool results (especially Codex
# command_execution events) can legitimately exceed asyncio's 64 KiB default
# StreamReader limit before we get a chance to trim the visible output.
AGENT_JSONL_LIMIT = 16 * 1024 * 1024

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
    process: Optional[asyncio.subprocess.Process] = None
    aborted: bool = False


class AgentAdapter:
    id: str

    def available(self) -> bool:
        return resolve_binary(self.id) is not None

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        raise NotImplementedError

    def environment(self, state: AgentRun) -> Dict[str, str]:
        return command_environment()

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
            while True:
                raw = await process.stdout.readline()
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

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        args = ["exec"]
        if state.resume_id:
            args.extend(["resume", state.resume_id])
        args.extend(["--json", "--skip-git-repo-check"])
        if state.model:
            args.extend(["--model", state.model])
        if not state.resume_id:
            args.extend(["-C", state.cwd])
        args.append("-")
        settled = False

        async def on_message(message: Dict[str, Any]) -> None:
            nonlocal settled
            kind = message.get("type")
            if kind == "thread.started" and isinstance(message.get("thread_id"), str):
                state.resume_id = message["thread_id"]
            elif kind == "turn.completed":
                settled = True
                await emit({"type": "settled"})
            elif kind in ("turn.failed", "error"):
                error = message.get("error") or message.get("message") or "Codex failed"
                if isinstance(error, dict):
                    error = error.get("message") or safe_json(error)
                await emit({"type": "error", "message": str(error)})
            elif kind in ("item.started", "item.completed"):
                item = message.get("item")
                if not isinstance(item, dict):
                    return
                phase = "started" if kind == "item.started" else "completed"
                item_type = item.get("type")
                tool_id = str(item.get("id")) if item.get("id") else None
                if item_type == "agent_message" and phase == "completed":
                    text = item.get("text") if isinstance(item.get("text"), str) else text_blocks(item.get("blocks"))
                    await emit({"type": "assistant_message", "text": text or "", "toolCalls": []})
                elif item_type == "reasoning":
                    if phase == "started":
                        await emit({"type": "thinking_start"})
                    else:
                        text = item.get("text") or item.get("summary") or text_blocks(item.get("content"))
                        if text:
                            await emit({"type": "thinking", "text": str(text)})
                elif item_type == "command_execution":
                    if phase == "started":
                        await emit({"type": "tool_start", "name": "bash", "args": truncate(str(item.get("command", "")), 1000), "toolId": tool_id})
                    else:
                        code = item.get("exit_code")
                        await emit({"type": "tool_end", "name": "bash", "output": truncate(str(item.get("aggregated_output", ""))), "isError": isinstance(code, int) and code != 0, "toolId": tool_id})
                elif item_type in ("file_change", "agent_edited_file"):
                    details = safe_json(item.get("changes") or {"path": item.get("file_path")})
                    if phase == "started":
                        await emit({"type": "tool_start", "name": str(item_type), "args": details, "toolId": tool_id})
                    else:
                        await emit({"type": "tool_end", "name": str(item_type), "output": details, "isError": False, "toolId": tool_id})

        await self._spawn_jsonl(state, args, prompt, on_message, emit)
        if not state.aborted and not settled:
            await emit({"type": "settled"})


class ClaudeAdapter(AgentAdapter):
    id = "claude"

    def environment(self, state: AgentRun) -> Dict[str, str]:
        environment = command_environment()
        if state.provider == "deepseek" and state.api_key:
            model = state.model or "deepseek-v4-pro[1m]"
            base_url = (state.base_url or "https://api.deepseek.com").rstrip("/")
            if not base_url.endswith("/anthropic"):
                base_url += "/anthropic"
            environment.update(
                {
                    "ANTHROPIC_BASE_URL": base_url,
                    "ANTHROPIC_AUTH_TOKEN": state.api_key,
                    "ANTHROPIC_MODEL": model,
                    "ANTHROPIC_DEFAULT_OPUS_MODEL": model,
                    "ANTHROPIC_DEFAULT_SONNET_MODEL": model,
                    "ANTHROPIC_DEFAULT_HAIKU_MODEL": "deepseek-v4-flash",
                    "CLAUDE_CODE_SUBAGENT_MODEL": "deepseek-v4-flash",
                    "CLAUDE_CODE_EFFORT_LEVEL": "max",
                    "CLAUDE_CODE_SUBPROCESS_ENV_SCRUB": "1",
                }
            )
        return environment

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
        if state.provider == "deepseek" and state.api_key:
            environment["DEEPSEEK_API_KEY"] = state.api_key
        return environment

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        args = ["run", prompt, "--format", "json", "--thinking"]
        if state.resume_id:
            args.extend(["--session", state.resume_id])
        if state.model:
            model = state.model
            if state.provider == "deepseek" and "/" not in model:
                model = f"deepseek/{model}"
            args.extend(["--model", model])
        elif state.provider == "deepseek":
            args.extend(["--model", "deepseek/deepseek-v4-pro"])
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

    def environment(self, state: AgentRun) -> Dict[str, str]:
        environment = command_environment()
        if state.provider == "deepseek" and state.api_key:
            environment["DEEPSEEK_API_KEY"] = state.api_key
        return environment

    async def run(self, state: AgentRun, prompt: str, emit: EventSink) -> None:
        binary = resolve_binary(self.id)
        if not binary:
            raise RuntimeError("pi is not installed")
        args = ["--mode", "rpc"]
        if state.resume_id:
            args.extend(["--session", state.resume_id])
        if state.provider:
            args.extend(["--provider", state.provider])
        if state.model:
            args.extend(["--model", state.model])
        elif state.provider == "deepseek":
            args.extend(["--model", "deepseek-v4-pro"])
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
        while True:
            raw = await process.stdout.readline()
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
                    await emit({"type": "thinking_start"})
                elif event_type == "thinking_delta":
                    await emit({"type": "thinking_delta", "text": str(event.get("delta", ""))})
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

PROVIDER_AGENTS = {"deepseek": {"claude", "opencode", "pi"}}


def provider_supported(agent_id: str, provider_id: Optional[str]) -> bool:
    return not provider_id or agent_id in PROVIDER_AGENTS.get(provider_id, set())


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
