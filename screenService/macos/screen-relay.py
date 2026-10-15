#!/usr/bin/python3
# SPDX-License-Identifier: MIT
"""macOS GUI LaunchAgent relay: stable system Python -> FFmpeg, as before 1.8.0.

Screen capture is performed by FFmpeg, without a second permission check on a
changing Helper executable. The OS continues to enforce capture/input access.
Only authenticated, loopback TCP clients can start capture or inject input.
"""

import argparse
import ctypes as c
import hmac
import json
import math
import os
from pathlib import Path
import re
import select
import signal
import socket
import struct
import subprocess
import sys
import threading
import time

VERSION = int("@RELAY_VERSION@")
MAX_CONTROL = 64 * 1024
MAX_FRAME = 4 * 1024 * 1024 - 21
LEASE = 6
START_CODE = re.compile(b"\x00\x00(?:\x00)?\x01")


class Point(c.Structure):
    _fields_ = [("x", c.c_double), ("y", c.c_double)]


class Size(c.Structure):
    _fields_ = [("width", c.c_double), ("height", c.c_double)]


class Rect(c.Structure):
    _fields_ = [("origin", Point), ("size", Size)]


class ProcessInfo(c.Structure):
    # Public Darwin proc_bsdinfo ABI, including the microsecond birth time.
    _fields_ = [(name, c.c_uint32) for name in (
        "flags", "status", "xstatus", "pid", "ppid", "uid", "gid", "ruid", "rgid", "svuid", "svgid", "reserved",
    )] + [("comm", c.c_char * 16), ("name", c.c_char * 32)] + [
        (name, c.c_uint32) for name in ("nfiles", "pgid", "jobc", "tdev", "tpgid")
    ] + [("nice", c.c_int32), ("seconds", c.c_uint64), ("microseconds", c.c_uint64)]


