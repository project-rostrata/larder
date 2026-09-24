# Architecture — as built

This is the living description of what actually exists in the `larder` codebase, kept in sync
as each phase of `V1_PLAN.md` lands. `PROJECT_BRIEF.md` is the target design and doesn't
change; `V1_PLAN.md` is the fixed order of work and doesn't change either. This file is the
current reality, and gets rewritten in place — not appended to — every time that reality
changes.

## Status

**Phases 0–5 are done.** Repo scaffold and planning docs; a backend skeleton (hand-rolled
router, sealed `ApiResult`/JSON error-envelope pattern, pooled JDBC connection, `GET
/api/health`, `GET /api/version`); the full v1 database schema (`users`, `sessions`, the global
`ingredients`/`ingredient_aliases`/`units`/`unit_conversions` vocabulary seeded with 21 starter
units, `recipes` with soft-delete via `deleted_at`, `recipe_ingredients`, `meal_plan_entries`,
`shopping_lists`, `shopping_list_items`, `shopping_list_item_sources`, applied by a hand-rolled
migration runner ported from `shelf`'s); auth — PBKDF2 password hashing, cookie-based sessions,
`POST /api/register`/`login`/`logout`, `GET /api/me` behind `requireAuth`, username + password
only, no email column at all (unlike `shelf`, which collects and later dropped it — larder
never had it to begin with, per the human's explicit direction); a standalone ingredient-parser
sidecar (`ingredient-parser/` — Python, wraps `strangetom/ingredient-parser`); the Kotlin side
that calls it (`larder.ingredients.IngredientLineParser`/`SidecarIngredientLineParser`,
`IngredientResolver`, `IngredientRepository`/`UnitRepository`); and now real recipe CRUD —
`GET/POST /api/recipes`, `GET/PUT/DELETE /api/recipes/{id}`,
`POST /api/ingredients/{id}/merge-into/{targetId}` — the first endpoints that actually call the
ingredient-parsing pipeline built in Phase 4.

**`backend/test/TestMain.kt` and `backend/test.sh` exist for the first time as of Phase 4b** —
`AGENTS.md`'s testing section anticipated "Phase 1 or Phase 4" as the trigger; Phase 4a turned
out to be Python (its own separate `pytest` suite), so Phase 4b is where it actually landed.

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
against a warm container) confirming the earlier performance estimate. Phase 4b's Kotlin code
was verified the same way, against real running containers, via a temporary (not committed)
entry point rather than the checked-in test suite (DB/network-touching code doesn't fit the
hermetic hand-rolled suite — see `docs/decisions.md`): five real ingredient lines parsed and
resolved end-to-end into real rows, resolving the same new ingredient twice confirmed
idempotent (same id, correct `ingredientWasNewlyCreated` both times), alias-based unit
resolution confirmed (`"cups"` → the seeded `cup` unit), and graceful degradation against an
unreachable sidecar confirmed (logs a warning, returns an all-null result, never throws).
Phase 5 was verified the same way, through the real HTTP API end to end: created a recipe with
real ingredient lines (including the parenthetical-package-size and bare-count cases) and
confirmed each resolved correctly; confirmed listing/tag-filtering, `PUT` fully replacing
`recipe_ingredients` rather than patching it, and soft-delete's full contract (excluded from
listing, still resolves by direct fetch, a second delete or an update on it both 404); confirmed
cross-user ownership isolation (a second user sees zero of the first user's recipes and gets 404
— not 403 — trying to read, update, or delete one); confirmed `merge-into` reassigns a real
`recipe_ingredients` row, rejects self-merge, and 404s on an already-merged-away id; and
confirmed the resilience case that matters most: stopping the sidecar mid-session and creating a
recipe anyway still succeeds, with that ingredient line falling back to raw-text-only.

## System shape

```
Browser (VanJS UI — not yet built, Phase 9)
      |
      v
App server (Kotlin, JDK stdlib HTTP, REST/JSON API)
      |                       |
      v                       v
  Postgres (source of truth   Ingredient-parser sidecar (Python, ingredient-parser/)
   for everything)
```

