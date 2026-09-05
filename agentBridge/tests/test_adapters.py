from __future__ import annotations

import io
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
    @staticmethod
    def write_fake_app_server(root: pathlib.Path, turn_events: str) -> pathlib.Path:
        executable = root / "codex"
        executable.write_text(
            f'''#!/usr/bin/env python3
import json
import os
import sys

requests = []
for raw in sys.stdin:
    message = json.loads(raw)
    requests.append(message)
    request_id = message.get("id")
    method = message.get("method")
    if method == "initialize":
        print(json.dumps({{"id": request_id, "result": {{}}}}), flush=True)
    elif method == "thread/start":
        print(json.dumps({{"id": request_id, "result": {{"thread": {{"id": "thread-1"}}}}}}), flush=True)
    elif method == "thread/resume":
        print(json.dumps({{"id": request_id, "result": {{"thread": {{"id": message["params"]["threadId"]}}}}}}), flush=True)
    elif method == "turn/start":
        print(json.dumps({{"id": request_id, "result": {{"turn": {{"id": "turn-1"}}}}}}), flush=True)
{turn_events}
if os.environ.get("TERMISH_TEST_REQUESTS"):
    with open(os.environ["TERMISH_TEST_REQUESTS"], "w", encoding="utf-8") as output:
        json.dump(requests, output)
''',
            encoding="utf-8",
        )
        executable.chmod(0o700)
        return executable

    async def test_silent_cli_times_out_with_sign_in_hint(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            executable = pathlib.Path(tmp) / "codex"
            executable.write_text(
                "#!/usr/bin/env python3\nimport time\ntime.sleep(2)\n",
                encoding="utf-8",
            )
            executable.chmod(0o700)
            old_path = os.environ.get("PATH", "")
            os.environ["PATH"] = f"{tmp}:{old_path}"
            try:
                with mock.patch("adapters.AGENT_START_TIMEOUT_SECONDS", 0.05):
                    with self.assertRaisesRegex(RuntimeError, "signed in"):
                        await CodexAdapter().run(AgentRun("codex", tmp), "hello", AsyncMock())
            finally:
                os.environ["PATH"] = old_path

    async def test_does_not_override_the_users_sandbox_or_approval_policy(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            requests_file = root / "requests.json"
            self.write_fake_app_server(
                root,
                '        print(json.dumps({"method":"turn/completed","params":{"turn":{"status":"completed"}}}), flush=True)',
            )
            with mock.patch.dict(
                os.environ,
                {
                    "PATH": f"{tmp}:{os.environ.get('PATH', '')}",
                    "TERMISH_TEST_REQUESTS": str(requests_file),
                },
            ):
                await CodexAdapter().run(AgentRun("codex", tmp), "hello", AsyncMock())
            requests = json.loads(requests_file.read_text(encoding="utf-8"))

        turn = next(message for message in requests if message.get("method") == "turn/start")
        self.assertNotIn("sandboxPolicy", turn["params"])
        self.assertNotIn("approvalPolicy", turn["params"])

    async def test_normalizes_jsonl_and_captures_resume_id(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.write_fake_app_server(
                root,
                '''        events = [
            {"method":"item/reasoning/summaryTextDelta","params":{"itemId":"reason-1","delta":"inspect"}},
            {"method":"item/started","params":{"item":{"id":"tool-1","type":"commandExecution","command":"pwd"}}},
            {"method":"item/completed","params":{"item":{"id":"tool-1","type":"commandExecution","command":"pwd","exitCode":0,"aggregatedOutput":"/tmp"}}},
            {"method":"item/agentMessage/delta","params":{"itemId":"msg-1","delta":"done"}},
            {"method":"turn/completed","params":{"turn":{"status":"completed"}}},
        ]
        for event in events:
            print(json.dumps(event), flush=True)''',
            )
            with mock.patch.dict(os.environ, {"PATH": f"{tmp}:{os.environ.get('PATH', '')}"}):
                state = AgentRun("codex", tmp)
                events = []

                async def emit(event):
                    events.append(event)

                await CodexAdapter().run(state, "hello", emit)

        self.assertEqual("thread-1", state.resume_id)
        self.assertIn("thinking_start", [event["type"] for event in events])
        self.assertIn({"type": "thinking_delta", "text": "inspect"}, events)
        self.assertIn("tool_start", [event["type"] for event in events])
        self.assertIn("tool_end", [event["type"] for event in events])
        self.assertIn({"type": "delta", "text": "done"}, events)
        self.assertEqual("settled", events[-1]["type"])

    async def test_accepts_jsonl_events_larger_than_asyncio_default_limit(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.write_fake_app_server(
                root,
                '''        print(json.dumps({
            "method":"item/completed",
            "params":{"item":{
                "id":"tool-large",
                "type":"commandExecution",
                "command":"cat large.log",
                "exitCode":0,
                "aggregatedOutput":"x" * (128 * 1024),
            }},
        }), flush=True)
        print(json.dumps({"method":"turn/completed","params":{"turn":{"status":"completed"}}}), flush=True)''',
            )
            with mock.patch.dict(os.environ, {"PATH": f"{tmp}:{os.environ.get('PATH', '')}"}):
                events = []

                async def emit(event):
                    events.append(event)

                await CodexAdapter().run(AgentRun("codex", tmp), "hello", emit)

        tool_end = next(event for event in events if event["type"] == "tool_end")
        self.assertTrue(tool_end["output"].endswith("… [truncated]"))
        self.assertEqual("settled", events[-1]["type"])

    async def test_routes_native_approval_to_client_and_resumes(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            response_file = root / "approval.json"
            self.write_fake_app_server(
                root,
                '''        print(json.dumps({
            "id":"approval-1",
            "method":"item/commandExecution/requestApproval",
            "params":{"command":"rm build.tmp","cwd":"/workspace","reason":"cleanup"},
        }), flush=True)
        approval = json.loads(sys.stdin.readline())
        with open(os.environ["TERMISH_TEST_APPROVAL"], "w", encoding="utf-8") as output:
            json.dump(approval, output)
        print(json.dumps({"method":"turn/completed","params":{"turn":{"status":"completed"}}}), flush=True)''',
            )
            approvals = []

            async def approve(request):
                approvals.append(request)
                return {"decision": "allow_session"}

            with mock.patch.dict(
                os.environ,
                {
                    "PATH": f"{tmp}:{os.environ.get('PATH', '')}",
                    "TERMISH_TEST_APPROVAL": str(response_file),
                },
            ):
                await CodexAdapter().run(
                    AgentRun("codex", tmp, approval_handler=approve),
                    "hello",
                    AsyncMock(),
                )
            response = json.loads(response_file.read_text(encoding="utf-8"))

        self.assertEqual("command", approvals[0]["kind"])
        self.assertEqual("rm build.tmp", approvals[0]["command"])
        self.assertEqual({"decision": "acceptForSession"}, response["result"])

    async def test_renders_managed_network_approval_without_a_fake_command(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            response_file = root / "network-approval.json"
            self.write_fake_app_server(
                root,
                '''        print(json.dumps({
            "id":"network-1",
            "method":"item/commandExecution/requestApproval",
            "params":{
                "reason":"Download dependencies",
                "networkApprovalContext":{"host":"repo.example.com","protocol":"https"},
                "availableDecisions":["accept","decline","cancel"]
            },
        }), flush=True)
        approval = json.loads(sys.stdin.readline())
        with open(os.environ["TERMISH_TEST_APPROVAL"], "w", encoding="utf-8") as output:
            json.dump(approval, output)
        print(json.dumps({"method":"turn/completed","params":{"turn":{"status":"completed"}}}), flush=True)''',
            )
            approvals = []

            async def approve(request):
                approvals.append(request)
                return {"decision": "allow_once"}

            with mock.patch.dict(
                os.environ,
                {
                    "PATH": f"{tmp}:{os.environ.get('PATH', '')}",
                    "TERMISH_TEST_APPROVAL": str(response_file),
                },
            ):
                await CodexAdapter().run(
                    AgentRun("codex", tmp, approval_handler=approve),
                    "hello",
                    AsyncMock(),
                )
            response = json.loads(response_file.read_text(encoding="utf-8"))

        self.assertEqual("network", approvals[0]["kind"])
        self.assertEqual("", approvals[0]["command"])
        self.assertIn("https://repo.example.com", approvals[0]["details"])
        self.assertEqual(["allow_once", "deny", "cancel"], approvals[0]["options"])
        self.assertEqual({"decision": "accept"}, response["result"])

    async def test_routes_native_user_questions_and_returns_all_answers(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            response_file = root / "answers.json"
            self.write_fake_app_server(
                root,
                '''        print(json.dumps({
            "id":"question-1",
            "method":"item/tool/requestUserInput",
            "params":{"questions":[
                {"id":"mode","header":"Mode","question":"Choose mode","options":[{"label":"Safe","description":"Read only"}]},
                {"id":"note","header":"Note","question":"Add a note","isSecret":True},
            ]},
        }), flush=True)
        answer = json.loads(sys.stdin.readline())
        with open(os.environ["TERMISH_TEST_ANSWERS"], "w", encoding="utf-8") as output:
            json.dump(answer, output)
        print(json.dumps({"method":"turn/completed","params":{"turn":{"status":"completed"}}}), flush=True)''',
            )
            approvals = []

            async def answer(request):
                approvals.append(request)
                value = "Safe" if request["kind"] == "select" else "private"
                decision = "select" if request["kind"] == "select" else "submit"
                return {"decision": decision, "value": value}

            with mock.patch.dict(
                os.environ,
                {
                    "PATH": f"{tmp}:{os.environ.get('PATH', '')}",
                    "TERMISH_TEST_ANSWERS": str(response_file),
                },
            ):
                await CodexAdapter().run(
                    AgentRun("codex", tmp, approval_handler=answer),
                    "hello",
                    AsyncMock(),
                )
            response = json.loads(response_file.read_text(encoding="utf-8"))

        self.assertEqual(["select", "input"], [request["kind"] for request in approvals])
        self.assertTrue(approvals[1]["secret"])
        self.assertEqual(
            {
                "answers": {
                    "mode": {"answers": ["Safe"]},
                    "note": {"answers": ["private"]},
                }
            },
            response["result"],
        )

    async def test_user_question_cancel_stops_the_sequence_and_keeps_native_timeout(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            response_file = root / "answers.json"
            self.write_fake_app_server(
                root,
                '''        print(json.dumps({
            "id":"question-1",
            "method":"item/tool/requestUserInput",
            "params":{"autoResolutionMs":5000,"questions":[
                {"id":"first","header":"First","question":"Continue?"},
                {"id":"second","header":"Second","question":"Should not open"}
            ]},
        }), flush=True)
        answer = json.loads(sys.stdin.readline())
        with open(os.environ["TERMISH_TEST_ANSWERS"], "w", encoding="utf-8") as output:
            json.dump(answer, output)
        print(json.dumps({"method":"turn/completed","params":{"turn":{"status":"completed"}}}), flush=True)''',
            )
            approvals = []

            async def cancel(request):
                approvals.append(request)
                return {"decision": "cancel"}

            with mock.patch.dict(
                os.environ,
                {
                    "PATH": f"{tmp}:{os.environ.get('PATH', '')}",
                    "TERMISH_TEST_ANSWERS": str(response_file),
                },
            ):
                await CodexAdapter().run(
                    AgentRun("codex", tmp, approval_handler=cancel),
                    "hello",
                    AsyncMock(),
                )
            response = json.loads(response_file.read_text(encoding="utf-8"))

        self.assertEqual(1, len(approvals))
        self.assertGreater(approvals[0]["timeoutMs"], 0)
        self.assertLessEqual(approvals[0]["timeoutMs"], 5000)
        self.assertEqual({"answers": {}}, response["result"])

    async def test_interrupted_turn_emits_cancelled_terminal_state(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            root = pathlib.Path(tmp)
            self.write_fake_app_server(
                root,
                '        print(json.dumps({"method":"turn/completed","params":{"turn":{"status":"interrupted"}}}), flush=True)',
            )
            events = []

            async def emit(event):
                events.append(event)

            with mock.patch.dict(os.environ, {"PATH": f"{tmp}:{os.environ.get('PATH', '')}"}):
                await CodexAdapter().run(AgentRun("codex", tmp), "hello", emit)

        self.assertEqual(["cancelled", "settled"], [event["type"] for event in events])


class ClaudeAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_auth_status_rejects_logged_out_json(self) -> None:
        adapter = ClaudeAdapter()
        with mock.patch.object(adapter, "available", return_value=True):
            with mock.patch.object(
                adapter,
                "_probe",
                new=AsyncMock(return_value=(0, '{"loggedIn":false}', "")),
            ):
                readiness = await adapter.readiness(AgentRun("claude", "/tmp"))

        self.assertEqual("login_required", readiness.status)
        self.assertEqual(
            "Claude Code is not signed in. Run `claude auth login` on the host first.",
            readiness.message,
        )
        self.assertNotIn("loggedIn", readiness.message)

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
                provider_type="deepseek",
                api_key="deepseek-secret",
                base_url="https://api.deepseek.com",
            )
        )

        self.assertEqual("https://api.deepseek.com/anthropic", environment["ANTHROPIC_BASE_URL"])
        self.assertEqual("deepseek-secret", environment["ANTHROPIC_AUTH_TOKEN"])

    def test_anthropic_type_provider_uses_base_url_directly(self) -> None:
        environment = ClaudeAdapter().environment(
            AgentRun(
                "claude",
                "/tmp",
                provider="anthropic",
                provider_type="anthropic",
                api_key="sk-ant-1",
                base_url="https://api.anthropic.com",
            )
        )

        self.assertEqual("https://api.anthropic.com", environment["ANTHROPIC_BASE_URL"])
        self.assertEqual("sk-ant-1", environment["ANTHROPIC_AUTH_TOKEN"])
        self.assertNotIn("ANTHROPIC_MODEL", environment)

    def test_openai_type_provider_with_anthropic_base_url(self) -> None:
        environment = ClaudeAdapter().environment(
            AgentRun(
                "claude",
                "/tmp",
                provider="deepseek",
                provider_type="openai",
                api_key="sk-1",
                base_url="https://api.deepseek.com",
                anthropic_base_url="https://api.deepseek.com/anthropic",
                model="deepseek-v4-pro",
            )
        )

        self.assertEqual("https://api.deepseek.com/anthropic", environment["ANTHROPIC_BASE_URL"])
        self.assertEqual("deepseek-v4-pro", environment["ANTHROPIC_MODEL"])

    def test_openai_type_without_anthropic_endpoint_skips_injection(self) -> None:
        environment = ClaudeAdapter().environment(
            AgentRun(
                "claude",
                "/tmp",
                provider="moonshot",
                provider_type="openai",
                api_key="sk-1",
                base_url="https://api.moonshot.cn/v1",
            )
        )
        self.assertNotIn("ANTHROPIC_AUTH_TOKEN", environment)

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
    async def test_empty_auth_list_is_rejected_before_prompt(self) -> None:
        adapter = OpenCodeAdapter()
        with mock.patch.object(adapter, "available", return_value=True):
            with mock.patch.object(
                adapter,
                "_probe",
                new=AsyncMock(return_value=(0, "0 credentials", "")),
            ):
                readiness = await adapter.readiness(AgentRun("opencode", "/tmp"))

        self.assertEqual("login_required", readiness.status)

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

        self.assertEqual("deepseek-secret", environment["OPENAI_API_KEY"])

    def test_openai_provider_injects_base_url(self) -> None:
        environment = OpenCodeAdapter().environment(
            AgentRun(
                "opencode",
                "/tmp",
                provider="deepseek",
                provider_type="openai",
                api_key="sk-1",
                base_url="https://api.deepseek.com",
            )
        )

        self.assertEqual("sk-1", environment["OPENAI_API_KEY"])
        self.assertEqual("https://api.deepseek.com", environment["OPENAI_BASE_URL"])

    def test_no_provider_keeps_environment_untouched(self) -> None:
        environment = OpenCodeAdapter().environment(AgentRun("opencode", "/tmp"))
        self.assertNotIn("OPENAI_API_KEY", environment)


class GeminiAdapterTest(unittest.IsolatedAsyncioTestCase):
    async def test_missing_auth_configuration_is_rejected_before_prompt(self) -> None:
        with tempfile.TemporaryDirectory() as tmp:
            adapter = GeminiAdapter()
            with mock.patch.object(adapter, "available", return_value=True):
                with mock.patch("pathlib.Path.home", return_value=pathlib.Path(tmp)):
                    with mock.patch.dict(
                        os.environ,
                        {
                            "GEMINI_API_KEY": "",
                            "GOOGLE_API_KEY": "",
                            "GOOGLE_APPLICATION_CREDENTIALS": "",
                        },
                    ):
                        readiness = await adapter.readiness(AgentRun("gemini", tmp))

        self.assertEqual("login_required", readiness.status)

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
print(json.dumps({"type": "extension_ui_request", "id": "approval-1", "method": "confirm", "title": "Allow?"}), flush=True)
print(json.dumps({"type": "message_update", "assistantMessageEvent": {"type": "text_delta", "delta": "done"}}), flush=True)
print(json.dumps({"type": "message_end", "message": {"role": "assistant", "content": [{"type": "text", "text": "done"}]}}), flush=True)
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
                state = AgentRun(
                    "pi",
                    tmp,
                    provider="openai",
                    provider_type="openai",
                    pi_provider="deepseek",
                    api_key="deepseek-secret",
                )
                events = []
                approvals = []

                async def emit(event):
                    events.append(event)

                async def approve(request):
                    approvals.append(request)
                    return {"decision": "deny"}

                state.approval_handler = approve

                await PiAdapter().run(state, "hello", emit)
            finally:
                os.environ["PATH"] = old_path
                os.environ.pop("TERMISH_TEST_DETAILS", None)
            details = json.loads(details_file.read_text(encoding="utf-8"))

        self.assertEqual("/tmp/pi-session.jsonl", state.resume_id)
        self.assertEqual(
            ["thinking_delta", "tool_start", "tool_end", "delta", "assistant_message", "settled"],
            [event["type"] for event in events],
        )
        self.assertEqual("confirm", approvals[0]["kind"])
        self.assertEqual("deepseek-secret", details["key"])
        self.assertIn("deepseek", details["args"])



    def test_codex_openai_provider_injection(self) -> None:
        environment = CodexAdapter().environment(
            AgentRun(
                "codex",
                "/tmp",
                provider="deepseek",
                provider_type="openai",
                api_key="sk-1",
                base_url="https://api.deepseek.com",
            )
        )
        self.assertEqual("sk-1", environment["OPENAI_API_KEY"])
        self.assertEqual("https://api.deepseek.com", environment["OPENAI_BASE_URL"])
class ProviderHelperTest(unittest.TestCase):
    def test_fetch_models_openai_format(self) -> None:
        from adapters import fetch_models
        with mock.patch("adapters.urllib.request.urlopen") as urlopen:
            import json as _json
            response = mock.MagicMock()
            response.__enter__.return_value.read.return_value = _json.dumps(
                {"data": [{"id": "deepseek-v4-pro"}, {"id": "deepseek-v4-flash"}]}
            ).encode("utf-8")
            urlopen.return_value = response
            models = fetch_models("https://api.deepseek.com/v1", "sk-1", "openai")
        self.assertEqual(["deepseek-v4-pro", "deepseek-v4-flash"], models)
        request = urlopen.call_args.args[0]
        self.assertEqual("https://api.deepseek.com/v1/models", request.full_url)
        self.assertEqual("Bearer sk-1", request.headers["Authorization"])

    def test_fetch_models_anthropic_headers(self) -> None:
        from adapters import fetch_models
        with mock.patch("adapters.urllib.request.urlopen") as urlopen:
            import json as _json
            response = mock.MagicMock()
            response.__enter__.return_value.read.return_value = _json.dumps(
                {"data": [{"id": "claude-3-7"}]}
            ).encode("utf-8")
            urlopen.return_value = response
            models = fetch_models("https://api.anthropic.com", "sk-ant-1", "anthropic")
        self.assertEqual(["claude-3-7"], models)
        request = urlopen.call_args.args[0]
        self.assertEqual("https://api.anthropic.com/v1/models", request.full_url)
        headers = dict(request.header_items())
        self.assertEqual("sk-ant-1", headers.get("X-api-key"))

    def test_fetch_models_http_error_surfaces_url(self) -> None:
        from adapters import fetch_models
        import urllib.error
        error_body = io.BytesIO(b"")
        with mock.patch("adapters.urllib.request.urlopen", side_effect=urllib.error.HTTPError("u", 401, "Unauthorized", None, error_body)):
            with self.assertRaises(RuntimeError) as ctx:
                fetch_models("https://api.example.com/v1", "bad", "openai")
        self.assertIn("HTTP 401", str(ctx.exception))
        self.assertIn("https://api.example.com/v1/models", str(ctx.exception))
        self.assertTrue(error_body.closed)

    def test_pi_environment_uses_provider_env_key(self) -> None:
        environment = PiAdapter().environment(
            AgentRun("pi", "/tmp", provider="openai", provider_type="openai", pi_provider="deepseek", api_key="sk-1")
        )
        self.assertEqual("sk-1", environment["DEEPSEEK_API_KEY"])

    def test_fetch_models_keeps_non_v1_api_path(self) -> None:
        from adapters import fetch_models
        with mock.patch("adapters.urllib.request.urlopen") as urlopen:
            response = mock.MagicMock()
            response.__enter__.return_value.read.return_value = b'{"data":[{"id":"glm-4"}]}'
            urlopen.return_value = response
            fetch_models("https://open.bigmodel.cn/api/paas/v4", "sk-1", "openai")
        self.assertEqual(
            "https://open.bigmodel.cn/api/paas/v4/models",
            urlopen.call_args.args[0].full_url,
        )

    def test_provider_support_matrix(self) -> None:
        from adapters import provider_supported
        self.assertTrue(provider_supported("claude", "anthropic"))
        self.assertTrue(provider_supported("claude", "deepseek"))
        self.assertFalse(provider_supported("claude", "openai"))
        self.assertTrue(provider_supported("claude", "openai", "https://api.deepseek.com/anthropic"))
        self.assertTrue(provider_supported("opencode", "openai"))
        self.assertTrue(provider_supported("pi", "openai"))
        self.assertFalse(provider_supported("codex", "openai"))
        self.assertFalse(provider_supported("gemini", "openai"))
        self.assertTrue(provider_supported("pi", None))  # 无 provider = 自带凭据


if __name__ == "__main__":
    unittest.main()
