"""Tests for the ingredient-parser sidecar, run against a real running instance over HTTP --
not just calling parse_ingredient() directly, so bugs in app.py's own routing/serialization/
error-handling get caught too, not only bugs in the underlying model.

Real ingredient lines throughout, pulled from common real-world phrasing (per AGENTS.md's
existing guidance for this module), not synthetic examples -- and specifically covering the
failure-mode categories that motivated choosing this parser over a hand-rolled regex one (see
PROJECT_BRIEF.md section 4): size words polluting the ingredient name, parenthetical/multiplier
package sizes, multiple prep/comment clauses, and quantity not appearing at the start of the
line.
"""

import http.client
import json
import threading
import time
from http.server import ThreadingHTTPServer

import pytest

from app import Handler

HOST = "127.0.0.1"


@pytest.fixture(scope="module")
def server_port():
    # Port 0 asks the OS for any free port -- avoids collisions with a real deployment or
    # another test run using the default 8000.
    server = ThreadingHTTPServer((HOST, 0), Handler)
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()
    yield port
    server.shutdown()
    thread.join(timeout=5)


def _post(port: int, path: str, body: dict | None) -> tuple[int, dict]:
    conn = http.client.HTTPConnection(HOST, port, timeout=10)
    payload = json.dumps(body).encode("utf-8") if body is not None else b""
    conn.request("POST", path, body=payload, headers={"Content-Type": "application/json"})
    resp = conn.getresponse()
    data = json.loads(resp.read())
    conn.close()
    return resp.status, data


def _get(port: int, path: str) -> tuple[int, dict]:
    conn = http.client.HTTPConnection(HOST, port, timeout=10)
    conn.request("GET", path)
    resp = conn.getresponse()
    data = json.loads(resp.read())
    conn.close()
    return resp.status, data


def _parse(port: int, text: str) -> dict:
    status, data = _post(port, "/parse", {"text": text})
    assert status == 200, f"expected 200, got {status}: {data}"
    return data


def test_health(server_port):
    status, data = _get(server_port, "/health")
    assert status == 200
    assert data == {"status": "ok"}


def test_basic_fraction_quantity_is_exact(server_port):
    result = _parse(server_port, "2 1/2 cups all-purpose flour, sifted")
    assert result["amount"][0]["quantity"] == {"numerator": 5, "denominator": 2}
    assert result["amount"][0]["unit"] == "cups"
    assert "flour" in result["name"][0]["text"].lower()
    assert result["preparation"]["text"] == "sifted"


def test_size_word_is_separated_from_name(server_port):
    # The specific property this parser was chosen for: "2 large eggs" and a hypothetical
    # "3 eggs" must resolve to the same ingredient NAME ("eggs") for larder's shopping-list
    # combination to work -- a regex parser's "whatever's left is the name" rule would instead
    # produce "large eggs" here, which would never combine with plain "eggs".
    result = _parse(server_port, "2 large eggs")
    assert result["name"][0]["text"] == "eggs"
    assert result["size"]["text"] == "large"


def test_parenthetical_package_size_does_not_pollute_name(server_port):
    result = _parse(server_port, "1 (14.5 oz) can diced tomatoes")
    assert result["name"][0]["text"] == "diced tomatoes"
    units = {a["unit"] for a in result["amount"]}
    assert units == {"can", "oz"}


def test_multiple_trailing_clauses_captured(server_port):
    # A "trailing comma-clause as notes" regex heuristic only ever catches the LAST clause --
    # this line has two ("sifted", "divided").
    result = _parse(server_port, "2 cups flour, sifted, divided")
    assert "sifted" in result["preparation"]["text"]
    assert "divided" in result["preparation"]["text"]


def test_quantity_after_name(server_port):
    result = _parse(server_port, "Flour, 2 cups")
    assert result["name"][0]["text"].lower() == "flour"
    assert result["amount"][0]["quantity"] == {"numerator": 2, "denominator": 1}
    assert result["amount"][0]["unit"] == "cups"


def test_no_quantity_falls_back_gracefully(server_port):
    result = _parse(server_port, "Salt, to taste")
    assert result["amount"] == []
    assert result["comment"]["text"] == "to taste"


def test_malformed_body_returns_400(server_port):
    status, data = _post(server_port, "/parse", {"nope": "wrong field"})
    assert status == 400
    assert data["error"]["code"] == "INVALID_BODY"


def test_empty_text_returns_400(server_port):
    status, data = _post(server_port, "/parse", {"text": "   "})
    assert status == 400
    assert data["error"]["code"] == "INVALID_BODY"


def test_unknown_route_returns_404_error_envelope(server_port):
    status, data = _get(server_port, "/nope")
    assert status == 404
    assert data["error"]["code"] == "NOT_FOUND"
