from __future__ import annotations

import json
from typing import Any, Dict


# Requests are normally tiny, but a paged history response can contain large tool
# results. Keep one explicit shared ceiling so a malformed peer cannot grow a
# StreamReader indefinitely while legitimate history pages still fit.
MAX_LINE_BYTES = 8 * 1024 * 1024


class ProtocolError(Exception):
    pass


def decode_line(raw: bytes) -> Dict[str, Any]:
    if len(raw) > MAX_LINE_BYTES:
        raise ProtocolError("request too large")
    try:
        value = json.loads(raw.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ProtocolError("invalid JSON") from exc
    if not isinstance(value, dict):
        raise ProtocolError("request must be an object")
    return value


def encode_line(value: Dict[str, Any]) -> bytes:
    encoded = (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")
    if len(encoded) > MAX_LINE_BYTES:
        raise ProtocolError("response too large")
    return encoded


def response(request_id: Any, result: Any) -> Dict[str, Any]:
    return {"id": request_id, "result": result}


def error_response(request_id: Any, message: str, code: str = "request_failed") -> Dict[str, Any]:
    return {"id": request_id, "error": {"code": code, "message": message}}
