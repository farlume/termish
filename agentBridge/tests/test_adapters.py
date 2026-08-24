from __future__ import annotations

import json
import pathlib
import os
import sys
import tempfile
import unittest
from unittest import mock
from unittest.mock import AsyncMock


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "termish_agent"
sys.path.insert(0, str(SOURCE))

from adapters import AgentRun, ClaudeAdapter, CodexAdapter, GeminiAdapter, OpenCodeAdapter, PiAdapter, install_plan, list_agents, resolve_binary, text_blocks, truncate


class AdapterHelpersTest(unittest.TestCase):
    def test_text_blocks(self) -> None:
        content = [{"type": "text", "text": "你"}, {"type": "tool_use"}, {"type": "text", "text": "好"}]
        self.assertEqual("你好", text_blocks(content))

    def test_agents_have_stable_capabilities(self) -> None:
        agents = {item["id"]: item for item in list_agents()}
        self.assertTrue(agents["codex"]["supported"])
        self.assertTrue(agents["pi"]["supported"])

    def test_install_plan_never_uses_sudo(self) -> None:
        plan = install_plan("codex")
        self.assertFalse(plan["requiresSudo"])
        self.assertIn("@openai/codex", plan["commandPreview"])

    def test_resolve_binary_finds_nvm_install(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            home = pathlib.Path(temp)
            binary = home / ".nvm/versions/node/v24.0.0/bin/codex"
            binary.parent.mkdir(parents=True)
            binary.write_text("#!/bin/sh\n", encoding="utf-8")
            with mock.patch.dict(os.environ, {"HOME": temp, "PATH": ""}, clear=False):
                with mock.patch("pathlib.Path.home", return_value=home):
                    self.assertEqual(resolve_binary("codex"), str(binary))

    def test_truncate(self) -> None:
        self.assertLessEqual(len(truncate("x" * 100, 10)), 30)


class CodexAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_does_not_override_the_users_sandbox_or_approval_policy(self) -> None:
        adapter = CodexAdapter()
        with mock.patch.object(adapter, "_spawn_jsonl", new=AsyncMock()) as spawn:
            await adapter.run(AgentRun("codex", "/tmp"), "hello", AsyncMock())

        args = spawn.await_args.args[1]
        self.assertNotIn("-s", args)
        self.assertNotIn("--ask-for-approval", args)

    async def test_normalizes_jsonl_and_captures_resume_id(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            executable = root / "codex"
            executable.write_text(
                """#!/usr/bin/env python3
import json
events = [
    {"type":"thread.started","thread_id":"thread-1"},
    {"type":"item.started","item":{"id":"reason-1","type":"reasoning"}},
    {"type":"item.completed","item":{"id":"reason-1","type":"reasoning","text":"inspect"}},
    {"type":"item.started","item":{"id":"tool-1","type":"command_execution","command":"pwd"}},
    {"type":"item.completed","item":{"id":"tool-1","type":"command_execution","command":"pwd","exit_code":0,"aggregated_output":"/tmp"}},
    {"type":"item.completed","item":{"id":"msg-1","type":"agent_message","text":"done"}},
    {"type":"turn.completed"},
]
for event in events:
    print(json.dumps(event), flush=True)
""",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            old_path = os.environ.get("PATH", "")
            os.environ["PATH"] = f"{tmp}:{old_path}"
            try:
                state = AgentRun("codex", tmp)
                events = []

                async def emit(event):
                    events.append(event)

                await CodexAdapter().run(state, "hello", emit)
            finally:
                os.environ["PATH"] = old_path

        self.assertEqual("thread-1", state.resume_id)
        self.assertIn("thinking_start", [event["type"] for event in events])
        self.assertIn({"type": "thinking", "text": "inspect"}, events)
        self.assertIn("tool_start", [event["type"] for event in events])
        self.assertIn("tool_end", [event["type"] for event in events])
        self.assertIn({"type": "assistant_message", "text": "done", "toolCalls": []}, events)
        self.assertEqual("settled", events[-1]["type"])

    async def test_accepts_jsonl_events_larger_than_asyncio_default_limit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            executable = root / "codex"
            executable.write_text(
                """#!/usr/bin/env python3
import json
print(json.dumps({
    "type":"item.completed",
    "item":{
        "id":"tool-large",
        "type":"command_execution",
        "command":"cat large.log",
        "exit_code":0,
        "aggregated_output":"x" * (128 * 1024),
    },
}), flush=True)
print(json.dumps({"type":"turn.completed"}), flush=True)
""",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            old_path = os.environ.get("PATH", "")
            os.environ["PATH"] = f"{tmp}:{old_path}"
            try:
                events = []

                async def emit(event):
                    events.append(event)

                await CodexAdapter().run(AgentRun("codex", tmp), "hello", emit)
            finally:
                os.environ["PATH"] = old_path

        tool_end = next(event for event in events if event["type"] == "tool_end")
        self.assertTrue(tool_end["output"].endswith("… [truncated]"))
        self.assertEqual("settled", events[-1]["type"])


class ClaudeAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_uses_existing_permission_configuration(self) -> None:
        adapter = ClaudeAdapter()
        with mock.patch.object(adapter, "_spawn_jsonl", new=AsyncMock()) as spawn:
            await adapter.run(AgentRun("claude", "/tmp"), "hello", AsyncMock())

        self.assertNotIn("--permission-mode", spawn.await_args.args[1])

    def test_deepseek_environment_uses_anthropic_compatibility_endpoint(self) -> None:
        environment = ClaudeAdapter().environment(
            AgentRun(
                "claude",
                "/tmp",
                provider="deepseek",
                api_key="deepseek-secret",
                base_url="https://api.deepseek.com",
            )
        )

        self.assertEqual("https://api.deepseek.com/anthropic", environment["ANTHROPIC_BASE_URL"])
        self.assertEqual("deepseek-secret", environment["ANTHROPIC_AUTH_TOKEN"])

    async def test_preserves_content_block_order(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            executable = root / "claude"
            executable.write_text(
                """#!/usr/bin/env python3
import json
events = [
    {"type":"assistant","message":{"content":[
        {"type":"thinking","thinking":"inspect"},
        {"type":"text","text":"before tool"},
        {"type":"tool_use","id":"tool-1","name":"bash","input":{"command":"pwd"}},
        {"type":"text","text":"after tool"}
    ]}},
    {"type":"user","message":{"content":[
        {"type":"tool_result","tool_use_id":"tool-1","content":"/tmp"}
    ]}},
    {"type":"result","session_id":"session-1"},
]
for event in events:
    print(json.dumps(event), flush=True)
""",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            old_path = os.environ.get("PATH", "")
            os.environ["PATH"] = f"{tmp}:{old_path}"
            try:
                state = AgentRun("claude", tmp)
                events = []

                async def emit(event):
                    events.append(event)

                await ClaudeAdapter().run(state, "hello", emit)
            finally:
                os.environ["PATH"] = old_path

        self.assertEqual(
            ["thinking", "assistant_message", "tool_start", "assistant_message", "tool_end", "settled"],
            [event["type"] for event in events],
        )
        self.assertEqual("session-1", state.resume_id)


class OpenCodeAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_does_not_auto_approve_permissions(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            executable = root / "opencode"
            args_file = root / "args.json"
            executable.write_text(
                """#!/usr/bin/env python3
import json
import os
import sys
with open(os.environ["TERMISH_TEST_ARGS"], "w", encoding="utf-8") as output:
    json.dump(sys.argv[1:], output)
""",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            old_path = os.environ.get("PATH", "")
            os.environ["PATH"] = f"{tmp}:{old_path}"
            os.environ["TERMISH_TEST_ARGS"] = str(args_file)
            try:
                async def emit(_event):
                    pass

                await OpenCodeAdapter().run(AgentRun("opencode", tmp), "hello", emit)
            finally:
                os.environ["PATH"] = old_path
                os.environ.pop("TERMISH_TEST_ARGS", None)

            args = json.loads(args_file.read_text(encoding="utf-8"))
            self.assertNotIn("--auto", args)

    def test_deepseek_key_is_passed_only_through_the_environment(self) -> None:
        environment = OpenCodeAdapter().environment(
            AgentRun("opencode", "/tmp", provider="deepseek", api_key="deepseek-secret")
        )

        self.assertEqual("deepseek-secret", environment["DEEPSEEK_API_KEY"])


class GeminiAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_uses_existing_trust_and_approval_configuration(self) -> None:
        adapter = GeminiAdapter()
        with mock.patch.object(adapter, "_spawn_jsonl", new=AsyncMock()) as spawn:
            await adapter.run(AgentRun("gemini", "/tmp"), "hello", AsyncMock())

        args = spawn.await_args.args[1]
        self.assertNotIn("--approval-mode", args)
        self.assertNotIn("--skip-trust", args)


class PiAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_normalizes_rpc_stream_and_captures_session(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            executable = root / "pi"
            executable.write_text(
                """#!/usr/bin/env python3
import json
import os
import sys
with open(os.environ["TERMISH_TEST_DETAILS"], "w", encoding="utf-8") as output:
    json.dump({"args": sys.argv[1:], "key": os.environ.get("DEEPSEEK_API_KEY")}, output)
state = json.loads(sys.stdin.readline())
prompt = json.loads(sys.stdin.readline())
print(json.dumps({"id": state["id"], "type": "response", "command": "get_state", "success": True, "data": {"sessionFile": "/tmp/pi-session.jsonl"}}), flush=True)
print(json.dumps({"id": prompt["id"], "type": "response", "command": "prompt", "success": True}), flush=True)
print(json.dumps({"type": "message_update", "assistantMessageEvent": {"type": "thinking_delta", "delta": "inspect"}}), flush=True)
print(json.dumps({"type": "tool_execution_start", "toolCallId": "tool-1", "toolName": "bash", "args": {"command": "pwd"}}), flush=True)
print(json.dumps({"type": "tool_execution_end", "toolCallId": "tool-1", "toolName": "bash", "result": {"content": [{"type": "text", "text": "/tmp"}]}, "isError": False}), flush=True)
print(json.dumps({"type": "message_update", "assistantMessageEvent": {"type": "text_delta", "delta": "done"}}), flush=True)
print(json.dumps({"type": "agent_settled"}), flush=True)
sys.stdin.read()
""",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            old_path = os.environ.get("PATH", "")
            os.environ["PATH"] = f"{tmp}:{old_path}"
            details_file = root / "details.json"
            os.environ["TERMISH_TEST_DETAILS"] = str(details_file)
            try:
                state = AgentRun("pi", tmp, provider="deepseek", api_key="deepseek-secret")
                events = []

                async def emit(event):
                    events.append(event)

                await PiAdapter().run(state, "hello", emit)
            finally:
                os.environ["PATH"] = old_path
                os.environ.pop("TERMISH_TEST_DETAILS", None)
            details = json.loads(details_file.read_text(encoding="utf-8"))

        self.assertEqual("/tmp/pi-session.jsonl", state.resume_id)
        self.assertEqual(
            ["thinking_delta", "tool_start", "tool_end", "delta", "settled"],
            [event["type"] for event in events],
        )
        self.assertEqual("deepseek-secret", details["key"])
        self.assertIn("deepseek", details["args"])


if __name__ == "__main__":
    unittest.main()
