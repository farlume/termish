from __future__ import annotations

import json
from typing import Any, Dict


MAX_LINE_BYTES = 1024 * 1024


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
    return (json.dumps(value, ensure_ascii=False, separators=(",", ":")) + "\n").encode("utf-8")


def response(request_id: Any, result: Any) -> Dict[str, Any]:
    return {"id": request_id, "result": result}


def error_response(request_id: Any, message: str, code: str = "request_failed") -> Dict[str, Any]:
    return {"id": request_id, "error": {"code": code, "message": message}}
