from __future__ import annotations

import pathlib
import sys
import unittest


SOURCE = pathlib.Path(__file__).resolve().parents[1] / "termish_agent"
sys.path.insert(0, str(SOURCE))

from protocol import MAX_LINE_BYTES, ProtocolError, decode_line, encode_line


class ProtocolTest(unittest.TestCase):
    def test_round_trip_unicode(self) -> None:
        value = {"id": 1, "method": "prompt.send", "params": {"message": "检查项目"}}
        self.assertEqual(value, decode_line(encode_line(value)))

    def test_requires_object(self) -> None:
        with self.assertRaises(ProtocolError):
            decode_line(b"[]\n")

    def test_rejects_invalid_json(self) -> None:
        with self.assertRaises(ProtocolError):
            decode_line(b"{bad}\n")

    def test_rejects_oversized_input_and_output(self) -> None:
        with self.assertRaisesRegex(ProtocolError, "request too large"):
            decode_line(b"x" * (MAX_LINE_BYTES + 1))
        with self.assertRaisesRegex(ProtocolError, "response too large"):
            encode_line({"value": "x" * MAX_LINE_BYTES})


if __name__ == "__main__":
    unittest.main()
