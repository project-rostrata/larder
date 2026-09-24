# Architecture — as built

This is the living description of what actually exists in the `larder` codebase, kept in sync
as each phase of `V1_PLAN.md` lands. `PROJECT_BRIEF.md` is the target design and doesn't
change; `V1_PLAN.md` is the fixed order of work and doesn't change either. This file is the
current reality, and gets rewritten in place — not appended to — every time that reality
changes.

## Status

**Phases 0–3 and 4a are done.** Repo scaffold and planning docs; a backend skeleton
(hand-rolled router, sealed `ApiResult`/JSON error-envelope pattern, pooled JDBC connection,
`GET /api/health`, `GET /api/version`); the full v1 database schema (`users`, `sessions`, the
global `ingredients`/`ingredient_aliases`/`units`/`unit_conversions` vocabulary seeded with 21
starter units, `recipes` with soft-delete via `deleted_at`, `recipe_ingredients`,
`meal_plan_entries`, `shopping_lists`, `shopping_list_items`, `shopping_list_item_sources`,
applied by a hand-rolled migration runner ported from `shelf`'s); auth — PBKDF2 password
hashing, cookie-based sessions, `POST /api/register`/`login`/`logout`, `GET /api/me` behind
`requireAuth`, username + password only, no email column at all (unlike `shelf`, which collects
and later dropped it — larder never had it to begin with, per the human's explicit direction);
and a standalone ingredient-parser sidecar (`ingredient-parser/` — Python, wraps
`strangetom/ingredient-parser`), built and verified in isolation but **not yet called by
anything** — the Kotlin-side integration is Phase 4b, not yet started.

Verified end-to-end against a real `postgres:18.6-alpine` container every phase, not just
compiled: Phase 1's health/version/404 round-trip; Phase 2's migrations actually applying plus
every constraint exercised directly (case-insensitive ingredient/alias uniqueness, the
paired-nullability and positive-denominator quantity `CHECK`s, the count-dimension/
`to_base_factor` `CHECK`, the scoped-vs-global `unit_conversions` partial unique indexes, and
the soft-delete/`RESTRICT` interaction — hard-deleting a meal-planned recipe is blocked,
soft-deleting it succeeds, the meal-plan entry survives); and Phase 3's full auth flow through
the real HTTP API — register, `/me` with and without a session cookie, duplicate-username
409, short-password and invalid-username 400s, an unexpected field (`email`) rejected outright
rather than silently accepted, login with correct/wrong/nonexistent-username credentials (the
last two returning the identical `INVALID_CREDENTIALS` response, confirmed no username
enumeration), logout actually invalidating the session server-side (confirmed via a following
401, not just a 200 response), logout being idempotent, and the `Set-Cookie` attributes
themselves (`larder_session`, `HttpOnly`, `SameSite=Lax`, correct `Max-Age`). Phase 4a's
sidecar was built, then verified for real: `pytest` (10 cases, real ingredient-line phrasing)
passing both locally and inside the built Docker image; the built image actually running and
answering `/health` and `/parse` over real HTTP; parsing still working with the container's
network disabled entirely (`docker run --network none`), proving the NLTK data is genuinely
baked in at build time and not fetched at runtime; and measured per-request latency (~4.5–5.8ms
against a warm container) confirming the earlier performance estimate.

## System shape

```
Browser (VanJS UI — not yet built, Phase 9)
      |
      v
App server (Kotlin, JDK stdlib HTTP, REST/JSON API)
      |                       :
      v                       : not wired up yet (Phase 4b)
  Postgres (source of truth   :
   for everything)     Ingredient-parser sidecar (Python, ingredient-parser/ — built and
                         verified standalone, Phase 4a; nothing calls it yet)
```

Full rationale in `PROJECT_BRIEF.md` §3–4. The one thing that's deviated from the brief's
diagram so far is exactly this: the sidecar exists and works, but the dotted line (Kotlin →
sidecar) isn't real yet.

## Repo layout

```
larder/
  backend/
    src/
      Main.kt                composition root: reads env config, opens the DB pool, wires
                               the router, starts HttpServer. No migration runner yet —
                               that's Phase 2's job.
      auth/
        PasswordHasher.kt        PBKDF2WithHmacSHA256, 600k iterations, self-describing stored
                                   format (algorithm/iterations travel with the hash)
        AuthConfig.kt             session duration + secure-cookies flag, passed into handlers
      api/
        Router.kt              hand-rolled router: method+path matching, :param segments,
                                 .get/.post/.delete (JSON only — no streaming/static-file
                                 serving yet, unlike shelf's Router; added when Phase 9
                                 actually needs it)
        ApiResult.kt            sealed Ok/Err result type + the fixed JSON error envelope
        Cookies.kt                parse Cookie header / build Set-Cookie (larder_session)
        Auth.kt                    requireAuth() middleware — resolves the full
                                    AuthenticatedUser(id, username) once, not a bare UUID
        AuthResponse.kt             shared {userId, username} DTO
        HealthHandler.kt        GET /api/health
        VersionHandler.kt       GET /api/version (reads SELECT version() from Postgres)
        RegisterHandler.kt      POST /api/register — username/password only, no email field
                                  at all (unlike shelf); unknown JSON fields rejected outright
        LoginHandler.kt           POST /api/login — identical error for wrong password vs.
                                    unknown username, no enumeration
        LogoutHandler.kt           POST /api/logout — idempotent, reads the cookie directly
                                    rather than through requireAuth
        MeHandler.kt                GET /api/me (behind requireAuth)
      db/
        ConnectionPool.kt       fixed-size pool of JDBC connections, opened once at startup
        Database.kt              queryOne/queryOneOrNull/queryList/update, all
                                  PreparedStatement-bound — see AGENTS.md's SQL-injection rule
        MigrationRunner.kt       hand-rolled migration runner, ported from shelf's — discovers
                                  NNN_*.sql files, tracks applied versions in
                                  schema_migrations, runs pending ones in a transaction each
        UserRow.kt / UserRepository.kt       id, username, password_hash, created_at only —
                                               no email/is_admin/quota_bytes, no findAll()
        SessionRow.kt / SessionRepository.kt  ported from shelf's, unchanged shape
    lib/
      DEPENDENCIES.sha1        filename/sha1/source-url manifest — see AGENTS.md
      fetch-deps.sh             downloads + verifies the jars above; jars themselves are
                                 gitignored
    build.sh                   bare kotlinc compile, no Gradle/Maven (see AGENTS.md's
                                 dependency/build-tool policy for when that might change)
    run.sh                     runs the compiled backend; defaults LARDER_MIGRATIONS_DIR to
                                 ../db/migrations for local dev
  frontend/                    empty — Phase 9
  db/
    migrations/
      0001_initial_schema.sql   all v1 tables, indexes, and constraints (PROJECT_BRIEF.md §7)
      0002_seed_units.sql       21 starter units (volume/mass/count) with conversion factors
  ingredient-parser/            Phase 4a — standalone Python sidecar, see its own README.md
    app.py                       stdlib http.server, POST /parse + GET /health, model loaded
                                   once at import time, Fraction quantities (not floats)
    requirements.txt              ingredient-parser-nlp==2.8.0 + its nltk/numpy/pint deps,
                                    exact-pinned
    Dockerfile                     pre-fetches NLTK data at build time (not runtime) via an
                                     explicit download_dir, verified with --network none
    test_app.py                     pytest, real HTTP requests against a real running
                                      instance, covering the specific failure modes that
                                      motivated this over a regex parser
  docker/                      empty — Phase 10 (will need to wire ingredient-parser in too,
                                once Phase 4b exists)
```

## Dependencies in use

**Kotlin side**: exactly the pre-approved set from `PROJECT_BRIEF.md` §2 — nothing beyond it
yet: `postgresql-42.7.13.jar` (JDBC driver), `kotlinx-serialization-core-jvm-1.11.0.jar`,
`kotlinx-serialization-json-jvm-1.11.0.jar`. Same exact versions `shelf` already vetted. Still
on bare `kotlinc`, no Gradle.

**`ingredient-parser/` (Python, its own separate dependency set — see `AGENTS.md`'s
"new runtime/service" policy)**: `ingredient-parser-nlp==2.8.0` and its own
`nltk==3.10.3`/`numpy==2.5.3`/`pint==0.26.1`, exact-pinned in `ingredient-parser/requirements.txt`.
No Flask or other web framework — stdlib `http.server`.

## Configuration

Read from environment variables at startup (`Main.kt`), no config file:

| Variable | Required | Default |
|---|---|---|
| `LARDER_PORT` | no | `8080` |
| `LARDER_DB_URL` | yes | — |
| `LARDER_DB_USER` | yes | — |
| `LARDER_DB_PASSWORD` | yes | — |
| `LARDER_DB_POOL_SIZE` | no | `10` |
| `LARDER_MIGRATIONS_DIR` | yes | — (`run.sh` defaults it to `../db/migrations` for local dev) |
| `LARDER_SESSION_DURATION_HOURS` | no | `720` (30 days) |
| `LARDER_SECURE_COOKIES` | no | `false` (real deployments must override to `true`; local HTTP dev needs it off) |

`ingredient-parser/` has its own separate, small config surface — see its own README.md
(currently just `INGREDIENT_PARSER_PORT`, default `8000`). Not part of the table above; it's a
different process with its own env-var namespace.

## Not built yet

Phase 4b (the Kotlin-side `IngredientLineParser` interface, the HTTP call to the sidecar, and
resolving its output against `ingredients`/`units`) and everything past Phase 4: recipe CRUD,
URL import, meal planning, shopping-list generation, the frontend, and Docker packaging
(including wiring `ingredient-parser` into `docker-compose.yml`). See `V1_PLAN.md` for the
phase order.
