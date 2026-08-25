from __future__ import annotations

import argparse
import asyncio
import contextlib
import json
import os
import pathlib
import signal
import socket
import subprocess
import sys
import threading
import time
from typing import Any, Dict, Optional, Set

from __init__ import PROTOCOL_VERSION, VERSION
from adapters import bridge_home
from protocol import ProtocolError, decode_line, encode_line, error_response, response
from store import SessionStore, redact_sensitive_payload, redact_sensitive_text, sensitive_values

try:
    import fcntl
except ImportError:
    fcntl = None


def runtime_dir() -> pathlib.Path:
    path = bridge_home() / "runtime"
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    path.chmod(0o700)
    return path


def socket_path() -> pathlib.Path:
    return runtime_dir() / "agent.sock"


@contextlib.contextmanager
def daemon_lock():
    lock_path = runtime_dir() / "agent.lock"
    with lock_path.open("a+b") as lock:
        if fcntl:
            fcntl.flock(lock.fileno(), fcntl.LOCK_EX)
        try:
            yield
        finally:
            if fcntl:
                fcntl.flock(lock.fileno(), fcntl.LOCK_UN)


def daemon_pid() -> Optional[int]:
    try:
        return int((runtime_dir() / "agent.pid").read_text(encoding="utf-8").strip())
    except (OSError, ValueError):
        return None


def process_alive(pid: int) -> bool:
    try:
        os.kill(pid, 0)
        return True
    except OSError:
        return False


def signal_daemon(pid: int, signum: int) -> None:
    if os.name == "posix":
        os.killpg(os.getpgid(pid), signum)
    else:
        os.kill(pid, signum)


class BridgeServer:
    def __init__(self) -> None:
        self.clients: Set[asyncio.StreamWriter] = set()
        self.store = SessionStore(self.broadcast)

    async def broadcast(self, message: Dict[str, Any]) -> None:
        raw = encode_line(redact_sensitive_payload(message))
        stale = []
        for writer in list(self.clients):
            try:
                writer.write(raw)
                await writer.drain()
            except (BrokenPipeError, ConnectionError):
                stale.append(writer)
        for writer in stale:
            self.clients.discard(writer)

    async def handle(self, reader: asyncio.StreamReader, writer: asyncio.StreamWriter) -> None:
        self.clients.add(writer)
        try:
            while True:
                raw = await reader.readline()
                if not raw:
                    break
                request_id: Any = None
                params: Dict[str, Any] = {}
                try:
                    request = decode_line(raw)
                    request_id = request.get("id")
                    method = str(request.get("method", ""))
                    params = request.get("params") or {}
                    if not isinstance(params, dict):
                        raise ProtocolError("params must be an object")
                    if method == "system.hello":
                        result = {
                            "version": VERSION,
                            "protocolVersion": PROTOCOL_VERSION,
                            "pythonVersion": list(sys.version_info[:3]),
                        }
                    else:
                        result = await self.store.dispatch(method, params)
                    writer.write(encode_line(response(request_id, result)))
                except ProtocolError as exc:
                    writer.write(encode_line(error_response(request_id, str(exc), "invalid_request")))
                except Exception as exc:
                    message = redact_sensitive_text(str(exc), sensitive_values(params))
                    writer.write(encode_line(error_response(request_id, message)))
                await writer.drain()
        except (BrokenPipeError, ConnectionError):
            pass
        finally:
            self.clients.discard(writer)
            writer.close()
            try:
                await writer.wait_closed()
            except (BrokenPipeError, ConnectionError):
                pass

    async def run(self) -> None:
        path = socket_path()
        if path.exists():
            path.unlink()
        server = await asyncio.start_unix_server(self.handle, path=str(path), limit=1024 * 1024)
        os.chmod(path, 0o600)
        (runtime_dir() / "agent.pid").write_text(str(os.getpid()), encoding="utf-8")
        (runtime_dir() / "agent.version").write_text(VERSION, encoding="utf-8")
        loop = asyncio.get_running_loop()
        stop = asyncio.Event()
        for signum in (signal.SIGINT, signal.SIGTERM):
            try:
                loop.add_signal_handler(signum, stop.set)
            except NotImplementedError:
                pass
        try:
            async with server:
                await stop.wait()
        finally:
            await self.store.shutdown()
            if daemon_pid() == os.getpid():
                path.unlink(missing_ok=True)
                (runtime_dir() / "agent.pid").unlink(missing_ok=True)
                (runtime_dir() / "agent.version").unlink(missing_ok=True)


