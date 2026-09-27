# larder ingredient-parser sidecar

A small standalone Python HTTP service wrapping
[`strangetom/ingredient-parser`](https://github.com/strangetom/ingredient-parser) (MIT
licensed) — turns a raw ingredient line like `"2 1/2 cups all-purpose flour, sifted"` into
structured quantity/unit/name/size/preparation/comment data.

This exists because no JVM/Kotlin-native equivalent does, and porting the underlying model to
Kotlin turned out to be a multi-thousand-line undertaking (CRF decoder + NLTK POS tagging +
hand-engineered features + a 2,400-line postprocessing engine), not proportionate to build or
maintain. Running the real, actively-maintained upstream package as a sidecar — called over
HTTP from the Kotlin backend — was the option that actually cleared the bar. See
`PROJECT_BRIEF.md` section 4 and `docs/decisions.md` in the main repo for the full research and
reasoning.

**Status: standalone only.** This service is complete and independently testable, but nothing
in the Kotlin backend calls it yet — that's a separate, later phase (`V1_PLAN.md` Phase 4b:
the `IngredientLineParser` interface, the HTTP client call, `docker-compose.yml` wiring, and
resolving this service's raw guesses against larder's own `ingredients`/`units` tables).

## API

- `GET /health` → `{"status": "ok"}`
- `POST /parse` — body `{"text": "2 cups flour"}`, returns the parser's full structured output
  as JSON (name, size, amount — a list, since one line can carry more than one quantity, e.g.
  a can's count *and* its per-unit size — preparation, comment, purpose, foundation_foods,
  sentence). A `quantity` is always `{"numerator": ..., "denominator": ...}`, never a float —
  matching larder's own exact-fraction quantity representation directly, not a coincidence.
- Errors use the same envelope shape as the Kotlin API: `{"error": {"code": ..., "message":
  ...}}`. 400 for a malformed/empty `text`, 404 for an unknown route, 500 for an unexpected
  parser failure (never a leaked stack trace).

Deliberately built on Python's stdlib `http.server` (`ThreadingHTTPServer`), not Flask or any
other framework — one endpoint wrapping one function call didn't justify a dependency, matching
the Kotlin side's own no-framework discipline.

## Running it standalone

```
docker build -t larder-ingredient-parser .
docker run --rm -p 8000:8000 larder-ingredient-parser
curl -X POST localhost:8000/parse -H 'Content-Type: application/json' \
  -d '{"text": "2 1/2 cups all-purpose flour, sifted"}'
```

The model, NLTK's tagger data, and the embeddings file are all fetched during the Docker
*build* (`RUN python -c "import ingredient_parser"` in the `Dockerfile`) — the running
container needs no internet access. Confirm that directly rather than assuming it:

```
docker run --rm --network none larder-ingredient-parser python -c \
  "from ingredient_parser import parse_ingredient; print(parse_ingredient('1 cup flour'))"
```

## Testing

```
pip install -r requirements.txt pytest
pytest
```

Tests spin up a real instance of the service (in-process, on an ephemeral port) and hit it over
real HTTP — not just calling the underlying library directly — so bugs in this wrapper's own
routing/serialization/error-handling get caught too, not only bugs in the model itself. Test
cases are real ingredient-line phrasing, chosen specifically to cover the failure modes that
motivated using this parser over a hand-rolled regex one (see `PROJECT_BRIEF.md` section 4):
size words polluting the ingredient name, parenthetical/multiplier package sizes, multiple
trailing clauses, and quantity not appearing at the start of the line.

## Configuration

| Variable | Default |
|---|---|
| `INGREDIENT_PARSER_PORT` | `8000` |
| `INGREDIENT_PARSER_HOST` | `0.0.0.0` (the bundled larder images set `127.0.0.1`) |
