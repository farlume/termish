from __future__ import annotations

import asyncio
import os
import pathlib
import sys
import tempfile
import unittest
from unittest import mock


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "termish_agent"
sys.path.insert(0, str(SOURCE))

import store


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


class FailingAdapter(FakeAdapter):
    async def run(self, state, prompt, emit) -> None:
        raise RuntimeError("authentication required")


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

    async def test_status_only_thinking_phase_is_kept_before_tools(self) -> None:
        adapter = NoReasoningAdapter()
        with mock.patch.object(store, "ADAPTERS", {"codex": adapter}):
            session = self.store.create("codex", self.temp.name, None)
            await self.store.start_prompt(session["sessionId"], "Work", [])
            task = self.store.require(session["sessionId"]).task
            self.assertIsNotNone(task)
            await task

        messages = self.store.messages(session["sessionId"])
        self.assertEqual(
            ["user", "thinking", "tool", "assistant"],
            [message["role"] for message in messages],
        )
        self.assertEqual("", messages[1]["text"])

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
