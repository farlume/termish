# SPDX-License-Identifier: MIT
import json
import os
from pathlib import Path
import shutil
import socket
import struct
import subprocess
import sys
import tempfile
import time
import unittest

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / "screenService/macos/screen-relay.py"
VERSION = next(line.split("=", 1)[1] for line in (ROOT / "screenService/service.properties").read_text().splitlines() if line.startswith("RELAY_VERSION="))
RENDERED = SOURCE.read_text().replace("@RELAY_VERSION@", VERSION)
relay = type(sys)("relay_under_test")
exec(compile(RENDERED, str(SOURCE), "exec"), relay.__dict__)


def eventually(test, timeout=5):
    end = time.monotonic() + timeout
    while not test():
        if time.monotonic() > end:
            raise AssertionError("condition not reached")
        time.sleep(0.03)


def packet(conn, data):
    conn.sendall(struct.pack(">I", len(data)) + data)


def control(kind=13, x=0, y=0, extra=0, text=""):
    return b"THC1" + bytes([kind]) + struct.pack(">ffi", x, y, extra) + text.encode()


class Fixture:
    def __init__(self, desktop=False):
        self.temp = tempfile.TemporaryDirectory(prefix="termish-macos-relay-")
        self.root = Path(self.temp.name)
        self.connections = []
        encoder = shutil.which("ffmpeg")
        if not encoder:
            raise unittest.SkipTest("FFmpeg unavailable")
        while True:
            with socket.socket() as probe:
                probe.bind(("127.0.0.1", 0))
                self.port = probe.getsockname()[1]
                if self.port > 65533:
                    continue
                try:
                    with socket.socket() as video:
                        video.bind(("127.0.0.1", self.port + 2))
                except OSError:
                    continue
                break
        self.script = self.root / "relay.py"
        self.script.write_text(RENDERED)
        self.encoder = self.root / "encoder"
        self.encoder.write_text("#!/bin/sh\necho started >> '%s'\n" % (self.root / "runs") + (
            "printf '%s\\n' \"$@\" > '%s'\nexec '%s' -hide_banner -loglevel error -re -f lavfi -i testsrc2=size=320x180:rate=30 -c:v libx264 -preset ultrafast -tune zerolatency -x264-params aud=1 -f h264 -\n" % ("%s", self.root / "args", encoder)
            if desktop else "exec '%s' \"$@\"\n" % encoder
        ))
        self.encoder.chmod(0o700)
        (self.root / "token").write_text("a" * 64)
        (self.root / "quality").write_text("fps=30\nscale=native\n")
        self.config = self.root / "config.json"
        self.config.write_text(json.dumps({
            "capture_source": "desktop" if desktop else "test_pattern",
            "port": self.port, "ffmpeg": str(self.encoder), "token_file": str(self.root / "token"),
            "log_file": str(self.root / "log"), "encoder_pid_file": str(self.root / "encoder.pid"),
            "stream_config_file": str(self.root / "quality"),
        }))
        args = [sys.executable, str(self.script), "--config", str(self.config)]
        if desktop:
            # Exercise desktop encoder selection while denying input. The fake
            # encoder produces a diagnostic pattern and never captures a desktop.
            loader = "import runpy; m=runpy.run_path(%r); m['main'].__globals__['Input']=type('DeniedInput',(),{'__init__':lambda self,*a:None,'status':lambda self:1,'release':lambda self:None}); m['main']()" % str(self.script)
            args = [sys.executable, "-c", loader, "--config", str(self.config)]
        self.stderr = (self.root / "stderr").open("w")
        self.process = subprocess.Popen(args, stdout=subprocess.DEVNULL, stderr=self.stderr)
        try:
            eventually(lambda: (self.root / "log").exists() and "service started" in (self.root / "log").read_text())
        except Exception:
            self.close()
            raise

    def connect(self):
        conn = socket.create_connection(("127.0.0.1", self.port + 2), timeout=5)
        self.connections.append(conn)
        return conn

    def auth(self, status=2):
        conn = self.connect()
        packet(conn, b"THA1" + b"a" * 64)
        assert relay.recv_exact(conn, 5) == b"THS1" + bytes([status])
        return conn

    def frames(self, conn, count=5):
        frames = []
        while len(frames) < count:
            packet(conn, control())
            data = relay.read_packet(conn, 5, 4 * 1024 * 1024)
            if data.startswith(b"THV2"):
                frames.append(data)
        return frames

    def close(self):
        for conn in self.connections:
            conn.close()
        if hasattr(self, "process"):
            self.process.terminate()
            try:
                self.process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.stderr.close()
        self.temp.cleanup()


