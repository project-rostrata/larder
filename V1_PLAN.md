# larder — v1 implementation plan

Built from `PROJECT_BRIEF.md`. Section 5's open questions are still open — each phase below
that touches one uses the brief's proposed default and flags it, rather than guessing silently.

This plan breaks v1 into ordered phases. Each phase should leave the app in a buildable,
runnable state — no phase depends on later phases to compile.

Dependency policy (brief section 2): start on bare `kotlinc` with zero non-`kotlinx`
dependencies, same as `shelf`. Unlike `shelf`, moving to Gradle or adding a dependency beyond
`kotlinx.*`/the Postgres driver is expected to come up — the Kotlin side is still on exactly
those three jars, but Phase 4a already added something bigger than a jar: a second
runtime/service (the `ingredient-parser/` Python sidecar), the concrete case
`AGENTS.md`'s dependency policy now names explicitly. Propose the specific need before adding
anything else, don't add it by default.

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
  `sessions`, `ingredients`, `ingredient_aliases`, `units`, `unit_conversions`, `recipes`,
  `recipe_ingredients`, `meal_plan_entries`, `shopping_lists`, `shopping_list_items`,
  `shopping_list_item_sources`. Every table is authoritative — none of it is a cache, unlike
  `shelf`'s `file_index`. `ingredients`/`ingredient_aliases`/`units`/`unit_conversions` are
  global, not `owner_id`-scoped — a deliberate exception, see brief section 4.
- `recipes` carries `deleted_at TIMESTAMPTZ NULL` (soft delete — brief section 4). This means
  `meal_plan_entries.recipe_id`'s foreign key is a defensive backstop, not the primary
  protection — the app's own `DELETE /api/recipes/{id}` (Phase 5) never issues a real SQL
  `DELETE` against `recipes` at all, it just sets `deleted_at`. Use `ON DELETE RESTRICT` for
  that FK (not `CASCADE`) so a real hard delete, if one ever happened outside the app, fails
  loudly instead of silently erasing meal-plan history.
- Seed `units` with the initial vocabulary (tsp/tbsp/cup/fl oz/pint/quart/gallon for volume;
  g/kg/oz/lb for mass; a handful of common count units) and their `to_base_factor`/`aliases` —
  this is developer-curated data, not something a migration leaves empty for users to fill in.
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

Originally planned as a single Kotlin phase (a hand-rolled regex parser). Reversed after
research (`docs/decisions.md`) found that running the real `strangetom/ingredient-parser`
Python package as a sidecar clears the bar a hand-rolled parser or a Kotlin port never would —
see brief section 4. Split into two stages; **no other phase's number changes** (matches
`shelf`'s own precedent of annotating scope changes in place rather than renumbering a document
other files already cross-reference by number).

### Phase 4a — Ingredient-parser sidecar (Python) — done

Built, tested, and verified standalone — no dependency on Postgres, auth, or anything else in
this plan. Lives in `ingredient-parser/` (its own README covers the service in full); summary:

- Wraps `strangetom/ingredient-parser`'s `parse_ingredient()` in a small HTTP service —
  `POST /parse` (`{"text": "..."}` in, the library's full structured output as JSON out —
  quantities as `{"numerator", "denominator"}`, never a float) and `GET /health`. Python
  stdlib `http.server`, no Flask — consistent with the Kotlin side's own no-framework
  discipline.
- Model, NLTK tagger data, and embeddings load once at process start, not per request —
  confirmed via measurement to keep per-request latency to single-digit milliseconds once warm.
- NLTK's tagger data is fetched at Docker *build* time, explicitly to a fixed path (not left to
  `nltk.download()`'s own default, which ignores the `NLTK_DATA` env var for where it *writes*,
  confirmed by testing) — the running container needs zero internet access, verified directly
  with `docker run --network none`.
- Tests run real HTTP requests against a real running instance (not just calling the library
  function directly), covering the specific failure modes that motivated this approach over
  regex: size-word separation (`"2 large eggs"` → name `"eggs"`, not `"large eggs"`),
  parenthetical/multiplier package sizes, multiple trailing clauses, quantity appearing after
  the ingredient name.
- **Not done yet, deliberately out of scope for this stage**: nothing in the Kotlin backend or
  `docker-compose.yml` calls this service. See Phase 4b.

