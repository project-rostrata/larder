# larder — v1 implementation plan

Built from `PROJECT_BRIEF.md`. Section 5's open questions are still open — each phase below
that touches one uses the brief's proposed default and flags it, rather than guessing silently.

This plan breaks v1 into ordered phases. Each phase should leave the app in a buildable,
runnable state — no phase depends on later phases to compile.

Dependency policy (brief section 2): start on bare `kotlinc` with zero non-`kotlinx`
dependencies, same as `shelf`. Unlike `shelf`, moving to Gradle or adding a dependency beyond
`kotlinx.*`/the Postgres driver is expected to come up (most likely in Phase 6, recipe import)
— when it does, propose the specific need before adding it, don't add it by default.

**No path-safety or filesystem-reconciliation phase.** Earlier drafts of this plan (mirroring
`shelf`'s Phase 4/5) included one — that was dropped when the storage-model decision changed
from file-backed recipes to Postgres-as-truth, before any code existed. See
`PROJECT_BRIEF.md` section 4 and `docs/decisions.md`. There is no filesystem content anywhere
in this app's v1 scope.

## Phase 0 — Repo scaffolding

- Directory layout:
  ```
  larder/
    backend/
      src/            (Kotlin sources)
      lib/             (dependency jars, fetched by a script — never committed)
      test/            (hand-rolled test suite, parallel to src/)
      build.sh          (explicit kotlinc compile command)
      run.sh            (explicit java run command)
      test.sh           (compiles src/+test/, runs the hand-rolled runner)
    frontend/
      index.html
      app.js            (VanJS entry point)
      lib/van.js         (VanJS, vendored — no npm)
      src/               (views/components, plain ES modules)
    db/
      migrations/        (numbered SQL migrations, same pattern as shelf)
    docker/
      Dockerfile
      docker-compose.yml
    docs/
    PROJECT_BRIEF.md
    V1_PLAN.md
    AGENTS.md
  ```
- No `entrypoint.sh`/`PUID`/`PGID` handling planned (brief section 8) — nothing to reconcile
  file ownership for.
- This phase is docs/structure only — no application code yet. `build.sh`/`run.sh` and the
  vendored dependency-fetch script land at the start of Phase 1, the first phase that has
  anything to compile.

## Phase 1 — Backend skeleton: router + one clean example endpoint

Same purpose as it served in `shelf`: get the router/JSON/error-handling pattern right once,
early, on bare `kotlinc`, before anything else copies it.

- Hand-rolled router on `com.sun.net.httpserver.HttpServer` — path + method matching, path
  params, a small `Route` data structure. Reuse `shelf`'s router shape directly; there's no
  reason to redesign it.
- JSON via `kotlinx.serialization` (pre-approved, same as `shelf`).
- `GET /api/health` and `GET /api/version` (reading a value from Postgres) as the reference
  endpoints, establishing the plain `java.sql`/JDBC connection pattern.
- Sealed `ApiResult`/error-envelope pattern, identical shape to `shelf`'s (see `AGENTS.md`).

## Phase 2 — Database schema

- `db/migrations/0001_initial_schema.sql` implementing brief section 7 in full: `users`,
  `sessions`, `recipes`, `recipe_ingredients`, `meal_plan_entries`, `shopping_lists`,
  `shopping_list_items`. Every table is authoritative — none of it is a cache, unlike `shelf`'s
  `file_index`.
- Reuse `shelf`'s hand-rolled migration runner pattern (numbered `NNN_*.sql` files, a
  `schema_migrations` tracking table) — no Flyway/Liquibase.

## Phase 3 — Auth

- Same as `shelf`'s Phase 3: PBKDF2 password hashing, `POST /api/register` /
  `POST /api/login` / `POST /api/logout`, cookie-based sessions backed by the `sessions` table,
  auth middleware wrapping protected routes.
- **No per-user home directory** — unlike `shelf`, there's no filesystem location to create on
  registration. A new user is just a row; ownership of everything else is an `owner_id` foreign
  key, checked on every query.

## Phase 4 — Ingredient-line parser

Build and unit-test this standalone, before Phase 5 or Phase 6 depend on it — this is larder's
equivalent of `shelf`'s Phase 4 (path safety) in risk profile, even though the subject matter
is completely different.

- Input a raw string (`"2 1/2 cups all-purpose flour, sifted"`), output
  `{quantity, unit, name, notes, raw_text}`. Cover mixed numbers, simple fractions, decimals, a
  fixed unit vocabulary (and common abbreviations — `tbsp`/`tablespoon`/`tablespoons`), and a
  trailing comma-clause as notes. Unparsed lines fall back to
  `{quantity: null, unit: null, name: raw_text, notes: null}` — never throw on a line that
  doesn't fit the pattern.
- Write real test cases from real recipe sites' ingredient lists, not just synthetic examples —
  this module's quality is what makes or breaks the shopping-list feature.

## Phase 5 — Recipe CRUD

