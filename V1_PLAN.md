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

Build and unit-test this standalone, before Phase 5 or Phase 6 depend on it — this is larder's
equivalent of `shelf`'s Phase 4 (path safety) in risk profile, even though the subject matter
is completely different.

- Input a raw string (`"2 1/2 cups all-purpose flour, sifted"`), output a quantity fraction
  (`quantity_numerator`/`quantity_denominator`), a resolved `unit_id` (matched against
  `units.name`/`abbreviation`/`aliases`), a resolved `ingredient_id` (matched against
  `ingredients`/`ingredient_aliases`, exact match after normalization — auto-creating a new
  canonical `ingredients` row on a miss, per brief section 4), `notes` (trailing comma-clause),
  and always `raw_text`. Cover mixed numbers, simple fractions, and decimals for the quantity
  (a decimal input still converts to an exact reduced fraction — e.g. `2.5` → `5/2`). Unparsed
  lines fall back to null quantity/unit/ingredient with just `raw_text` — never throw on a line
  that doesn't fit the pattern.
- The create/import call this feeds into (Phase 5/6) must be able to report, per ingredient
  line, whether its `ingredient_id` was matched to a pre-existing row or created new during that
  call — this falls out of the get-or-create lookup itself, no extra state needed. Don't lose
  this signal on the way out of the parser/lookup step; it's the hook brief section 5's deferred
  "is this ingredient known?" UI will eventually use.
- Write real test cases from real recipe sites' ingredient lists, not just synthetic examples —
  this module's quality is what makes or breaks the shopping-list feature. Expect meaningfully
  lower accuracy than the CRF-based parsers researched for this design (Mealie's own, and
  `strangetom/ingredient-parser`'s 94.9%) — that's an accepted tradeoff (see brief section 4),
  not a bar this module needs to clear.

## Phase 5 — Recipe CRUD

All straightforward reads/writes against Phase 2's tables, scoped by `owner_id` on every query
— no reconciliation, no lazy rescan, no background scan job, because Postgres is simply the
data, not a cache of something else.

- `GET /api/recipes?tag=...` — listing, filtered by tag.
- `GET /api/recipes/{id}` — a single recipe with its ingredients.
- `POST /api/recipes`, `PUT /api/recipes/{id}` — create/edit, writing `recipes` +
  `recipe_ingredients` rows in one transaction. Manually-entered ingredient lines go through
  Phase 4's parser too, same as imported ones. The response includes, per ingredient line,
  whether its `ingredient_id` was newly created or matched an existing row (Phase 4's note) —
  undisplayed by any UI yet, but present in the API from this phase on.
- `DELETE /api/recipes/{id}` — **soft delete** (brief section 4): sets `deleted_at`, does not
  remove the row or its `recipe_ingredients`. `PUT` on an already-deleted recipe returns 404.
  `GET /api/recipes?tag=...` (listing) filters `WHERE deleted_at IS NULL`; `GET
  /api/recipes/{id}` (direct fetch) does not — a historical `meal_plan_entries` row still needs
  to resolve the recipe it references after that recipe's been deleted.
- `POST /api/ingredients/{id}/merge-into/{targetId}` — folds a duplicate canonical ingredient
  into another: reassign every `recipe_ingredients`/`shopping_list_items` row referencing `{id}`
  to `{targetId}`, move any aliases over, **reassign `unit_conversions.ingredient_id` rows too**
  (dropping one as a duplicate if `{targetId}` already has a conversion for the same unit pair —
  don't error), then delete `{id}`. Mirrors Tandoor's `merge_into` pattern (brief section 4) —
  the recovery path for an auto-created ingredient that turns out to duplicate one that already
  existed.

## Phase 6 — Recipe URL import

- `POST /api/recipes/import { url }`: fetch with `java.net.http.HttpClient`, extract
  `<script type="application/ld+json">` blocks via regex, parse with
  `kotlinx.serialization.json`, find a `Recipe`-typed object (top-level or inside `@graph`),
  map its fields (`name`, `recipeIngredient`, `recipeInstructions`, `recipeYield` → `servings`/
  `servings_text`, `prepTime`/`cookTime`/`totalTime` as ISO 8601 durations, `keywords`) onto
  `recipes`/`recipe_ingredients` rows, running every `recipeIngredient` string through Phase 4's
  parser — `recipeIngredient` is confirmed (brief section 4, via `recipe-scrapers`' own source)
  to always be raw unparsed strings, so this parser is load-bearing here, not a fallback.
  No image field is read or stored, per brief section 2. Same as Phase 5, the response flags
  which ingredient lines resolved to a new vs. existing canonical ingredient.
- If this phase reveals that JSON-LD coverage is too thin across real-world recipe sites to be
  useful, that's the trigger point for raising the `jsoup`/HTML-parsing dependency question
  from brief section 4 — don't silently add it, surface it first.

## Phase 7 — Meal planning

- `GET /api/meal-plan?from=...&to=...`, `POST /api/meal-plan`, `DELETE /api/meal-plan/{id}` —
  CRUD for `meal_plan_entries` (date, meal slot, recipe id, servings multiplier). `POST` must
  verify the client-supplied `recipe_id` resolves to a recipe owned by the authenticated user
  before creating the entry — the FK only proves the recipe exists, not that it's theirs (see
  `AGENTS.md`'s ownership rule).
- Servings multiplier per entry, recipe row itself never mutated — this is now a settled
  decision (brief section 4's "Recipe scaling"), not an open question to re-flag. Multiplying a
  recipe's `servings` (the numeric field) by this factor, never `servings_text` (display-only),
  is what determines each entry's actual scaled ingredient quantities.

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

- Vendor `van.js`, no bundler — same as `shelf`.
- Views: login/register, recipe list (tag filter) + recipe detail/edit, import-from-URL form,
  meal planner (calendar-ish date/slot grid), shopping list view (checkable items, manual add).
- Plain `fetch` calls to the Phase 1/3/5/6/7/8 API. Responsive layout.
- Two things the backend already supports but this phase doesn't need to design yet (brief
  section 5, both explicitly deferred): surfacing whether a recipe's ingredients matched a
  known ingredient or got auto-created, and a UI for Phase 8's manual item-merge endpoint. Build
  the v1 views without them; both are a later, separate pass once there's a UX design to build
  against.

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