### Phase 4b — Kotlin integration — done

- `larder.ingredients.IngredientLineParser` — the interface (`ParsedIngredientLine` out: a
  quantity fraction, a raw unit word, a raw ingredient-name string, notes, always `raw_text`),
  and `larder.ingredients.SidecarIngredientLineParser` — the one implementation, calling the
  sidecar's `POST /parse` via `java.net.http.HttpClient` (already in the JDK, zero new Kotlin
  dependency). Any failure — connection refused, timeout, non-200, unparseable body — degrades
  to an all-null `ParsedIngredientLine` rather than throwing; verified directly by pointing it
  at an unreachable URL, not just assumed.
- The JSON-extraction logic (`parseSidecarResponse`, `extractPrimaryAmount`) is a pure,
  standalone top-level function specifically so it's unit-testable against canned response
  bodies without needing a live sidecar — `backend/test/SidecarIngredientLineParserTest.kt`, 8
  cases, all built from real captured sidecar responses. **This is also where
  `backend/test/TestMain.kt` and `backend/test.sh` first come into existence** — `AGENTS.md`'s
  testing section anticipated Phase 1 or Phase 4 as the likely trigger; Phase 4a turned out to
  be Python, so Phase 4b is where it actually landed.
- **The multi-amount-collapsing design question is resolved**: always take the *first* entry in
  the sidecar's `amount` list (lowest `starting_index` — confirmed the list is already ordered
  this way) as the row's quantity/unit; a `CompositeIngredientAmount` (`"1 cup plus 2
  tablespoons"`) recurses into *its* first sub-amount rather than being summed; a `RANGE` amount
  (`"2-3 cloves"`) uses `quantity_max`, not `quantity` — better to slightly over-buy on a
  shopping list than under-buy. Anything not captured this way is never lost, only
  unstructured: `raw_text` always has the full original line. Summing composite amounts, or
  choosing more cleverly between a package count and its per-unit size, would need the same
  unit-family conversion machinery Phase 8 builds for shopping-list combination — judged not
  worth duplicating here for v1. See `docs/decisions.md`.
- `larder.db.IngredientRow`/`IngredientRepository` (exact match on `ingredients.name` or
  `ingredient_aliases.alias`, case-insensitive; auto-creates and reports whether *this call* is
  what created it — races on the unique index handled explicitly, not just assumed away) and
  `larder.db.UnitRow`/`UnitRepository` (match on `units.name`/`abbreviation`/`aliases`; no
  create — units are seeded, not user-grown). Both global, not `owner_id`-scoped, per brief
  section 4.
- `larder.ingredients.IngredientResolver` ties the two together: `ParsedIngredientLine` in,
  `ResolvedIngredientLine` out (`unitId`, `ingredientId`, `ingredientWasNewlyCreated`, plus the
  quantity fraction and notes carried through unchanged). This is the "is this ingredient
  known?" signal from brief section 5, now real, not just planned.
