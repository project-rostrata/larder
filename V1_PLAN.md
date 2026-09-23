# larder — v1 implementation plan

Built from `PROJECT_BRIEF.md`. Section 5's open questions are still open — each phase below
that touches one uses the brief's proposed default and flags it, rather than guessing silently.

This plan breaks v1 into ordered phases. Each phase should leave the app in a buildable,
runnable state — no phase depends on later phases to compile. Path safety (brief section 4)
applies throughout, not just in the phase that builds it, exactly as it did for `shelf`.

Dependency policy (brief section 2): start on bare `kotlinc` with zero non-`kotlinx`
dependencies, same as `shelf`. Unlike `shelf`, moving to Gradle or adding a dependency beyond
`kotlinx.*`/the Postgres driver is expected to come up (most likely in Phase 7, recipe import)
— when it does, propose the specific need before adding it, don't add it by default.

## Phase 0 — Repo scaffolding

- Directory layout, mirroring `shelf`'s shape with recipe-app-specific pieces swapped in:
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
      entrypoint.sh
    docs/
    PROJECT_BRIEF.md
    V1_PLAN.md
    AGENTS.md
  ```
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

- `db/migrations/0001_initial_schema.sql` implementing brief section 7: `users`, `sessions`,
  `recipe_index`, `meal_plan_entries`, `shopping_lists`, `shopping_list_items`.
- Reuse `shelf`'s hand-rolled migration runner pattern (numbered `NNN_*.sql` files, a
  `schema_migrations` tracking table) — no Flyway/Liquibase.

## Phase 3 — Auth

- Same as `shelf`'s Phase 3: PBKDF2 password hashing, `POST /api/register` /
  `POST /api/login` / `POST /api/logout`, cookie-based sessions backed by the `sessions` table,
  auth middleware wrapping protected routes.
- Per-user home directory: `<storage root>/<username>/recipes/`, created on first
  login/registration.

## Phase 4 — Path safety layer

- Reuse `shelf`'s `resolveUserPath`-style function directly: canonicalize, verify still under
  the user's root, error type on violation. Every recipe-file-touching endpoint from Phase 6
  onward goes through it, no exceptions — same hard requirement as `shelf`.

## Phase 5 — Recipe storage format + ingredient-line parser

This is larder's equivalent of `shelf`'s Phase 4 in risk profile: build and unit-test it
standalone, before any endpoint or import path depends on it.

- Define the recipe JSON format from brief section 4 as `@Serializable` data classes.
- Build the ingredient-line parser as its own module: input a raw string
  (`"2 1/2 cups all-purpose flour, sifted"`), output `{quantity, unit, name, notes, raw_text}`.
  Cover mixed numbers, simple fractions, decimals, a fixed unit vocabulary (and common
  abbreviations — `tbsp`/`tablespoon`/`tablespoons`), and a trailing comma-clause as notes.
  Unparsed lines fall back to `{quantity: null, unit: null, name: raw_text, notes: null}` —
  never throw on a line that doesn't fit the pattern.
- Write real test cases from real recipe sites' ingredient lists, not just synthetic examples —
  this module's quality is what makes or breaks the shopping-list feature.

## Phase 6 — Recipe CRUD + index reconciliation

All routes below go through Phase 4's path-safety function.

- `GET /api/recipes?tag=...` — listing (triggers lazy rescan of the user's recipe directory if
  stale, same mtime-comparison trigger as `shelf`'s Phase 5).
- `GET /api/recipes/{path}` — read a single recipe (from disk, not just the index).
- `POST /api/recipes`, `PUT /api/recipes/{path}`, `DELETE /api/recipes/{path}` — create/edit/
  delete, writing the JSON file and upserting `recipe_index`.
- A background `ScheduledExecutorService` running a periodic full recursive scan per user as
  the reconciliation backstop — same pattern and same NFS rationale as `shelf`'s Phase 5. No
  inotify/filesystem-watch API.

## Phase 7 — Recipe URL import

- `POST /api/recipes/import { url }`: fetch with `java.net.http.HttpClient`, extract
  `<script type="application/ld+json">` blocks via regex, parse with
  `kotlinx.serialization.json`, find a `Recipe`-typed object (top-level or inside `@graph`),
  map its fields (`name`, `recipeIngredient`, `recipeInstructions`, `recipeYield`, `prepTime`/
  `cookTime`/`totalTime` as ISO 8601 durations, `keywords`) onto the internal format, running
  every `recipeIngredient` string through Phase 5's parser.
  No image field is read or stored, per brief section 2.
- If this phase reveals that JSON-LD coverage is too thin across real-world recipe sites to be
  useful, that's the trigger point for raising the `jsoup`/HTML-parsing dependency question
  from brief section 4 — don't silently add it, surface it first.

## Phase 8 — Meal planning

- `GET /api/meal-plan?from=...&to=...`, `POST /api/meal-plan`, `DELETE /api/meal-plan/{id}` —
  CRUD for `meal_plan_entries` (date, meal slot, recipe path, servings multiplier).
- Uses brief section 5's proposed default (servings multiplier per entry, recipe file itself
  never mutated) — flag to the human before this phase locks the behavior in if it hasn't been
  confirmed by then.

## Phase 9 — Shopping list generation

- `POST /api/shopping-lists { recipe_paths } | { meal_plan_from, meal_plan_to }` — gathers
  every ingredient across the selected recipes (scaled by each meal-plan entry's servings
  multiplier where applicable), combines by brief section 4's rules (exact normalized name +
  same unit family, hand-rolled conversion table, non-combinable items kept separate), and
  persists the result as a `shopping_lists`/`shopping_list_items` row set.
- `GET /api/shopping-lists/{id}`, `PATCH /api/shopping-lists/{id}/items/{item_id}` (check off /
  edit / delete), `POST /api/shopping-lists/{id}/items` (add a manual item) — the list is a
  living, editable document once generated, not a one-shot computation.

## Phase 10 — Frontend (VanJS)

- Vendor `van.js`, no bundler — same as `shelf`.
- Views: login/register, recipe list (tag filter) + recipe detail/edit, import-from-URL form,
  meal planner (calendar-ish date/slot grid), shopping list view (checkable items, manual add).
- Plain `fetch` calls to the Phase 1/3/4/6/7/8/9 API. Responsive layout.

## Phase 11 — Docker

- `Dockerfile`: build stage runs `backend/build.sh` explicitly (same "no hidden build-tool
  magic" pattern as `shelf`, for as long as the project stays on bare `kotlinc` — revisit this
  phase's text if Phase 7 or later triggers a move to Gradle); runtime stage copies compiled
  output + dependency jars.
- Entrypoint handles `PUID`/`PGID`, same as `shelf`.
- `docker-compose.yml`: app service, Postgres service, bind-mounted storage volume, optional
  reverse-proxy service.

## Phase 12 — Hardening pass before calling v1 done

- Audit every recipe-file-touching endpoint against Phase 4 (path safety) — no exceptions.
- Confirm no inotify/filesystem-watch usage anywhere.
- Confirm the only runtime dependencies are ones that were explicitly raised and approved per
  brief section 2 — nothing snuck in silently.
- Manual smoke test of the full v1 feature list from brief section 6: accounts/auth, recipe
  CRUD, URL import against a handful of real recipe sites, meal planning, shopping-list
  generation and combination (including a manual multi-recipe case that should actually
  combine two shared ingredients), responsive UI.

## Deferred to v1.5 / v2 (do not build now, per brief section 6)

- Fuzzy/synonym ingredient-name matching, filename/tag search, nutrition info, print/export
  view, shopping list grouped by aisle/category, HTML-microdata import fallback (v1.5).
- Sharing (public link or user-to-user), multi-household support, pantry/inventory tracking
  (v2/stretch).
