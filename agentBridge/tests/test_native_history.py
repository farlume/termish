from __future__ import annotations

import json
import pathlib
import sys
import tempfile
import unittest
from unittest import mock


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "termish_agent"
sys.path.insert(0, str(SOURCE))

import native_history


def write_jsonl(path: pathlib.Path, values: list[dict]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("\n".join(json.dumps(value) for value in values) + "\n", encoding="utf-8")


class NativeHistoryTest(unittest.TestCase):
    def setUp(self) -> None:
        native_history._native_cache.clear()

    def test_codex_history_filters_injected_context(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            home = pathlib.Path(temp)
            cwd = home / "project"
            cwd.mkdir()
            path = home / ".codex/sessions/2026/08/25/rollout-test.jsonl"
            write_jsonl(
                path,
                [
                    {
                        "type": "session_meta",
                        "timestamp": "2026-08-25T01:00:00Z",
                        "payload": {"id": "codex-1", "cwd": str(cwd)},
                    },
                    {
                        "type": "response_item",
                        "timestamp": "2026-08-25T01:00:01Z",
                        "payload": {
                            "type": "message",
                            "role": "user",
                            "content": [{"type": "input_text", "text": "<environment_context>hidden</environment_context>"}],
                        },
                    },
                    {
                        "type": "response_item",
                        "timestamp": "2026-08-25T01:00:02Z",
                        "payload": {
                            "type": "message",
                            "role": "user",
                            "content": [{"type": "input_text", "text": "Fix the release build"}],
                        },
                    },
                    {
                        "type": "response_item",
                        "timestamp": "2026-08-25T01:00:03Z",
                        "payload": {
                            "type": "message",
                            "role": "assistant",
                            "content": [{"type": "output_text", "text": "Done"}],
                        },
                    },
                ],
            )

            sessions = native_history._codex_sessions(home)

            self.assertEqual(1, len(sessions))
            self.assertEqual("codex-1", sessions[0].native_id)
            self.assertEqual("Fix the release build", sessions[0].title)
            self.assertEqual(["user", "assistant"], [message.role for message in sessions[0].messages])

    def test_claude_and_pi_histories_preserve_resume_identifiers(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            home = pathlib.Path(temp)
            cwd = home / "project"
            cwd.mkdir()
            claude_path = home / ".claude/projects/project/claude-1.jsonl"
            write_jsonl(
                claude_path,
                [
                    {
                        "type": "user",
                        "sessionId": "claude-1",
                        "cwd": str(cwd),
                        "timestamp": "2026-08-25T02:00:00Z",
                        "message": {"role": "user", "content": "Review this"},
                    },
                    {
                        "type": "assistant",
                        "sessionId": "claude-1",
                        "cwd": str(cwd),
                        "timestamp": "2026-08-25T02:00:01Z",
                        "message": {"role": "assistant", "content": [{"type": "text", "text": "Reviewed"}]},
                    },
                ],
            )
            pi_path = home / ".pi/agent/sessions/project/pi-1.jsonl"
            write_jsonl(
                pi_path,
                [
                    {"type": "session", "id": "pi-1", "cwd": str(cwd), "timestamp": "2026-08-25T03:00:00Z"},
                    {
                        "type": "message",
                        "message": {"role": "user", "content": [{"type": "text", "text": "Build it"}], "timestamp": 1_777_000_000_000},
                    },
                    {
                        "type": "message",
                        "message": {"role": "assistant", "content": [{"type": "text", "text": "Built"}], "timestamp": 1_777_000_001_000},
                    },
                ],
            )

            claude = native_history._claude_sessions(home)[0]
            pi = native_history._pi_sessions(home)[0]

            self.assertEqual("claude-1", claude.resume_id)
            self.assertEqual(str(pi_path.resolve()), pi.resume_id)
            self.assertEqual(["Review this", "Reviewed"], [message.text for message in claude.messages])
            self.assertEqual(["Build it", "Built"], [message.text for message in pi.messages])

    def test_gemini_jsonl_rewind_uses_project_root(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            home = pathlib.Path(temp)
            cwd = home / "project"
            cwd.mkdir()
            project = home / ".gemini/tmp/project-hash"
            project.mkdir(parents=True)
            (project / ".project_root").write_text(str(cwd), encoding="utf-8")
            path = project / "chats/session-test.jsonl"
            write_jsonl(
                path,
                [
                    {
                        "sessionId": "gemini-1",
                        "projectHash": "project-hash",
                        "startTime": "2026-08-25T04:00:00Z",
                        "lastUpdated": "2026-08-25T04:00:05Z",
                    },
                    {"id": "u1", "type": "user", "timestamp": "2026-08-25T04:00:01Z", "content": [{"text": "Explain it"}]},
                    {"id": "a1", "type": "gemini", "timestamp": "2026-08-25T04:00:02Z", "content": [{"text": "First answer"}]},
                    {"id": "u2", "type": "user", "timestamp": "2026-08-25T04:00:03Z", "content": [{"text": "Discard me"}]},
                    {"$rewindTo": "u2"},
                ],
            )

            sessions = native_history._gemini_sessions(home)

            self.assertEqual(1, len(sessions))
            self.assertEqual("gemini-1", sessions[0].resume_id)
            self.assertEqual(str(cwd), sessions[0].cwd)
            self.assertEqual(["Explain it", "First answer"], [message.text for message in sessions[0].messages])

    def test_list_deduplicates_by_agent_and_native_id(self) -> None:
        newer = native_history.NativeSession("codex", "same", "same", "New", "/tmp", 2, 3, [])
        older = native_history.NativeSession("codex", "same", "same", "Old", "/tmp", 1, 2, [])
        with (
            mock.patch.object(native_history.pathlib.Path, "home", return_value=pathlib.Path("/tmp")),
            mock.patch.object(native_history, "_codex_sessions", return_value=[older, newer]),
            mock.patch.object(native_history, "_claude_sessions", return_value=[]),
            mock.patch.object(native_history, "_gemini_sessions", return_value=[]),
            mock.patch.object(native_history, "_opencode_sessions", return_value=[]),
            mock.patch.object(native_history, "_pi_sessions", return_value=[]),
        ):
            sessions = native_history.list_native_sessions()

        self.assertEqual(["New"], [session.title for session in sessions])

    def test_list_reuses_short_lived_scan_cache_and_force_refresh_bypasses_it(self) -> None:
        session = native_history.NativeSession("codex", "one", "one", "Cached", "/tmp", 1, 2, [])
        with (
            mock.patch.object(native_history.pathlib.Path, "home", return_value=pathlib.Path("/tmp/cache-test")),
            mock.patch.object(native_history, "_codex_sessions", return_value=[session]) as codex,
            mock.patch.object(native_history, "_claude_sessions", return_value=[]),
            mock.patch.object(native_history, "_gemini_sessions", return_value=[]),
            mock.patch.object(native_history, "_opencode_sessions", return_value=[]),
            mock.patch.object(native_history, "_pi_sessions", return_value=[]),
        ):
            native_history.list_native_sessions()
            native_history.list_native_sessions()
            native_history.list_native_sessions(force_refresh=True)

        self.assertEqual(2, codex.call_count)