Full rationale in `PROJECT_BRIEF.md` §3–4. Both arrows are real now — recipe creation/update
calls the sidecar, confirmed live. `docker-compose.yml` doesn't run the two services together
as one deployment yet, though (Phase 10) — Phase 5's verification pointed the app at the
sidecar's host-mapped port directly, not through a Compose network.

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
                                 .get/.post/.put/.delete (JSON only — no streaming/static-file
                                 serving yet, unlike shelf's Router; added when Phase 9
                                 actually needs it). put() added Phase 5 — PUT /api/recipes/{id}
                                 was the first route that needed it
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
        RecipeRequest.kt         request DTOs, shared by create and update
        RecipeResponse.kt         response DTOs + RecipeRow.toResponse()/
                                    PersistedRecipe.toWriteResponse() mappers — Read and Write
                                    response shapes are deliberately separate types (see
                                    docs/decisions.md)
        RecipeValidation.kt        validateRecipeRequest(), shared by create and update
        RecipeIngredientResolution.kt  resolveIngredientLines() — the parser+resolver call per
                                         raw line, shared by create and update, order-preserving
        RecipesListHandler.kt      GET /api/recipes?tag=... — excludes soft-deleted
        RecipeGetHandler.kt         GET /api/recipes/{id} — does NOT exclude soft-deleted
        RecipeCreateHandler.kt        POST /api/recipes
        RecipeUpdateHandler.kt         PUT /api/recipes/{id} — full replace, not a patch; 404
                                         on a deleted/missing/not-owned recipe, all identical
        RecipeDeleteHandler.kt           DELETE /api/recipes/{id} — soft delete; already-deleted
                                           is 404, not idempotent-200
        IngredientMergeHandler.kt          POST /api/ingredients/{id}/merge-into/{targetId} — no
                                             ownership check, ingredients are global
      db/
        ConnectionPool.kt       fixed-size pool of JDBC connections, opened once at startup
        Database.kt              queryOne/queryOneOrNull/queryList/update, all
                                  PreparedStatement-bound — see AGENTS.md's SQL-injection rule.
                                  transaction()/Transaction added Phase 5 — the first thing
                                  needing more than one statement to commit atomically
        MigrationRunner.kt       hand-rolled migration runner, ported from shelf's — discovers
                                  NNN_*.sql files, tracks applied versions in
                                  schema_migrations, runs pending ones in a transaction each
        UserRow.kt / UserRepository.kt       id, username, password_hash, created_at only —
                                               no email/is_admin/quota_bytes, no findAll()
        SessionRow.kt / SessionRepository.kt  ported from shelf's, unchanged shape
        IngredientRow.kt / IngredientRepository.kt  global, not owner_id-scoped; exact match on
                                                      name or ingredient_aliases; auto-creates
                                                      and reports whether this call created it;
                                                      mergeInto() added Phase 5
        UnitRow.kt / UnitRepository.kt         global; match on name/abbreviation/aliases; no
                                                 create — units are seeded, not user-grown
        RecipeRow.kt / RecipeIngredientRow.kt / RecipeRepository.kt  owner_id-scoped;
                                                                       create/update both run in
                                                                       one Transaction; findById
                                                                       does not filter
                                                                       deleted_at, list() does
      ingredients/
        IngredientLineParser.kt   the interface + ParsedIngredientLine (raw split, not yet
                                    resolved against larder's own tables)
        SidecarIngredientLineParser.kt  calls the sidecar over HTTP; degrades to an all-null
                                          result on any failure, verified directly, never
                                          throws. parseSidecarResponse()/extractPrimaryAmount()
                                          are pure top-level functions, unit-tested separately
        IngredientResolver.kt      ParsedIngredientLine + the two repositories above ->
                                     ResolvedIngredientLine (unitId, ingredientId,
                                     ingredientWasNewlyCreated)
    test/
      TestMain.kt                 hand-rolled runner, ported from shelf's — reflectively finds
                                    and runs every test*() in the classes listed here
      SidecarIngredientLineParserTest.kt  8 cases, built from real captured sidecar responses
                                            (multiple amounts, composite, range, notes-joining)
    lib/
      DEPENDENCIES.sha1        filename/sha1/source-url manifest — see AGENTS.md
      fetch-deps.sh             downloads + verifies the jars above; jars themselves are
                                 gitignored
    build.sh                   bare kotlinc compile, no Gradle/Maven (see AGENTS.md's
                                 dependency/build-tool policy for when that might change)
    run.sh                     runs the compiled backend; defaults LARDER_MIGRATIONS_DIR to
                                 ../db/migrations for local dev
    test.sh                    compiles src/+test/, runs TestMain -- hermetic tests only, see
                                 docs/decisions.md for why DB/network-touching code isn't here
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
| `LARDER_INGREDIENT_PARSER_URL` | yes | — (`run.sh` defaults it to `http://localhost:8000` for local dev; Compose will point it at the sidecar's service name instead, Phase 10) |

`ingredient-parser/` has its own separate, small config surface — see its own README.md
(currently just `INGREDIENT_PARSER_PORT`, default `8000`). Not part of the table above; it's a
different process with its own env-var namespace.

## Not built yet

Everything past Phase 5: URL import, meal planning, shopping-list generation, the frontend, and
Docker packaging (including wiring `ingredient-parser` into `docker-compose.yml`). See
`V1_PLAN.md` for the phase order.
