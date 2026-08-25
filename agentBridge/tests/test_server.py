from __future__ import annotations

import json
import os
import pathlib
import select
import signal
import subprocess
import sys
import tempfile
import time
import unittest


MAIN = pathlib.Path(__file__).resolve().parents[1] / "termish_agent" / "__main__.py"


class RelayTest(unittest.TestCase):
    def test_relay_forwards_request_without_stdin_eof(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            environment = os.environ.copy()
            environment["TERMISH_AGENT_HOME"] = temp
            process = subprocess.Popen(
                [sys.executable, str(MAIN), "connect"],
                stdin=subprocess.PIPE,
                stdout=subprocess.PIPE,
                stderr=subprocess.PIPE,
                env=environment,
            )
            try:
                request = {"id": 1, "method": "system.hello", "params": {}}
                process.stdin.write((json.dumps(request) + "\n").encode("utf-8"))
                process.stdin.flush()
                readable, _, _ = select.select([process.stdout], [], [], 5)
                self.assertTrue(readable, "relay waited for stdin EOF instead of forwarding the request")
                response = json.loads(process.stdout.readline())
                self.assertEqual(1, response["id"])
                self.assertEqual(3, response["result"]["protocolVersion"])
            finally:
                process.terminate()
                process.wait(timeout=5)
                process.stdin.close()
                process.stdout.close()
                process.stderr.close()
                pid_path = pathlib.Path(temp) / "runtime" / "agent.pid"
                if pid_path.exists():
                    os.kill(int(pid_path.read_text(encoding="utf-8")), signal.SIGTERM)
                    time.sleep(0.1)

    def test_restart_replaces_daemon_and_reaps_previous_process(self) -> None:
        with tempfile.TemporaryDirectory() as temp:
            environment = os.environ.copy()
            environment["TERMISH_AGENT_HOME"] = temp
            pid_path = pathlib.Path(temp) / "runtime" / "agent.pid"
            subprocess.run(
                [sys.executable, str(MAIN), "ensure-running"],
                check=True,
                capture_output=True,
                env=environment,
                timeout=10,
            )
            previous_pid = int(pid_path.read_text(encoding="utf-8"))
            try:
                subprocess.run(
                    [sys.executable, str(MAIN), "restart"],
                    check=True,
                    capture_output=True,
                    env=environment,
                    timeout=15,
                )
                current_pid = int(pid_path.read_text(encoding="utf-8"))
                self.assertNotEqual(previous_pid, current_pid)
                with self.assertRaises(ProcessLookupError):
                    os.kill(previous_pid, 0)
            finally:
                if pid_path.exists():
                    current_pid = int(pid_path.read_text(encoding="utf-8"))
                    os.killpg(os.getpgid(current_pid), signal.SIGTERM)
                    time.sleep(0.1)


if __name__ == "__main__":
    unittest.main()
