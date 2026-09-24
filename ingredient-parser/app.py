"""larder ingredient-parser sidecar.

Wraps strangetom/ingredient-parser's parse_ingredient() in a small HTTP service so the Kotlin
backend can call it over the internal Docker network instead of needing a Python runtime of its
own. See README.md for why this exists and how the two sides talk to each other; see
PROJECT_BRIEF.md section 4 in the main repo for the full research behind this decision.

Deliberately stdlib-only (http.server, no Flask) -- this is one endpoint wrapping one function
call, and reaching for a framework here would be inconsistent with the Kotlin side's own
hand-rolled-HttpServer, no-framework discipline. See AGENTS.md's dependency policy.
"""

import dataclasses
import json
import os
import sys
from enum import Enum
from fractions import Fraction
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# Importing this is what loads the trained model, NLTK's tagger data, and the embeddings file
# into memory -- once, here, at process start, not per request. If loading fails the process
# crashes immediately (a container that can't load its model shouldn't report itself healthy).
from ingredient_parser import parse_ingredient

PORT = int(os.environ.get("INGREDIENT_PARSER_PORT", "8000"))


def _json_default(obj: object) -> object:
    # dataclasses.asdict() recursively turns nested dataclasses into plain dicts, but leaves
    # non-dataclass leaf values (a Fraction quantity, a UnitSystem enum) untouched -- this
    # handles those for json.dumps. Fraction becomes {numerator, denominator} rather than a
    # lossy float, matching larder's own exact-fraction quantity representation directly.
    if isinstance(obj, Fraction):
        return {"numerator": obj.numerator, "denominator": obj.denominator}
    if isinstance(obj, Enum):
        return obj.value
    raise TypeError(f"Object of type {type(obj).__name__} is not JSON serializable")


class Handler(BaseHTTPRequestHandler):
    def _send_json(self, status: int, payload: dict) -> None:
        body = json.dumps(payload, default=_json_default).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    # Same {"error": {"code": ..., "message": ...}} shape as the Kotlin API's error envelope
    # (larder.api.ErrorEnvelope) -- consistency across the system even though nothing outside
    # larder's own backend ever calls this.
    def _send_error(self, status: int, code: str, message: str) -> None:
        self._send_json(status, {"error": {"code": code, "message": message}})

    def do_GET(self) -> None:
        if self.path == "/health":
            self._send_json(200, {"status": "ok"})
        else:
            self._send_error(404, "NOT_FOUND", f"No route for GET {self.path}")

    def do_POST(self) -> None:
        if self.path != "/parse":
            self._send_error(404, "NOT_FOUND", f"No route for POST {self.path}")
            return

        length = int(self.headers.get("Content-Length", 0))
        raw_body = self.rfile.read(length) if length else b""
        try:
            request = json.loads(raw_body)
            text = request["text"]
            if not isinstance(text, str) or not text.strip():
                raise ValueError("text must be a non-empty string")
        except (json.JSONDecodeError, KeyError, ValueError, TypeError):
            self._send_error(400, "INVALID_BODY", "Malformed request body")
            return

        try:
            result = parse_ingredient(text, string_units=True)
        except Exception:
            # Never leak a stack trace. The caller's fallback for any non-200 response is to
            # treat the line as unparsed (raw_text only) -- see PROJECT_BRIEF.md section 4.
            self._send_error(500, "INTERNAL_ERROR", "Unexpected parser error")
            return

        self._send_json(200, dataclasses.asdict(result))


def main() -> None:
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"ingredient-parser listening on port {PORT}", file=sys.stderr)
    server.serve_forever()


if __name__ == "__main__":
    main()