All straightforward reads/writes against Phase 2's tables, scoped by `owner_id` on every query
— no reconciliation, no lazy rescan, no background scan job, because Postgres is simply the
data, not a cache of something else.

- `GET /api/recipes?tag=...` — listing, filtered by tag.
- `GET /api/recipes/{id}` — a single recipe with its ingredients.
- `POST /api/recipes`, `PUT /api/recipes/{id}`, `DELETE /api/recipes/{id}` — create/edit/delete,
  writing `recipes` + `recipe_ingredients` rows in one transaction. Manually-entered ingredient
  lines go through Phase 4's parser too, same as imported ones.

## Phase 6 — Recipe URL import

- `POST /api/recipes/import { url }`: fetch with `java.net.http.HttpClient`, extract
  `<script type="application/ld+json">` blocks via regex, parse with
  `kotlinx.serialization.json`, find a `Recipe`-typed object (top-level or inside `@graph`),
  map its fields (`name`, `recipeIngredient`, `recipeInstructions`, `recipeYield`, `prepTime`/
  `cookTime`/`totalTime` as ISO 8601 durations, `keywords`) onto `recipes`/`recipe_ingredients`
  rows, running every `recipeIngredient` string through Phase 4's parser.
  No image field is read or stored, per brief section 2.
- If this phase reveals that JSON-LD coverage is too thin across real-world recipe sites to be
  useful, that's the trigger point for raising the `jsoup`/HTML-parsing dependency question
  from brief section 4 — don't silently add it, surface it first.

## Phase 7 — Meal planning

- `GET /api/meal-plan?from=...&to=...`, `POST /api/meal-plan`, `DELETE /api/meal-plan/{id}` —
  CRUD for `meal_plan_entries` (date, meal slot, recipe id, servings multiplier).
- Uses brief section 5's proposed default (servings multiplier per entry, recipe row itself
  never mutated) — flag to the human before this phase locks the behavior in if it hasn't been
  confirmed by then.

## Phase 8 — Shopping list generation

- `POST /api/shopping-lists { recipe_ids } | { meal_plan_from, meal_plan_to }` — gathers every
  ingredient across the selected recipes (a query against `recipe_ingredients`, scaled by each
  meal-plan entry's servings multiplier where applicable), combines by brief section 4's rules
  (exact normalized name + same unit family, hand-rolled conversion table, non-combinable items
  kept separate), and persists the result as a `shopping_lists`/`shopping_list_items` row set.
- `GET /api/shopping-lists/{id}`, `PATCH /api/shopping-lists/{id}/items/{item_id}` (check off /
  edit / delete), `POST /api/shopping-lists/{id}/items` (add a manual item) — the list is a
  living, editable document once generated, not a one-shot computation.

## Phase 9 — Frontend (VanJS)

- Vendor `van.js`, no bundler — same as `shelf`.
- Views: login/register, recipe list (tag filter) + recipe detail/edit, import-from-URL form,
  meal planner (calendar-ish date/slot grid), shopping list view (checkable items, manual add).
- Plain `fetch` calls to the Phase 1/3/5/6/7/8 API. Responsive layout.

## Phase 10 — Docker

- `Dockerfile`: build stage runs `backend/build.sh` explicitly (same "no hidden build-tool
  magic" pattern as `shelf`, for as long as the project stays on bare `kotlinc` — revisit this
  phase's text if Phase 6 or later triggers a move to Gradle); runtime stage copies compiled
  output + dependency jars.
- `docker-compose.yml`: app service, Postgres service (data via a named volume), optional
  reverse-proxy service. **No bind-mounted storage volume, no `PUID`/`PGID` entrypoint** — see
  brief section 8; there's no user-facing filesystem content to manage.

## Phase 11 — Hardening pass before calling v1 done

- Audit every query that touches `recipes`, `recipe_ingredients`, `meal_plan_entries`,
  `shopping_lists`, or `shopping_list_items` for an `owner_id` (or joined-through-owner) check —
  this is larder's equivalent of `shelf`'s path-safety audit: the one property that must never
  have an exception.
- Confirm every SQL query uses `PreparedStatement` with bound parameters.
- Confirm the only runtime dependencies are ones that were explicitly raised and approved per
  brief section 2 — nothing snuck in silently.
- Manual smoke test of the full v1 feature list from brief section 6: accounts/auth, recipe
  CRUD, URL import against a handful of real recipe sites, meal planning, shopping-list
  generation and combination (including a manual multi-recipe case that should actually
  combine two shared ingredients), responsive UI.

## Deferred to v1.5 / v2 (do not build now, per brief section 6)

- Fuzzy/synonym ingredient-name matching, full-text/tag search, nutrition info, print/export
  view, shopping list grouped by aisle/category, JSON export for backup/portability,
  HTML-microdata import fallback (v1.5).
- Sharing (public link or user-to-user), multi-household support, pantry/inventory tracking
  (v2/stretch).