class Power:
    """Power assertions exist only for the authenticated owner's capture lease."""

    def __init__(self, diagnostic=False):
        self.io = None
        self.assertions = []
        self.activity = c.c_uint32()
        if diagnostic or sys.platform != "darwin":
            return
        self.io = c.CDLL("/System/Library/Frameworks/IOKit.framework/IOKit")
        self.cf = c.CDLL("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        self.cf.CFStringCreateWithCString.argtypes = [c.c_void_p, c.c_char_p, c.c_uint32]
        self.cf.CFStringCreateWithCString.restype = c.c_void_p
        self.cf.CFRelease.argtypes = [c.c_void_p]
        self.io.IOPMAssertionCreateWithName.argtypes = [c.c_void_p, c.c_uint32, c.c_void_p, c.POINTER(c.c_uint32)]
        self.io.IOPMAssertionDeclareUserActivity.argtypes = [c.c_void_p, c.c_uint32, c.POINTER(c.c_uint32)]
        self.io.IOPMAssertionRelease.argtypes = [c.c_uint32]

    def start(self):
        if self.io is None:
            return
        name = self.cf.CFStringCreateWithCString(None, b"Termish authenticated remote screen", 0x08000100)
        try:
            for kind in (b"PreventUserIdleDisplaySleep", b"PreventUserIdleSystemSleep"):
                text = self.cf.CFStringCreateWithCString(None, kind, 0x08000100)
                try:
                    assertion = c.c_uint32()
                    if self.io.IOPMAssertionCreateWithName(text, 255, name, c.byref(assertion)) == 0:
                        self.assertions.append(assertion.value)
                finally:
                    self.cf.CFRelease(text)
            self.wake(name)
        finally:
            self.cf.CFRelease(name)

    def wake(self, name=None):
        if self.io is None:
            return
        owned = name is None
        if owned:
            name = self.cf.CFStringCreateWithCString(None, b"Termish remote display recovery", 0x08000100)
        try:
            previous = self.activity.value
            if self.io.IOPMAssertionDeclareUserActivity(name, 0, c.byref(self.activity)) != 0:
                self.activity.value = previous
        finally:
            if owned:
                self.cf.CFRelease(name)

    def close(self):
        for assertion in self.assertions:
            self.io.IOPMAssertionRelease(assertion)
        self.assertions.clear()
        if self.activity.value:
            self.io.IOPMAssertionRelease(self.activity.value)
            self.activity.value = 0


class Input:
    """The same CGEvent input API as the native service, under Python's identity."""

    def __init__(self, diagnostic=False):
        self.api = None
        self.prompted = False
        self.left = self.right = False
        self.origin = None
        self.last = Point()
        self.lock = threading.RLock()
        if diagnostic or sys.platform != "darwin":
            return
        self.api = c.CDLL("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices")
        self.cf = c.CDLL("/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation")
        signatures = {
            "AXIsProcessTrusted": (c.c_bool, []),
            "CGMainDisplayID": (c.c_uint32, []),
            "CGDisplayBounds": (Rect, [c.c_uint32]),
            "CGEventSourceCreate": (c.c_void_p, [c.c_int32]),
            "CGEventCreate": (c.c_void_p, [c.c_void_p]),
            "CGEventGetLocation": (Point, [c.c_void_p]),
            "CGEventCreateMouseEvent": (c.c_void_p, [c.c_void_p, c.c_uint32, Point, c.c_uint32]),
            "CGEventCreateKeyboardEvent": (c.c_void_p, [c.c_void_p, c.c_uint16, c.c_bool]),
            "CGEventCreateScrollWheelEvent": (c.c_void_p, [c.c_void_p, c.c_uint32, c.c_uint32]),
            "CGEventSetLocation": (None, [c.c_void_p, Point]),
            "CGEventSetFlags": (None, [c.c_void_p, c.c_uint64]),
            "CGEventKeyboardSetUnicodeString": (None, [c.c_void_p, c.c_size_t, c.POINTER(c.c_uint16)]),
            "CGEventPost": (None, [c.c_uint32, c.c_void_p]),
        }
        for name, (result, args) in signatures.items():
            function = getattr(self.api, name)
            function.restype, function.argtypes = result, args
        self.cf.CFRelease.argtypes = [c.c_void_p]
        self.cf.CFRelease.restype = None
        for name in ("CGPreflightPostEventAccess", "CGRequestPostEventAccess"):
            if hasattr(self.api, name):
                getattr(self.api, name).restype = c.c_bool
                getattr(self.api, name).argtypes = []

    def status(self):
        if self.api is None:
            return 2
        return 0 if getattr(self.api, "CGPreflightPostEventAccess", self.api.AXIsProcessTrusted)() else 1

    def post(self, event):
        if event:
            try:
                self.api.CGEventPost(0, event)
            finally:
                self.cf.CFRelease(event)

    def mouse(self, source, kind, point, button=0):
        self.post(self.api.CGEventCreateMouseEvent(source, kind, point, button))

    def key(self, source, code, down, flags=0):
        event = self.api.CGEventCreateKeyboardEvent(source, code, down)
        if event:
            self.api.CGEventSetFlags(event, flags)
        self.post(event)

    def inject(self, data):
        kind, x, y, extra, text = parse_control(data)
        if kind == 13:
            return
        with self.lock:
            if self.status() != 0:
                if self.api and not self.prompted:
                    self.prompted = True
                    request = getattr(self.api, "CGRequestPostEventAccess", None)
                    if request:
                        request()
                return
            source = self.api.CGEventSourceCreate(-1)
            if not source:
                return
            try:
                if kind == 4:
                    for character in text:
                        utf16 = character.encode("utf-16-le")
                        chars = (c.c_uint16 * (len(utf16) // 2)).from_buffer_copy(utf16)
                        for down in (True, False):
                            event = self.api.CGEventCreateKeyboardEvent(source, 0, down)
                            if event:
                                self.api.CGEventKeyboardSetUnicodeString(event, len(chars), chars)
                            self.post(event)
                    return
                if kind == 5:
                    if not 0 <= extra <= 65535:
                        return
                    modifiers = [(55, 1, 1 << 20), (56, 2, 1 << 17), (59, 4, 1 << 18), (58, 8, 1 << 19)]
                    selected = [(code, flag) for code, bit, flag in modifiers if int(x) & bit]
                    flags = sum(flag for _, flag in selected)
                    for code, _ in selected:
                        self.key(source, code, True, flags)
                    self.key(source, extra, True, flags)
                    self.key(source, extra, False, flags)
                    for code, _ in reversed(selected):
                        self.key(source, code, False)
                    return
                bounds = self.api.CGDisplayBounds(self.api.CGMainDisplayID())
                point = Point(bounds.origin.x + max(0, min(1, x)) * bounds.size.width,
                              bounds.origin.y + max(0, min(1, y)) * bounds.size.height)
                self.last = point
                if kind in (0, 1, 2, 6, 7, 11):
                    if kind == 11 and not self.left:
                        return
                    if kind in (1, 2):
                        self.left = kind == 1
                    if kind in (6, 7):
                        self.right = kind == 6
                    self.mouse(source, {0: 5, 1: 1, 2: 2, 6: 3, 7: 4, 11: 6}[kind], point, int(kind in (6, 7)))
                elif kind == 3:
                    event = self.api.CGEventCreateScrollWheelEvent(source, 1, 1, c.c_int32(max(-1000, min(1000, extra))))
                    if event:
                        self.api.CGEventSetLocation(event, point)
                    self.post(event)
                elif kind in (8, 9, 10):
                    current = self.api.CGEventCreate(None)
                    origin = self.api.CGEventGetLocation(current) if current else None
                    if current:
                        self.cf.CFRelease(current)
                    if kind == 10:
                        self.origin = origin if self.origin is None else self.origin
                        self.left = True
                        self.mouse(source, 1, point)
                    else:
                        button = int(kind == 9)
                        self.mouse(source, 1 if kind == 8 else 3, point, button)
                        self.mouse(source, 2 if kind == 8 else 4, point, button)
                        if origin is not None:
                            self.mouse(source, 5, origin)
                elif kind == 12:
                    self.release()
            finally:
                self.cf.CFRelease(source)

    def release(self):
        with self.lock:
            if self.api and (self.left or self.right or self.origin is not None):
                source = self.api.CGEventSourceCreate(-1)
                if source:
                    try:
                        if self.left:
                            self.mouse(source, 2, self.last)
                        if self.right:
                            self.mouse(source, 4, self.last, 1)
                        if self.origin is not None:
                            self.mouse(source, 5, self.origin)
                    finally:
                        self.cf.CFRelease(source)
            self.left = self.right = False
            self.origin = None


def parse_control(data):
    if len(data) < 17 or data[:4] != b"THC1" or data[4] > 13:
        raise ValueError("invalid control")
    x, y, extra = struct.unpack(">ffi", data[5:17])
    if not math.isfinite(x) or not math.isfinite(y):
        raise ValueError("invalid coordinates")
    return data[4], x, y, extra, data[17:].decode("utf-8")


def recv_exact(conn, count):
    result = bytearray()
    while len(result) < count:
        chunk = conn.recv(count - len(result))
        if not chunk:
            raise EOFError("client closed")
        result.extend(chunk)
    return bytes(result)


def read_packet(conn, minimum, maximum):
    size = struct.unpack(">I", recv_exact(conn, 4))[0]
    if not minimum <= size <= maximum:
        raise ValueError("invalid packet size")
    return recv_exact(conn, size)


class Config:
    def __init__(self, path):
        settings = json.loads(Path(path).read_text())
        self.port = settings.get("port", 17321)
        if type(self.port) is not int or not 1 <= self.port <= 65533:
            raise ValueError("invalid port")
        self.ffmpeg = settings.get("ffmpeg", "ffmpeg")
        self.diagnostic = settings.get("capture_source", "desktop") == "test_pattern"
        if settings.get("capture_source", "desktop") not in ("desktop", "test_pattern"):
            raise ValueError("invalid capture source")
        self.token = Path(os.path.expanduser(settings.get("token_file", "~/.termish-screen.token"))).read_bytes().strip()
        if len(self.token) != 64 or any(ch not in b"0123456789abcdefABCDEF" for ch in self.token):
            raise ValueError("invalid token")
        self.log = Path(os.path.expanduser(settings.get("log_file", "~/Library/Logs/termish-screen.err")))
        self.pid = Path(os.path.expanduser(settings.get("encoder_pid_file", "~/.termish-screen-ffmpeg.pid")))
        self.identity = self.pid.with_name(self.pid.name + ".identity")
        self.quality = Path(os.path.expanduser(settings.get("stream_config_file", "~/.termish-screen.conf")))
        self.helper = Path(path).parent / "Termish Helper.app/Contents/MacOS/Termish Helper"
        self.log.parent.mkdir(parents=True, exist_ok=True)
        self.pid.parent.mkdir(parents=True, exist_ok=True)

    @staticmethod
    def process_identity(pid):
        if sys.platform == "darwin":
            api = c.CDLL("/usr/lib/libproc.dylib")
            api.proc_pidinfo.argtypes = [c.c_int, c.c_int, c.c_uint64, c.c_void_p, c.c_int]
            info = ProcessInfo()
            count = api.proc_pidinfo(pid, 3, 0, c.byref(info), c.sizeof(info))
            if count != c.sizeof(info) or info.status == 5:
                return ""
            return "darwin:%d:%d:%d" % (pid, info.seconds, info.microseconds)
        result = subprocess.run(["ps", "-p", str(pid), "-o", "lstart=", "-o", "stat="],
                                capture_output=True, text=True, timeout=2)
        fields = result.stdout.split()
        # The encoder may exec from a wrapper into FFmpeg. Its birth identity
        # remains stable across exec, whereas its command line does not.
        return " ".join(fields[:-1]) if result.returncode == 0 and fields and not fields[-1].startswith("Z") else ""

    def record_encoder(self, pid):
        self.pid.write_text(str(pid))
        self.identity.write_text(json.dumps({"pid": pid, "identity": self.process_identity(pid)}))
        os.chmod(self.pid, 0o600)
        os.chmod(self.identity, 0o600)

    def clear_encoder(self, pid):
        try:
            if self.pid.read_text().strip() == str(pid):
                self.pid.unlink()
                self.identity.unlink(missing_ok=True)
        except FileNotFoundError:
            pass

    def recover_encoder(self):
        # A PID alone can belong to another task after reuse. Only reclaim the
        # exact process recorded by this relay before an abrupt exit.
        try:
            pid = int(self.pid.read_text())
            owner = json.loads(self.identity.read_text())
            identity = self.process_identity(pid)
            if owner["pid"] == pid and not identity:
                self.clear_encoder(pid)
            elif owner["pid"] == pid and owner["identity"] and owner["identity"] == identity:
                os.kill(pid, signal.SIGKILL)
                self.write_log("reclaimed orphaned encoder")
                self.clear_encoder(pid)
        except (OSError, ValueError, KeyError):
            pass

    def write_log(self, message):
        with self.log.open("a") as log:
            log.write("[%s] %s\n" % (time.strftime("%H:%M:%S"), message))

    def screen_input(self):
        # AVFoundation numbers screens after cameras. Camera changes and a
        # sleeping/closed display can invalidate the former fixed index 1.
        result = subprocess.run(
            [self.ffmpeg, "-hide_banner", "-f", "avfoundation", "-list_devices", "true", "-i", ""],
            capture_output=True, text=True, errors="replace", timeout=5,
        )
        video = False
        screens = []
        for line in result.stderr.splitlines():
            if "AVFoundation video devices:" in line:
                video = True
            elif "AVFoundation audio devices:" in line:
                video = False
            elif video:
                match = re.search(r"\[(\d+)\]\s+Capture screen (\d+)\s*$", line)
                if match:
                    screens.append((int(match[2]), int(match[1])))
        if not screens:
            raise ValueError("no AVFoundation screen available; open/wake the Mac display and reconnect")
        return "%d:none" % min(screens)[1]

    def args(self, scale):
        fps, size = 30, "1280:-2"
        for line in self.quality.read_text().splitlines() if self.quality.exists() else []:
            key, _, value = line.partition("=")
            if key == "fps":
                try:
                    fps = max(1, min(120, int(value)))
                except ValueError:
                    pass
            if key == "scale" and (value == "native" or re.fullmatch(r"[0-9]+:-2", value)):
                if value == "native" or 2 <= int(value.split(":")[0]) <= 16384:
                    size = value
        base = 24 if size == "native" or size.startswith("2560") else 10 if size.startswith("1920") else 6 if size.startswith("1280") else 4
        kbps = max(1000, base * (2 if fps >= 120 else 1.5 if fps >= 60 else 1) * 1000 * scale // 100)
        bitrate = "%dk" % kbps
        if not self.diagnostic and self.helper.is_file():
            width = "0" if size == "native" else size.split(":")[0]
            return [str(self.helper), "--capture-native", str(fps), width, str(int(kbps * 1000))]
        if self.diagnostic:
            source = ["-re", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=%d" % fps]
            encoder = ["-c:v", "libx264", "-preset", "ultrafast", "-tune", "zerolatency", "-x264-params", "aud=1"]
        else:
            # Restore the former AVFoundation/VideoToolbox capture command.
            source = ["-f", "avfoundation", "-capture_cursor", "1", "-pixel_format", "uyvy422", "-framerate", str(fps), "-i", self.screen_input()]
            encoder = ["-c:v", "h264_videotoolbox", "-realtime", "1"]
        vf = "fps=%d" % fps
        if size != "native":
            vf += ",scale=%s:flags=lanczos" % size
        framing = [] if self.diagnostic else ["-bsf:v", "dump_extra=freq=keyframe,h264_metadata=aud=insert"]
        return [self.ffmpeg] + source + ["-hide_banner", "-loglevel", "error", "-vf", vf] + encoder + [
            "-b:v", bitrate, "-maxrate", bitrate, "-bufsize", "%dk" % max(500, kbps // 2), "-g", str(max(15, fps // 2)),
            "-pix_fmt", "yuv420p",
        ] + framing + ["-f", "h264", "-"]


class Session:
    def __init__(self, server, conn):
        self.server, self.config, self.conn = server, server.config, conn
        self.stopped = threading.Event()
        self.write_lock = threading.Lock()
        self.encoder_lock = threading.RLock()
        self.encoder = None
        self.heartbeat = time.monotonic()
        self.bitrate_scale = 100
        self.bad = self.healthy = 0
        self.restart = False
        self.last_restart = time.monotonic()
        self.sequence = 0
        self.power = Power(self.config.diagnostic)

    def send(self, data):
        with self.write_lock:
            self.conn.sendall(struct.pack(">I", len(data)) + data)

    def reap_encoder(self):
        with self.encoder_lock:
            encoder = self.encoder
            self.encoder = None
            if encoder is None:
                return
            if encoder.poll() is None:
                encoder.kill()
            encoder.wait()
            encoder.stdout.close()
            self.config.clear_encoder(encoder.pid)

    def stop(self, reason):
        if self.stopped.is_set():
            return
        self.stopped.set()
        try:
            self.conn.shutdown(socket.SHUT_RDWR)
        except OSError:
            pass
        self.conn.close()
        self.reap_encoder()
        self.server.input.release()
        self.config.write_log("video stream closed: " + reason)

    def feedback(self, data):
        if len(data) != 40 or data[:5] != b"THF1\x01":
            raise ValueError("invalid feedback")
        queue = struct.unpack(">H", data[6:8])[0]
        received, rendered, _, dropped, busy, jitter = struct.unpack(">IIIIII", data[8:32])
        pressured = dropped >= 120 or busy >= 2 or jitter >= 80 or queue >= 3 or (received >= 20 and rendered * 100 < received * 70)
        self.bad = self.bad + 1 if pressured else 0
        self.healthy = 0 if pressured else self.healthy + 1
        scale = self.bitrate_scale
        ladder = [100, 75, 55, 40]
        at = ladder.index(scale)
        if self.bad >= 2:
            scale = ladder[min(3, at + 1)]
        elif self.healthy >= 5:
            scale = ladder[max(0, at - 1)]
        if (scale != self.bitrate_scale or data[5] & 1) and time.monotonic() - self.last_restart >= 3:
            with self.encoder_lock:
                if self.encoder is not None and not self.stopped.is_set():
                    self.bitrate_scale = scale
                    self.last_restart = time.monotonic()
                    self.restart = True
                    self.encoder.kill()

    def control(self):
        try:
            while not self.stopped.is_set():
                data = read_packet(self.conn, 17, MAX_CONTROL)
                if data.startswith(b"THF1"):
                    self.feedback(data)
                else:
                    parse_control(data)
                    if data[4] != 13:
                        self.server.input.inject(data)
                self.heartbeat = time.monotonic()
        except (OSError, EOFError, ValueError) as error:
            self.stop("control ended: " + str(error))

    def frame(self, data):
        if len(data) > MAX_FRAME:
            raise ValueError("oversized video frame")
        keyframe = any(match.end() < len(data) and data[match.end()] & 31 == 5 for match in START_CODE.finditer(data))
        envelope = b"THV2" + struct.pack(">QQB", self.sequence, time.monotonic_ns() // 1000, int(keyframe))
        self.send(envelope + data)
        self.sequence += 1

    def pump(self):
        status = self.server.input.status()
        checked = time.monotonic()
        try:
            self.power.start()
            while not self.stopped.is_set():
                with self.encoder_lock:
                    if self.stopped.is_set():
                        break
                    self.restart = False
                    with self.config.log.open("ab") as log:
                        self.encoder = subprocess.Popen(self.config.args(self.bitrate_scale),
                                                        stdin=subprocess.DEVNULL, stdout=subprocess.PIPE, stderr=log,
                                                        close_fds=True)
                    encoder = self.encoder
                    self.config.record_encoder(encoder.pid)
                buffer = b""
                last_data = time.monotonic()
                while not self.stopped.is_set():
                    now = time.monotonic()
                    if now - self.heartbeat > LEASE:
                        raise TimeoutError("client heartbeat expired")
                    if now - checked >= 1:
                        checked = now
                        current = self.server.input.status()
                        if current != status:
                            status = current
                            self.send(b"THS1" + bytes([status]))
                    if not select.select([encoder.stdout], [], [], 0.25)[0]:
                        if now - last_data > 20:
                            self.config.write_log("capture output stalled; restarting child")
                            self.restart = True
                            encoder.kill()
                            break
                        continue
                    data = os.read(encoder.stdout.fileno(), 262144)
                    if not data:
                        break
                    last_data = now
                    buffer += data
                    # Preserve SPS/PPS preceding the first AUD, including after restart.
                    starts = [match.start() for match in START_CODE.finditer(buffer)
                              if match.end() < len(buffer) and buffer[match.end()] & 31 == 9]
                    while len(starts) >= 2:
                        cut = starts[1]
                        self.frame(buffer[:cut])
                        buffer = buffer[cut:]
                        starts = [at - cut for at in starts[1:]]
                    if len(buffer) > MAX_FRAME:
                        raise ValueError("oversized encoder output")
                try:
                    code = encoder.wait(timeout=1)
                except subprocess.TimeoutExpired:
                    code = None
                with self.encoder_lock:
                    restart = self.restart
                    self.reap_encoder()
                if code == 77:
                    self.send(b"THS1\x04")
                    break
                if code == 75:
                    self.power.wake()
                if not restart:
                    if self.config.diagnostic:
                        break
                    # Display sleep/reconfiguration can end the capture child.
                    # Preserve the authenticated socket and its THV2 sequence.
                    if self.stopped.wait(1):
                        break
                self.config.write_log("encoder restarted in-place scale=%d" % self.bitrate_scale)
        except (OSError, ValueError, subprocess.SubprocessError) as error:
            self.config.write_log("encoder ended: " + str(error))
        finally:
            self.stop("encoder ended")
            self.power.close()


class Server:
    def __init__(self, config):
        self.config = config
        self.input = Input(config.diagnostic)
        self.active = None
        self.shutdown = threading.Event()
        self.threads = []

    def stop(self, *_):
        self.shutdown.set()
        if self.active:
            self.active.stop("service stopped")

    def run(self):
        with socket.socket() as probe, socket.socket() as video:
            for listener, port in ((probe, self.config.port), (video, self.config.port + 2)):
                listener.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
                listener.bind(("127.0.0.1", port))
                listener.listen(4)
            self.config.write_log("service started version=%d backend=python-macos" % VERSION)
            self.config.recover_encoder()
            try:
                while not self.shutdown.is_set():
                    ready, _, _ = select.select([probe, video], [], [], 0.25)
                    if probe in ready:
                        conn, _ = probe.accept()
                        conn.close()
                    if video not in ready:
                        continue
                    conn, _ = video.accept()
                    try:
                        conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                        conn.settimeout(5)
                        auth = read_packet(conn, 68, 68)
                        if auth[:4] != b"THA1" or not hmac.compare_digest(auth[4:], self.config.token):
                            raise ValueError("authentication rejected")
                        if self.active and not self.active.stopped.is_set():
                            if time.monotonic() - self.active.heartbeat <= LEASE:
                                conn.sendall(b"THS1\x03")
                                conn.close()
                                continue
                            self.active.stop("stale lease replaced")
                        # Join the old video/input workers before giving a new owner access.
                        for worker in self.threads:
                            worker.join(timeout=3)
                            if worker.is_alive():
                                raise RuntimeError("previous session did not stop")
                        conn.sendall(b"THS1" + bytes([self.input.status()]))
                        conn.settimeout(LEASE)
                        self.active = Session(self, conn)
                        self.threads = [threading.Thread(target=target, daemon=True)
                                        for target in (self.active.control, self.active.pump)]
                        for worker in self.threads:
                            worker.start()
                    except (OSError, EOFError, ValueError, RuntimeError) as error:
                        conn.close()
                        self.config.write_log("connection rejected: " + str(error))
            finally:
                self.stop()
                for worker in self.threads:
                    worker.join(timeout=3)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument("--version", action="store_true")
    parser.add_argument("--check-config", action="store_true")
    parser.add_argument("--config", default=os.path.expanduser("~/Library/Application Support/termish/screen-service.json"))
    args = parser.parse_args()
    if args.version:
        print(VERSION)
        return
    config = Config(args.config)
    if args.check_config:
        return
    server = Server(config)
    signal.signal(signal.SIGTERM, server.stop)
    signal.signal(signal.SIGINT, server.stop)
    server.run()


if __name__ == "__main__":
    main()