class RelayTest(unittest.TestCase):
    def fixture(self, **kwargs):
        fixture = Fixture(**kwargs)
        self.addCleanup(fixture.close)
        return fixture

    def test_untrusted_and_oversized_requests_never_start_capture(self):
        f = self.fixture()
        for body in (struct.pack(">I", 2**32 - 1), struct.pack(">I", 68) + b"THA1" + b"b" * 64):
            conn = f.connect()
            conn.sendall(body)
            self.assertEqual(b"", conn.recv(1))
        self.assertFalse((f.root / "runs").exists())
        self.assertFalse((f.root / "encoder.pid").exists())

    def test_video_frames_preserve_parameter_sets_and_monotonic_sequence(self):
        f = self.fixture()
        frames = f.frames(f.auth(), 10)
        sequences = [struct.unpack(">Q", frame[4:12])[0] for frame in frames]
        self.assertEqual(list(range(10)), sequences)
        self.assertEqual(1, frames[0][20])
        nal_types = {data[m.end()] & 31 for frame in frames for data in (frame[21:],) for m in relay.START_CODE.finditer(data) if m.end() < len(data)}
        self.assertTrue({5, 7, 8, 9}.issubset(nal_types), nal_types)
        self.assertEqual(0o600, (f.root / "encoder.pid").stat().st_mode & 0o777)

    def test_denied_input_does_not_block_legacy_desktop_encoder(self):
        f = self.fixture(desktop=True)
        frames = f.frames(f.auth(status=1))
        self.assertTrue(frames)
        args = (f.root / "args").read_text().splitlines()
        self.assertIn("avfoundation", args)
        self.assertIn("h264_videotoolbox", args)
        self.assertNotIn("CGPreflightScreenCaptureAccess", RENDERED)

    def test_busy_client_and_bad_auth_cannot_replace_a_healthy_owner(self):
        f = self.fixture()
        owner = f.auth()
        f.frames(owner)
        self.auth_rejected = f.connect()
        packet(self.auth_rejected, b"THA1" + b"b" * 64)
        self.assertEqual(b"", self.auth_rejected.recv(1))
        second = f.auth(status=3)
        self.assertEqual(b"", second.recv(1))
        self.assertTrue(f.frames(owner))
        self.assertEqual(1, len((f.root / "runs").read_text().splitlines()))

    def test_disconnect_reaps_encoder_and_allows_reconnection(self):
        f = self.fixture()
        for _ in range(2):
            conn = f.auth()
            f.frames(conn)
            pid = int((f.root / "encoder.pid").read_text())
            conn.shutdown(socket.SHUT_RDWR)
            conn.close()
            eventually(lambda: not (f.root / "encoder.pid").exists())
            with self.assertRaises(ProcessLookupError):
                os.kill(pid, 0)

    def test_heartbeat_expiry_stops_capture_and_reclaims_session(self):
        f = self.fixture()
        conn = f.auth()
        f.frames(conn)
        eventually(lambda: not (f.root / "encoder.pid").exists(), timeout=8)
        self.assertTrue(f.frames(f.auth()))

    def test_feedback_restarts_encoder_without_resetting_sequence(self):
        f = self.fixture()
        conn = f.auth()
        first = f.frames(conn, 100)
        packet(conn, b"THF1\x01\x01\x00\x00" + struct.pack(">IIIIII", 30, 30, 1000, 0, 0, 0) + b"\x00" * 8)
        frames = f.frames(conn, 40)
        eventually(lambda: len((f.root / "runs").read_text().splitlines()) == 2)
        seq = [struct.unpack(">Q", frame[4:12])[0] for frame in first + frames]
        self.assertEqual(list(range(len(seq))), seq)
        self.assertGreater(sum(frame[20] for frame in first + frames), 1)

    def test_abrupt_service_exit_reclaims_only_its_recorded_encoder(self):
        f = self.fixture()
        conn = f.auth()
        f.frames(conn)
        old_pid = int((f.root / "encoder.pid").read_text())
        f.process.kill()
        f.process.wait()
        conn.close()
        f.process = subprocess.Popen([sys.executable, str(f.script), "--config", str(f.config)], stdout=subprocess.DEVNULL, stderr=f.stderr)
        eventually(lambda: not (f.root / "encoder.pid").exists())
        eventually(lambda: not relay.Config.process_identity(old_pid))
        self.assertEqual("", relay.Config.process_identity(old_pid))
        self.assertTrue(f.frames(f.auth()))

    def test_pid_reuse_metadata_does_not_kill_an_unrelated_process(self):
        f = self.fixture()
        unrelated = subprocess.Popen(["sleep", "30"], start_new_session=True)
        try:
            config = relay.Config(f.config)
            config.record_encoder(unrelated.pid)
            metadata = json.loads(config.identity.read_text())
            metadata["identity"] = "stale prior process identity"
            config.identity.write_text(json.dumps(metadata))
            config.recover_encoder()
            self.assertIsNone(unrelated.poll())
        finally:
            unrelated.terminate()
            unrelated.wait()

    def test_invalid_control_is_rejected_before_input(self):
        for data in (control(x=float("nan")), control(kind=14), control(text="").replace(b"THC1", b"NOPE")):
            with self.assertRaises(ValueError):
                relay.parse_control(data)
        self.assertEqual("😀", relay.parse_control(control(kind=4, text="😀"))[-1])

    def test_heartbeat_never_checks_or_requests_input_permission(self):
        inp = relay.Input(True)
        inp.status = lambda: self.fail("heartbeat probed input permission")
        inp.inject(control())
        self.assertFalse(inp.prompted)

    @unittest.skipUnless(sys.platform == "darwin", "macOS native symbols")
    def test_native_input_symbols_are_available_without_requesting_access(self):
        inp = relay.Input()
        self.assertIn(inp.status(), (0, 1))
        self.assertFalse(inp.prompted)


if __name__ == "__main__":
    unittest.main()