def daemon_ready(timeout: float = 0.2) -> bool:
    path = socket_path()
    if not path.exists():
        return False
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.settimeout(timeout)
    try:
        client.connect(str(path))
        return True
    except OSError:
        return False
    finally:
        client.close()


def _ensure_daemon_unlocked() -> None:
    if daemon_ready():
        return
    path = socket_path()
    path.unlink(missing_ok=True)
    log_path = runtime_dir() / "agent.log"
    log = open(log_path, "ab", buffering=0)
    subprocess.Popen(
        [sys.executable, str(pathlib.Path(sys.argv[0]).resolve()), "serve"],
        stdin=subprocess.DEVNULL,
        stdout=log,
        stderr=subprocess.STDOUT,
        start_new_session=True,
        close_fds=True,
    )
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline:
        if daemon_ready():
            return
        time.sleep(0.05)
    raise RuntimeError(f"Agent Bridge did not start; see {log_path}")


def ensure_daemon() -> None:
    with daemon_lock():
        _ensure_daemon_unlocked()


def restart_daemon() -> None:
    with daemon_lock():
        pid = daemon_pid()
        if pid and process_alive(pid):
            try:
                signal_daemon(pid, signal.SIGTERM)
            except OSError:
                pass
            deadline = time.monotonic() + 5
            while time.monotonic() < deadline and process_alive(pid):
                time.sleep(0.05)
            if process_alive(pid):
                try:
                    signal_daemon(pid, signal.SIGKILL)
                except OSError:
                    pass
        socket_path().unlink(missing_ok=True)
        _ensure_daemon_unlocked()


def relay() -> None:
    ensure_daemon()
    client = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    client.connect(str(socket_path()))
    done = threading.Event()

    def upload() -> None:
        try:
            while not done.is_set():
                # BufferedReader.read(size) may wait for the entire size on an
                # SSH pipe. os.read returns the currently available NDJSON
                # request immediately while keeping stdin open for the session.
                data = os.read(sys.stdin.fileno(), 65536)
                if not data:
                    break
                client.sendall(data)
        except (BrokenPipeError, OSError):
            pass
        finally:
            done.set()
            try:
                client.shutdown(socket.SHUT_WR)
            except OSError:
                pass

    thread = threading.Thread(target=upload, name="termish-agent-input", daemon=True)
    thread.start()
    try:
        while not done.is_set():
            data = client.recv(65536)
            if not data:
                break
            sys.stdout.buffer.write(data)
            sys.stdout.buffer.flush()
    finally:
        done.set()
        client.close()


def status() -> int:
    try:
        daemon_version = (runtime_dir() / "agent.version").read_text(encoding="utf-8").strip()
    except OSError:
        daemon_version = None
    print(
        json.dumps(
            {
                "version": VERSION,
                "protocolVersion": PROTOCOL_VERSION,
                "running": daemon_ready(),
                "daemonVersion": daemon_version,
                "pythonVersion": list(sys.version_info[:3]),
            },
            separators=(",", ":"),
        )
    )
    return 0


def main(argv: Optional[list] = None) -> None:
    os.umask(0o077)
    parser = argparse.ArgumentParser(prog="termish-agent")
    subparsers = parser.add_subparsers(dest="command", required=True)
    subparsers.add_parser("serve")
    subparsers.add_parser("connect")
    subparsers.add_parser("ensure-running")
    subparsers.add_parser("restart")
    subparsers.add_parser("status")
    args = parser.parse_args(argv)
    if args.command == "serve":
        asyncio.run(BridgeServer().run())
    elif args.command == "connect":
        relay()
    elif args.command == "ensure-running":
        ensure_daemon()
        raise SystemExit(status())
    elif args.command == "restart":
        restart_daemon()
        raise SystemExit(status())
    elif args.command == "status":
        raise SystemExit(status())
