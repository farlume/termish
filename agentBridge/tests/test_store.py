from __future__ import annotations

import asyncio
import os
import pathlib
import sys
import tempfile
import unittest
from collections import deque
from unittest import mock


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "termish_agent"
sys.path.insert(0, str(SOURCE))

import store
from native_history import NativeMessage, NativeSession


class ArtifactDetectionTest(unittest.TestCase):
    def test_detects_recent_document_outputs_and_ignores_old_inputs(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            old_input = root / "source.xlsx"
            old_input.write_bytes(b"input")
            os.utime(old_input, (1, 1))
            output = root / "quarterly report.pptx"
            output.write_bytes(b"slides")
            started_at = int(output.stat().st_mtime * 1000)

            artifacts = store.detect_artifacts(
                temp,
                ['python build.py source.xlsx --output "quarterly report.pptx"'],
                started_at,
            )

            self.assertEqual(["quarterly report.pptx"], [item["name"] for item in artifacts])
            self.assertEqual("presentation", artifacts[0]["kind"])
            self.assertEqual(len(b"slides"), artifacts[0]["size"])

    def test_supports_markdown_spreadsheets_documents_pdf_and_images(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            root = pathlib.Path(temp)
            names = ["notes.md", "data.xlsx", "brief.docx", "report.pdf", "chart.png"]
            for name in names:
                (root / name).write_text(name, encoding="utf-8")

            artifacts = store.detect_artifacts(temp, [" ".join(names)], 0)

            self.assertEqual(names, [item["name"] for item in artifacts])


class FakeAdapter:
    def __init__(self) -> None:
        self.prompts = []

    def available(self) -> bool:
        return True

    async def run(self, state, prompt, emit) -> None:
        self.prompts.append(prompt)
        await emit({"type": "thinking", "text": "Inspect the project"})
        await emit({"type": "tool_start", "name": "bash", "args": "pwd", "toolId": "tool-1"})
        await emit({"type": "tool_end", "name": "bash", "output": "/workspace", "toolId": "tool-1", "isError": False})
        await emit({"type": "assistant_message", "text": "done"})
        await emit({"type": "settled"})

    async def abort(self, state) -> None:
        pass


class SegmentedAdapter(FakeAdapter):
    async def run(self, state, prompt, emit) -> None:
        await emit({"type": "thinking", "text": "First thought"})
        await emit({"type": "assistant_message", "text": "Progress update"})
        await emit({"type": "thinking", "text": "Second thought"})
        await emit({"type": "tool_start", "name": "bash", "args": "pwd", "toolId": "tool-1"})
        await emit({"type": "tool_end", "name": "bash", "output": "/workspace", "toolId": "tool-1", "isError": False})
        await emit({"type": "assistant_message", "text": "Final answer"})
        await emit({"type": "settled"})


class NoReasoningAdapter(FakeAdapter):
    async def run(self, state, prompt, emit) -> None:
        await emit({"type": "tool_start", "name": "bash", "args": "pwd", "toolId": "tool-1"})
        await emit({"type": "tool_end", "name": "bash", "output": "/workspace", "toolId": "tool-1", "isError": False})
        await emit({"type": "assistant_message", "text": "done"})
        await emit({"type": "settled"})


class EmptyReasoningAdapter(FakeAdapter):
    async def run(self, state, prompt, emit) -> None:
        await emit({"type": "thinking_start"})
        await emit({"type": "assistant_message", "text": "done"})
        await emit({"type": "settled"})


class ReconnectSnapshotAdapter(FakeAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.reasoning_ready = asyncio.Event()
        self.continue_to_tool = asyncio.Event()
        self.tool_ready = asyncio.Event()
        self.continue_to_answer = asyncio.Event()
        self.answer_ready = asyncio.Event()
        self.finish_answer = asyncio.Event()

    async def run(self, state, prompt, emit) -> None:
        await emit({"type": "thinking_start"})
        await emit({"type": "thinking_delta", "text": "Inspecting"})
        self.reasoning_ready.set()
        await self.continue_to_tool.wait()
        await emit({"type": "tool_start", "name": "bash", "args": "pwd", "toolId": "tool-1"})
        self.tool_ready.set()
        await self.continue_to_answer.wait()
        await emit({"type": "tool_end", "name": "bash", "output": "/workspace", "toolId": "tool-1"})
        await emit({"type": "delta", "text": "hel"})
        self.answer_ready.set()
        await self.finish_answer.wait()
        await emit({"type": "delta", "text": "lo"})
        await emit({"type": "settled"})


class BlockingAdapter(FakeAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.release = asyncio.Event()
        self.aborted = False

    async def run(self, state, prompt, emit) -> None:
        await self.release.wait()

    async def abort(self, state) -> None:
        self.aborted = True
        self.release.set()


class BlockingReadinessAdapter(BlockingAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.readiness_started = asyncio.Event()
        self.release_readiness = asyncio.Event()

    async def readiness(self, state):
        self.readiness_started.set()
        await self.release_readiness.wait()
        return store.AgentReadiness("ready")


class FailingAdapter(FakeAdapter):
    async def run(self, state, prompt, emit) -> None:
        raise RuntimeError("authentication required")


class LeakingAdapter(FakeAdapter):
    async def run(self, state, prompt, emit) -> None:
        await emit(
            {
                "type": "tool_start",
                "name": "debug",
                "args": "Authorization: Bearer sk-tool-secret",
                "toolId": "secret-tool",
            }
        )
        await emit(
            {
                "type": "tool_end",
                "name": "debug",
                "output": '{"api_key":"sk-output-secret"}',
                "toolId": "secret-tool",
            }
        )
        await emit({"type": "assistant_message", "text": "password=secret-password"})
        await emit({"type": "settled"})


class LoginRequiredAdapter(FakeAdapter):
    async def readiness(self, state):
        return store.AgentReadiness("login_required", "Agent login required")


class ApprovalAdapter(FakeAdapter):
    def __init__(self) -> None:
        super().__init__()
        self.requested = asyncio.Event()
        self.response = None

    async def run(self, state, prompt, emit) -> None:
        self.requested.set()
        self.response = await state.approval_handler(
            {
                "kind": "command",
                "title": "Approve command",
                "message": "Run tests?",
                "command": "make test",
                "cwd": state.cwd,
                "options": ["allow_once", "allow_session", "deny"],
            }
        )
        await emit({"type": "assistant_message", "text": "continued"})
        await emit({"type": "settled"})


class SessionStoreTest(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self) -> None:
        self.temp = tempfile.TemporaryDirectory()
        self.environment = mock.patch.dict(os.environ, {"TERMISH_AGENT_HOME": self.temp.name})
        self.environment.start()
        self.adapter = FakeAdapter()
        self.adapters = mock.patch.object(store, "ADAPTERS", {"codex": self.adapter})
        self.adapters.start()
        self.events = []

        async def broadcast(event):
            self.events.append(event)

        self.store = store.SessionStore(broadcast)

    async def asyncTearDown(self) -> None:
        self.store.close()
        self.adapters.stop()
        self.environment.stop()
        self.temp.cleanup()

    async def test_attachment_metadata_and_prompt_reference_are_preserved(self) -> None:
        session = self.store.create("codex", self.temp.name, None)
        attachments = [{"name": "notes.txt", "path": ".termish/attachments/notes.txt", "size": 42}]

        turn_id = await self.store.start_prompt(session["sessionId"], "Review this", attachments)
        task = self.store.require(session["sessionId"]).task
        self.assertIsNotNone(task)
        await task

        messages = self.store.messages(session["sessionId"])
        self.assertEqual(attachments, messages[0]["attachments"])
        self.assertEqual(["user", "thinking", "tool", "assistant"], [message["role"] for message in messages])
        self.assertTrue(all(message["turnId"] == turn_id for message in messages))
        self.assertEqual("tool-1", messages[2]["activityId"])
        self.assertEqual("pwd", messages[2]["toolInput"])
        self.assertIn(".termish/attachments/notes.txt", self.adapter.prompts[0])
        self.assertEqual("Review this", self.store.require(session["sessionId"]).title)

    async def test_session_can_be_renamed(self) -> None:
        session = self.store.create("codex", self.temp.name, None)
        renamed = self.store.rename(session["sessionId"], "Release review")

        self.assertEqual("Release review", renamed["title"])
        row = self.store.db.execute("SELECT title FROM sessions WHERE id=?", (session["sessionId"],)).fetchone()
        self.assertEqual("Release review", row["title"])

    async def test_native_history_import_is_idempotent_and_resumable(self) -> None:
        native = NativeSession(
            "codex",
            "native-thread-1",
            "native-thread-1",
            "Imported conversation",
            self.temp.name,
            1000,
            2000,
            [
                NativeMessage("user", "Original question", 1100),
                NativeMessage("assistant", "Original answer", 1200),
            ],
        )
        with (
            mock.patch.object(store, "resolve_binary", return_value=None),
            mock.patch.object(store, "find_native_session", return_value=native),
        ):
            first = await self.store.import_native_history("codex", native.native_id)
            second = await self.store.import_native_history("codex", native.native_id)

        self.assertEqual(first["sessionId"], second["sessionId"])
        imported = self.store.require(first["sessionId"])
        self.assertEqual(native.resume_id, imported.resume_id)
        self.assertEqual(
            ["Original question", "Original answer"],
            [message["text"] for message in self.store.messages(imported.id)],
        )
        count = self.store.db.execute("SELECT COUNT(*) FROM sessions WHERE resume_id=?", (native.resume_id,)).fetchone()[0]
        self.assertEqual(1, count)

    async def test_session_model_can_be_changed_while_idle(self) -> None:
        session = self.store.create("codex", self.temp.name, "old-model")

        updated = self.store.set_model(session["sessionId"], "new-model")

        self.assertEqual("new-model", updated["model"])
        self.assertEqual("new-model", self.store.require(session["sessionId"]).model)

    async def test_intermediate_messages_and_thinking_blocks_get_distinct_ids(self) -> None:
        segmented = SegmentedAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": segmented}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Work", [])
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        messages = self.store.messages(session["sessionId"])
        self.assertEqual(
            ["user", "thinking", "assistant", "thinking", "tool", "assistant"],
            [message["role"] for message in messages],
        )
        assistant_ids = [message["activityId"] for message in messages if message["role"] == "assistant"]
        thinking_ids = [message["activityId"] for message in messages if message["role"] == "thinking"]
        self.assertEqual(2, len(set(assistant_ids)))
        self.assertEqual(2, len(set(thinking_ids)))

    async def test_agent_without_reasoning_does_not_create_empty_thinking_card(self) -> None:
        adapter = NoReasoningAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Work", [])
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        messages = self.store.messages(session["sessionId"])
        self.assertEqual(
            ["user", "tool", "assistant"],
            [message["role"] for message in messages],
        )

    async def test_empty_reasoning_start_does_not_create_empty_thinking_card(self) -> None:
        adapter = EmptyReasoningAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Work", [])
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        messages = self.store.messages(session["sessionId"])
        self.assertEqual(["user", "assistant"], [message["role"] for message in messages])

    async def test_running_activities_are_restored_for_reconnecting_clients(self) -> None:
        adapter = ReconnectSnapshotAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Work", [])

            await adapter.reasoning_ready.wait()
            reasoning_snapshot = self.store.messages(session["sessionId"])
            self.assertEqual(["user", "thinking"], [message["role"] for message in reasoning_snapshot])
            self.assertEqual("Inspecting", reasoning_snapshot[-1]["text"])
            self.assertTrue(reasoning_snapshot[-1]["running"])
            running = self.store.require(session["sessionId"])
            replay = self.store.replay_events(session["sessionId"], self.store.event_epoch, 1)
            self.assertEqual(
                ["thinking_start", "thinking_delta"],
                [envelope["event"]["type"] for envelope in replay["events"]],
            )
            self.assertEqual(running.event_seq, replay["eventCursor"])

            adapter.continue_to_tool.set()
            await adapter.tool_ready.wait()
            tool_snapshot = self.store.messages(session["sessionId"])
            self.assertEqual(["user", "thinking", "tool"], [message["role"] for message in tool_snapshot])
            self.assertEqual("pwd", tool_snapshot[-1]["toolInput"])
            self.assertTrue(tool_snapshot[-1]["running"])

            adapter.continue_to_answer.set()
            await adapter.answer_ready.wait()
            answer_snapshot = self.store.messages(session["sessionId"])
            self.assertEqual(
                ["user", "thinking", "tool", "assistant"],
                [message["role"] for message in answer_snapshot],
            )
            self.assertEqual("hel", answer_snapshot[-1]["text"])
            self.assertTrue(answer_snapshot[-1]["running"])

            adapter.finish_answer.set()
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        completed = self.store.messages(session["sessionId"])
        self.assertEqual("hello", completed[-1]["text"])
        self.assertNotIn("running", completed[-1])

    async def test_event_replay_uses_monotonic_cursors_and_deduplicates(self) -> None:
        session = self.store.create("codex", self.temp.name, None)
        await self.store.start_prompt(session["sessionId"], "Work", [])
        task = self.store.require(session["sessionId"]).task
        self.assertIsNotNone(task)
        await task

        info = self.store.session_info(self.store.require(session["sessionId"]))
        replay = self.store.replay_events(session["sessionId"], info["eventEpoch"], 2)
        sequences = [event["eventSeq"] for event in replay["events"]]

        self.assertFalse(replay["reset"])
        self.assertEqual(list(range(3, info["eventCursor"] + 1)), sequences)
        self.assertTrue(all(event["eventEpoch"] == info["eventEpoch"] for event in replay["events"]))
        caught_up = self.store.replay_events(
            session["sessionId"],
            info["eventEpoch"],
            info["eventCursor"],
        )
        self.assertEqual([], caught_up["events"])
        self.assertFalse(caught_up["reset"])

    async def test_event_replay_falls_back_to_snapshot_for_epoch_or_buffer_gap(self) -> None:
        session_info = self.store.create("codex", self.temp.name, None)
        session = self.store.require(session_info["sessionId"])
        epoch_reset = self.store.replay_events(session.id, "old-bridge", 0)
        self.assertTrue(epoch_reset["reset"])

        session.event_journal = deque(maxlen=2)
        await self.store._publish(session, {"type": "busy", "sessionId": session.id, "busy": True})
        await self.store._publish(session, {"type": "event", "sessionId": session.id, "event": {"type": "delta"}})
        await self.store._publish(session, {"type": "busy", "sessionId": session.id, "busy": False})

        gap_reset = self.store.replay_events(session.id, self.store.event_epoch, 0)
        self.assertTrue(gap_reset["reset"])
        self.assertEqual([], gap_reset["events"])

    async def test_removing_running_session_waits_for_agent_cleanup(self) -> None:
        adapter = BlockingAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Work", [])
            running = self.store.require(session["sessionId"])
            task = running.task
            self.assertIsNotNone(task)
            await asyncio.sleep(0)

            await self.store.remove(session["sessionId"])

        self.assertTrue(adapter.aborted)
        self.assertTrue(task.done())
        self.assertNotIn(session["sessionId"], self.store.sessions)

    async def test_concurrent_prompt_start_is_serialized_per_session(self) -> None:
        adapter = BlockingReadinessAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            first = asyncio.create_task(
                self.store.start_prompt(session["sessionId"], "First", [])
            )
            await adapter.readiness_started.wait()
            second = asyncio.create_task(
                self.store.start_prompt(session["sessionId"], "Second", [])
            )
            await asyncio.sleep(0)
            self.assertFalse(second.done())

            adapter.release_readiness.set()
            await first
            with self.assertRaisesRegex(RuntimeError, "session is busy"):
                await second
            await self.store.abort(session["sessionId"])

        messages = self.store.messages(session["sessionId"])
        self.assertEqual(["First"], [message["text"] for message in messages if message["role"] == "user"])

    async def test_agent_failure_always_settles_the_turn(self) -> None:
        adapter = FailingAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Work", [])
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        event_types = [event.get("event", {}).get("type") for event in self.events if event.get("type") == "event"]
        self.assertIn("error", event_types)
        self.assertEqual(1, event_types.count("settled"))
        self.assertFalse(self.store.require(session["sessionId"]).busy)

    async def test_agent_output_is_redacted_before_events_and_database_persistence(self) -> None:
        adapter = LeakingAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Do work", [])
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        serialized_messages = str(self.store.messages(session["sessionId"]))
        serialized_events = str(self.events)
        for secret in ("sk-tool-secret", "sk-output-secret", "secret-password"):
            self.assertNotIn(secret, serialized_messages)
            self.assertNotIn(secret, serialized_events)
        self.assertIn("[REDACTED]", serialized_messages)

    async def test_login_is_checked_before_accepting_or_persisting_message(self) -> None:
        adapter = LoginRequiredAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            with self.assertRaisesRegex(RuntimeError, "login required"):
                await self.store.start_prompt(session["sessionId"], "Do work", [])

        self.assertEqual([], self.store.messages(session["sessionId"]))
        self.assertFalse(self.store.require(session["sessionId"]).busy)

    async def test_readiness_endpoint_rejects_before_attachment_upload_or_persistence(self) -> None:
        adapter = LoginRequiredAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            with self.assertRaisesRegex(RuntimeError, "login required"):
                await self.store.dispatch(
                    "agents.check",
                    {"sessionId": session["sessionId"], "provider": {}},
                )

        self.assertEqual([], self.store.messages(session["sessionId"]))
        self.assertFalse(self.store.require(session["sessionId"]).busy)

    async def test_pending_approval_is_snapshotted_and_resumed_by_response(self) -> None:
        adapter = ApprovalAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Do work", [])
            await adapter.requested.wait()
            await asyncio.sleep(0)

            pending = self.store.approvals(session["sessionId"])
            self.assertEqual(1, len(pending))
            self.assertEqual("make test", pending[0]["command"])
            self.assertTrue(self.store.session_info(self.store.require(session["sessionId"]))["waitingApproval"])

            await self.store.respond_approval(
                session["sessionId"],
                pending[0]["approvalId"],
                "allow_session",
                None,
            )
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        self.assertEqual({"decision": "allow_session", "value": None}, adapter.response)
        self.assertEqual([], self.store.approvals(session["sessionId"]))
        event_types = [event.get("event", {}).get("type") for event in self.events if event.get("type") == "event"]
        self.assertIn("approval_request", event_types)
        self.assertIn("approval_resolved", event_types)

    async def test_abort_cancels_pending_approval_without_hanging(self) -> None:
        adapter = ApprovalAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Do work", [])
            await adapter.requested.wait()
            await asyncio.sleep(0)
            await asyncio.wait_for(self.store.abort(session["sessionId"]), timeout=1)

        self.assertEqual("cancel", adapter.response["decision"])
        self.assertFalse(self.store.require(session["sessionId"]).busy)

    async def test_provider_type_and_local_configuration_id_are_kept_separate(self) -> None:
        provider = {"id": "local-deepseek-1", "type": "deepseek"}
        with mock.patch.object(store, "ADAPTERS", {"claude": self.adapter}):
            session = self.store.create("claude", self.temp.name, None, provider)
            await self.store.start_prompt(
                session["sessionId"],
                "Work",
                [],
                {**provider, "apiKey": "secret-deepseek-key", "baseUrl": "https://api.deepseek.com"},
            )
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        restored = self.store.require(session["sessionId"])
        self.assertEqual("deepseek", restored.provider)
        self.assertEqual("local-deepseek-1", restored.provider_config_id)
        self.assertEqual("local-deepseek-1", self.store.session_info(restored)["provider"])
        self.assertEqual("deepseek", self.store.session_info(restored)["providerType"])
        self.assertNotIn("secret-deepseek-key", pathlib.Path(self.store.db.execute("PRAGMA database_list").fetchone()[2]).read_text(errors="ignore"))

    async def test_claude_accepts_openai_provider_with_anthropic_endpoint(self) -> None:
        provider = {
            "id": "local-deepseek-1",
            "type": "openai",
            "anthropicBaseUrl": "https://api.deepseek.com/anthropic",
        }
        with mock.patch.object(store, "ADAPTERS", {"claude": self.adapter}):
            session = self.store.create("claude", self.temp.name, None, provider)

        self.assertEqual("openai", self.store.require(session["sessionId"]).provider)

    async def test_missing_provider_key_is_rejected_before_message_is_accepted(self) -> None:
        provider = {"id": "local-deepseek-1", "type": "deepseek"}
        with mock.patch.object(store, "ADAPTERS", {"claude": self.adapter}):
            session = self.store.create("claude", self.temp.name, None, provider)
            with self.assertRaisesRegex(RuntimeError, "API key is missing"):
                await self.store.start_prompt(session["sessionId"], "Work", [], provider)

        self.assertEqual([], self.store.messages(session["sessionId"]))
        self.assertFalse(self.store.require(session["sessionId"]).busy)

    async def test_incompatible_provider_is_rejected(self) -> None:
        with self.assertRaisesRegex(RuntimeError, "not compatible"):
            self.store.create(
                "codex",
                self.temp.name,
                None,
                {"id": "local-deepseek-1", "type": "deepseek"},
            )


if __name__ == "__main__":
    unittest.main()