- **Nothing calls any of this yet** — there's no recipe-creation endpoint for it to be wired
  into (that's Phase 5/6). Verified instead with a temporary, not-committed Kotlin entry point
  run against a real `ingredient-parser` sidecar container and a real Postgres: parsed and
  resolved five real ingredient lines end-to-end into actual rows, confirmed resolving the same
  new ingredient twice is idempotent (same id, correct `ingredientWasNewlyCreated` both times),
  confirmed alias-based unit resolution (`"cups"` → the seeded `cup` unit), and confirmed
  graceful degradation against an unreachable sidecar.
- `docker-compose.yml` wiring for the sidecar service is still Phase 10's concern.

## Phase 5 — Recipe CRUD — done

All straightforward reads/writes against Phase 2's tables, scoped by `owner_id` on every query
— no reconciliation, no lazy rescan, no background scan job, because Postgres is simply the
data, not a cache of something else.

- `GET /api/recipes?tag=...` — listing, filtered by tag. Excludes soft-deleted recipes.
- `GET /api/recipes/{id}` — a single recipe with its ingredients. Unlike listing, does **not**
  exclude soft-deleted recipes — see the `DELETE` bullet below.
- `POST /api/recipes`, `PUT /api/recipes/{id}` — create/edit, writing `recipes` +
  `recipe_ingredients` rows in one transaction (`Database.transaction`/`Transaction`, added this
  phase — nothing before this needed a multi-statement commit). `PUT` fully replaces
  `recipe_ingredients` (delete + re-insert), it's not a patch. Manually-entered ingredient lines
  go through Phase 4b's parser too, same as imported ones will in Phase 6 — confirmed live,
  including that an unreachable sidecar degrades the whole create/update to raw-text-only
  ingredients rather than failing the request. The response includes, per ingredient line,
  whether its `ingredient_id` was newly created or matched an existing row (Phase 4b's note) —
  a separate response shape from `GET`'s (`RecipeWriteResponse` vs `RecipeResponse`) since the
  flag is a fact about *this write*, not a durable property worth reporting on every later read.
  Undisplayed by any UI yet, but present in the API from this phase on.
- `DELETE /api/recipes/{id}` — **soft delete** (brief section 4): sets `deleted_at`, does not
  remove the row or its `recipe_ingredients`. Deleting an already-deleted recipe, updating one,
  or either operation from a non-owner, all return the identical 404 — never a distinct signal
  that reveals a row exists but isn't the caller's. `GET /api/recipes?tag=...` (listing) filters
  `WHERE deleted_at IS NULL`; `GET /api/recipes/{id}` (direct fetch) does not — a historical
  `meal_plan_entries` row still needs to resolve the recipe it references after that recipe's
  been deleted.
- `POST /api/ingredients/{id}/merge-into/{targetId}` — folds a duplicate canonical ingredient
  into another: reassign every `recipe_ingredients`/`shopping_list_items` row referencing `{id}`
  to `{targetId}`, move any aliases over, **reassign `unit_conversions.ingredient_id` rows too**
  (dropping one as a duplicate if `{targetId}` already has a conversion for the same unit pair —
  don't error), then delete `{id}`. One transaction. Mirrors Tandoor's `merge_into` pattern
  (brief section 4) — the recovery path for an auto-created ingredient that turns out to
  duplicate one that already existed. No ownership check on `{id}`/`{targetId}` themselves —
  `ingredients` is global, not `owner_id`-scoped, so any authenticated user can merge any two;
  self-merge is rejected (400), a nonexistent or already-merged-away id is 404.
- **`Router.kt` gained a `put()` method this phase** — it only had get/post/delete before;
  `PUT /api/recipes/{id}` is the first route that needed it.

## Phase 6 — Recipe URL import — done

- `POST /api/recipes/import { url }`: fetch, extract JSON-LD, map onto the same `RecipeRequest`
  shape Phase 5's create/update already validate and persist through — import doesn't duplicate
  that path, it feeds it. `larder.recipeimport.extractRecipeFromHtml` (pure, no I/O, unit
  tested against a real captured food.com fixture plus representative `@graph`/multi-typed/
  `HowToSection` cases) maps `name`, `recipeIngredient`, `recipeInstructions` (flattening
  `HowToStep` and nested `HowToSection` objects), `recipeYield` → `servings`/`servings_text`
  (best-effort number extraction; the full text is always kept, since real yield strings like
  "1 pound per serving" aren't clean serving counts), `prepTime`/`cookTime`/`totalTime` (ISO
  8601 durations, `java.time.Duration.parse` — zero new dependency), and `keywords` → `tags`.
  Every `recipeIngredient` string runs through Phase 4b's parser — confirmed (brief section 4,
  via `recipe-scrapers`' own source) to always be raw unparsed strings, so this parser is
  load-bearing here, not a fallback. No image field is read or stored, per brief section 2.
- **SSRF guard, required by `SECURITY.md`**: `larder.recipeimport.validateImportUrl` rejects
  non-HTTP(S) schemes and resolves the host to reject loopback/link-local (covers the
  `169.254.169.254` cloud-metadata address)/RFC 1918 private addresses, checked before the
  initial fetch. Redirects are followed manually
  (`larder.recipeimport.fetchRecipeHtml`), not via `HttpClient`'s own redirect handling,
  specifically so every hop gets re-validated the same way — a URL that passes the guard once
  could still redirect to an internal address, and only re-checking each hop actually closes
  that.
- **Verified against a real, live recipe page, not just synthetic fixtures** — fetched,
  extracted, parsed every ingredient through the real sidecar, and persisted correctly end to
  end, messy real-world whitespace (`"2   teaspoons    unsalted butter"`) and an imprecise
  `recipeYield` (`"1 pound per serving"`) included. **This resolves the trigger question this
  phase was written to answer: JSON-LD coverage was not too thin** for the one real site
  actually tested — the `jsoup`/HTML-parsing dependency from brief section 4 was not proposed,
  and stays not-pre-approved. Revisit only if a real site is actually hit that this can't
  handle, not preemptively.

## Phase 7 — Meal planning (done)

- `GET /api/meal-plan?from=...&to=...`, `POST /api/meal-plan`, `DELETE /api/meal-plan/{id}` —
  CRUD for `meal_plan_entries` (date, meal slot, recipe id, servings multiplier). `POST` must
  verify the client-supplied `recipe_id` resolves to a recipe owned by the authenticated user
  before creating the entry — the FK only proves the recipe exists, not that it's theirs (see
  `AGENTS.md`'s ownership rule).
- Servings multiplier per entry, recipe row itself never mutated — this is now a settled
  decision (brief section 4's "Recipe scaling"), not an open question to re-flag. Multiplying a
  recipe's `servings` (the numeric field) by this factor, never `servings_text` (display-only),
  is what determines each entry's actual scaled ingredient quantities.
- As built: `meal_slot` is a fixed set (`breakfast`/`lunch`/`dinner`/`snack`), validated in the
  handler; list requires `from`/`to` (inclusive, max 366 days) and returns entries joined with
  `recipeTitle`/`recipeDeleted`; an unknown-or-not-yours `recipe_id` is one 403 (no existence
  leak), a soft-deleted own recipe is 422. No update endpoint — moving an entry is delete +
  create, per this phase's original scope.

## Phase 8 — Shopping list generation

- `POST /api/shopping-lists { recipe_ids } | { meal_plan_from, meal_plan_to }` — the
  `recipe_ids` form must filter to recipes owned by the authenticated user (same ownership rule
  as Phase 7's `recipe_id`, `AGENTS.md`) before gathering anything, not just trust the list.
  Gathers every ingredient across the selected recipes (a query against `recipe_ingredients`,
  scaled by each meal-plan entry's servings multiplier where applicable), and combines per
  brief section 4's rules:
  - Same `ingredient_id` + compatible unit (same `unit_id`, same dimension via
    `to_base_factor`, or a matching `unit_conversions` row — checked in that order, no
    multi-hop chaining) → combine. Two unresolved lines with identical normalized `raw_text`
    also combine. Everything else stays a separate `shopping_list_items` row.
  - For every recipe that contributed to a combined item, insert one
    `shopping_list_item_sources` row: a snapshot of that recipe's title, the ingredient line's
    `raw_text`, and its quantity/unit *as scaled but before unit conversion* — this is what lets
    a later view answer "which recipes want this, and how much did each call for" (e.g. a
    combined "1 lb flour" item whose sources show "4 cups" from one recipe and "3 tbsp" from
    another). Get this right in this phase even though no UI surfaces it yet — brief section 4
    calls this a stated requirement, not a nice-to-have.
  - Persist the result as a `shopping_lists`/`shopping_list_items`/`shopping_list_item_sources`
    row set — computed once at generation time, not recomputed live on every read.
- `GET /api/shopping-lists/{id}`, `PATCH /api/shopping-lists/{id}/items/{item_id}` (check off /
  edit / delete), `POST /api/shopping-lists/{id}/items` (add a manual item) — the list is a
  living, editable document once generated, not a one-shot computation.
- `POST /api/shopping-lists/{id}/items/merge { item_ids }` — merges two or more existing items
  into one: unions their `shopping_list_item_sources` rows onto the surviving item, sums
  quantities when units are compatible. This is the explicit, accepted workaround for whatever
  automatic combination misses (brief section 4) — the exact response shape for an
  incompatible-unit merge is an implementation detail to work out in this phase, not something
  the brief prescribes; the UI that would call this endpoint is deferred (brief section 5).

## Phase 9 — Frontend (VanJS)

Split into two stages like Phase 4 — 9a (done, this pass) covers everything the Phase 1/3/5/6
API already supports; 9b (placeholder, content TBD) covers the meal planner and shopping list
views once Phases 7/8 land. No document-wide renumbering — later phases keep their numbers.

### Phase 9a — Auth + recipe views (done)

- Vendored `van.js` 1.6.1, no bundler — same as `shelf`.
- Query-param client-side routing (`?view=...&id=...&tag=...`) on the single `/` route,
  matching `shelf`'s own `router.js` pattern exactly (explicit human choice) — no SPA-fallback
  logic needed in the static file server since every view lives at path `/`.
- New `StaticFileHandler`/`Router.serveStatic()` on the Kotlin side: unmatched GET requests
  outside `/api/` fall through to serving `LARDER_FRONTEND_DIR` (new required env var).
- Views built: `Login`/`Register` (adapted from `shelf`'s near-identical originals, wordmark/
  copy changed only), `RecipeList` (card grid, not a table — explicit human choice; debounced
  tag-filter input, import-from-URL and new-recipe actions), `RecipeDetail` (raw_text ingredient
  display per the brief's "what you typed/imported is what you see" principle, numbered
  instructions, edit/delete actions), `RecipeForm` (shared create/edit: scalar fields, dynamic
  ingredient/instruction rows, comma-separated tags input).
- Shared components: `TopBar`, `Toast`, `ImportDialog` (adapted from `shelf`'s `MkdirDialog`
  pattern), `ConfirmDialog` (new — a generic confirm/cancel modal `shelf` had no equivalent of,
  since all its dialogs were task-specific), `DialogHost` (dispatches on `state.activeDialog`).
- Plain `fetch` calls to the Phase 1/3/5/6 API only — Phase 9a doesn't touch meal planning or
  shopping lists, since those endpoints don't exist yet.
- Two things the backend already supports but this stage doesn't design a UI for (brief
  section 5, both explicitly deferred): surfacing whether a recipe's ingredients matched a
  known ingredient or got auto-created, and interactive servings-scaling. Both are a later,
  separate pass once there's a UX design to build against.

### Phase 9b — Meal planner + shopping list views (placeholder, not started)

- Meal planner (calendar-ish date/slot grid), shopping list view (checkable items, manual add,
  a UI for Phase 8's manual item-merge endpoint). Depends on Phases 7 and 8 existing first.

## Phase 10 — Docker (done)

- `docker/Dockerfile`: two-stage build. Stage 1 runs `backend/build.sh` explicitly (same "no
  hidden build-tool magic" pattern as `shelf`, still on bare `kotlinc`), on
  `eclipse-temurin:25.0.4_7-jdk-alpine`. Stage 2 is the runtime — **diverges from this plan's
  original sketch**: rather than a separate `ingredient-parser` service/image, the human asked
  for a single deployable image, so the runtime stage bundles the compiled backend, the
  frontend, and the Python sidecar together, based on `python:3.12-slim` (glibc, matching
  `ingredient-parser/Dockerfile`'s own already-verified base — Alpine/musl would force
  `numpy`/`regex` to compile from source instead of installing manylinux wheels) with
  `openjdk-21-jre-headless` added via `apt`.
- `docker/entrypoint.sh`: starts the sidecar and the app as two direct child processes (no
  supervisor dependency), with the app's own exit — not the sidecar's — ending the container;
  see `docs/decisions.md` for the reasoning.
- `docker/docker-compose.yml`: `app` service (built from the Dockerfile above) + `postgres`
  service (official image, data via a named volume). **No bind-mounted storage volume, no
  `PUID`/`PGID` entrypoint** — see brief section 8; there's no user-facing filesystem content to
  manage. No reverse-proxy service — not asked for.

## Phase 11 — Hardening pass before calling v1 done

- Audit every query that touches `recipes`, `recipe_ingredients`, `meal_plan_entries`,
  `shopping_lists`, `shopping_list_items`, or `shopping_list_item_sources` for an `owner_id` (or
  joined-through-owner) check — this is larder's equivalent of `shelf`'s path-safety audit: the
  one property that must never have an exception. Specifically check every endpoint that
  accepts a `recipe_id` from the client (Phase 7's meal-plan create, Phase 8's shopping-list
  generation) actually verifies ownership of that referenced recipe, not just that it exists —
  see `AGENTS.md`'s ownership rule. `ingredients`, `ingredient_aliases`, `units`,
  and `unit_conversions` are the **one deliberate exception** (brief section 4, global
  instance-wide vocabulary) — confirm access to those four is appropriately unscoped, not that
  it's missing an `owner_id` check it was never supposed to have.
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
