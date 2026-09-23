# Architecture — as built

This is the living description of what actually exists in the `larder` codebase, kept in sync
as each phase of `V1_PLAN.md` lands. `PROJECT_BRIEF.md` is the target design and doesn't
change; `V1_PLAN.md` is the fixed order of work and doesn't change either. This file is the
current reality, and gets rewritten in place — not appended to — every time that reality
changes.

## Status

**Phases 0–2 are done.** Repo scaffold, planning docs, a backend skeleton (hand-rolled router,
sealed `ApiResult`/JSON error-envelope pattern, pooled JDBC connection, `GET /api/health` and
`GET /api/version`), and the full v1 database schema: `users`, `sessions`, the global
`ingredients`/`ingredient_aliases`/`units`/`unit_conversions` vocabulary (seeded with 21
starter units), `recipes` (soft-delete via `deleted_at`), `recipe_ingredients`,
`meal_plan_entries`, `shopping_lists`, `shopping_list_items`, `shopping_list_item_sources` — a
hand-rolled migration runner (ported from `shelf`'s) applies them on startup. Verified
end-to-end against a real `postgres:18.6-alpine` container each phase, not just compiled:
Phase 1's health/version/404 round-trip, and Phase 2's migrations actually applying plus every
constraint that matters exercised directly (case-insensitive ingredient/alias uniqueness, the
paired-nullability and positive-denominator quantity `CHECK`s, the count-dimension/
`to_base_factor` `CHECK`, the scoped-vs-global `unit_conversions` partial unique indexes, and —
the one this schema exists to get right — that hard-deleting a meal-planned recipe is blocked
by `ON DELETE RESTRICT` while soft-deleting it succeeds and the meal-plan entry survives).

## System shape

```
Browser (VanJS UI — not yet built, Phase 9)
      |
      v
App server (Kotlin, JDK stdlib HTTP, REST/JSON API)
      |
      v
  Postgres (source of truth for everything — see PROJECT_BRIEF.md §4)
```

Full rationale in `PROJECT_BRIEF.md` §3–4. Nothing here deviates from it yet.

## Repo layout

```
larder/
  backend/
    src/
      Main.kt                composition root: reads env config, opens the DB pool, wires
                               the router, starts HttpServer. No migration runner yet —
                               that's Phase 2's job.
      api/
        Router.kt              hand-rolled router: method+path matching, :param segments,
                                 .get/.post/.delete (JSON only — no streaming/static-file
                                 serving yet, unlike shelf's Router; added when Phase 9
                                 actually needs it)
        ApiResult.kt            sealed Ok/Err result type + the fixed JSON error envelope
        HealthHandler.kt        GET /api/health
        VersionHandler.kt       GET /api/version (reads SELECT version() from Postgres)
      db/
        ConnectionPool.kt       fixed-size pool of JDBC connections, opened once at startup
        Database.kt              queryOne/queryOneOrNull/queryList/update, all
                                  PreparedStatement-bound — see AGENTS.md's SQL-injection rule
        MigrationRunner.kt       hand-rolled migration runner, ported from shelf's — discovers
                                  NNN_*.sql files, tracks applied versions in
                                  schema_migrations, runs pending ones in a transaction each
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
  docker/                      empty — Phase 10
```

## Dependencies in use

Exactly the pre-approved set from `PROJECT_BRIEF.md` §2 — nothing beyond it yet:
`postgresql-42.7.13.jar` (JDBC driver), `kotlinx-serialization-core-jvm-1.11.0.jar`,
`kotlinx-serialization-json-jvm-1.11.0.jar`. Same exact versions `shelf` already vetted.

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

## Not built yet

Everything past Phase 2: auth, the ingredient-line parser, recipe CRUD, URL import, meal
planning, shopping-list generation, the frontend, and Docker packaging. See `V1_PLAN.md` for
the phase order.
