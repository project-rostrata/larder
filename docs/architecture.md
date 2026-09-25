# Architecture — as built

This is the living description of what actually exists in the `larder` codebase, kept in sync
as each phase of `V1_PLAN.md` lands. `PROJECT_BRIEF.md` is the target design and doesn't
change; `V1_PLAN.md` is the fixed order of work and doesn't change either. This file is the
current reality, and gets rewritten in place — not appended to — every time that reality
changes.

## Status

**Phases 0–6 are done.** Repo scaffold and planning docs; a backend skeleton (hand-rolled
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
`IngredientResolver`, `IngredientRepository`/`UnitRepository`); real recipe CRUD —
`GET/POST /api/recipes`, `GET/PUT/DELETE /api/recipes/{id}`,
`POST /api/ingredients/{id}/merge-into/{targetId}`; and now recipe URL import —
`POST /api/recipes/import` (`larder.recipeimport`: `JsonLdRecipeParser`, an SSRF guard, and a
manually-redirect-following fetcher) — feeding the same create path Phase 5 already built,
rather than a separate one.

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
Phase 6 was verified against a **real, live recipe page** (this sandbox's outbound network
could only reach a handful of real recipe sites past their bot-protection — food.com was one)
— fetched, extracted, ran every ingredient through the real sidecar, and persisted correctly,
messy whitespace and an imprecise `recipeYield` included; separately confirmed the SSRF guard
rejects loopback and cloud-metadata addresses and a bad scheme (400), a reachable URL with no
Recipe JSON-LD returns 422, and a malformed request body returns 400 — all through the real
HTTP API, not just unit tests.

**Phase 9a (frontend: auth + recipe views) is done.** A VanJS UI now exists in `frontend/` —
see its own section under Repo layout. `Router.kt` gained `serveStatic()`/`StaticResult`, and a
new `api/StaticFileHandler.kt` serves it: unmatched GET requests outside `/api/` fall through to
the frontend directory (`LARDER_FRONTEND_DIR`, a new required env var), everything under
`/api/` is untouched. Verified live: started a real `postgres:18.6-alpine` container, the real
`ingredient-parser` sidecar image, and the compiled backend pointed at the real `frontend/`
directory; confirmed every static asset serves with the right content-type (`index.html`,
`app.js`, every `src/**/*.js`, `lib/van-1.6.1.js`, `style.css`, `favicon.svg`), a non-existent
path 404s rather than silently falling back to `index.html`, and `/api/health` still routes to
the API rather than being swallowed by the static fallback. Exercised the full recipe lifecycle
the UI drives through the real HTTP API — register, create (ingredients routed through the real
sidecar: `"2 large eggs"` correctly stripped its size word from the ingredient name, `"2 cups
flour, sifted, divided"` correctly captured its multi-clause notes), list, tag-filter, get,
`PUT`-replace, soft-delete, confirming the list excludes a deleted recipe while direct-fetch
still resolves it (`RecipeGetHandler`'s documented does-not-filter-`deleted_at` behavior).
Every frontend `.js` file passed a `node --check` syntax pass. **Not verified in an actual
rendered browser** — no headless-browser tooling (Playwright/jsdom/Selenium) was available in
this environment and installing one wasn't requested, so the reactive VanJS wiring (the
`display:contents` grid-children pattern in particular) was verified by hand-tracing VanJS's
dependency-tracking semantics and by reading the vendored source directly, not by watching it
render. Two real issues were caught and fixed before shipping: `RecipeList.js`'s `Grid()`
originally wrapped card children in a plain `<div>`, which would have broken the CSS Grid layout
(the wrapper becomes the grid item, not each card) — fixed with a `display:contents` wrapper, the
same fix applied in `RecipeForm.js`'s `DynamicRows()`; and `RecipeList()` was calling
`refreshRecipeList()` itself even though `router.js`'s `navigate()`/`applyUrlToState()` already
triggers it on every navigation to that view, causing a redundant double-fetch on every visit —
removed, view components now only render state, `router.js` owns loading it.

**Phase 10 (Docker packaging) is done.** `docker/Dockerfile`, `docker/entrypoint.sh`, and
`docker/docker-compose.yml` now exist — see their own section under Repo layout for what each
does, and `docs/decisions.md` for why this bundles the `ingredient-parser` sidecar into the
app's own image rather than running it as a separate compose service (a human-directed
deviation from this plan's original sketch, not a default the app chose on its own). Verified
live: `docker compose -f docker/docker-compose.yml up --build` brought up a real two-container
stack (the bundled app image + Postgres); confirmed migrations apply automatically on first
start, the frontend and API both serve correctly, and a real recipe create routes through the
bundled sidecar over loopback with the same parsing behavior already verified in Phase 9a;
killed the sidecar's process inside the running container and confirmed the container stayed up,
`/api/health` kept responding, and a subsequent create correctly degraded to an all-null
unresolved ingredient rather than erroring (this is `entrypoint.sh`'s deliberate divergence from
`shelf`'s multi-process entrypoint pattern — only the app's own exit ends the container, not the
sidecar's, see `docs/decisions.md`); confirmed a clean `docker stop` exits promptly (code 143,
the normal SIGTERM result) with no forced kill needed.

**Phase 7 (meal planning API) is done** — `GET/POST /api/meal-plan`, `DELETE
/api/meal-plan/{id}`. A meal plan is one flat list per user: each entry is a recipe, an
optional free-text label, and a servings multiplier — no dates or meal slots (migration `0003`
removed a first cut's `plan_date`/`meal_slot`). Verified live against a real Postgres through
the HTTP API, including the upgrade path (a pre-`0003` database with an existing entry
migrated cleanly, entry intact): label trimming/blank-to-null/length cap, insertion ordering,
cross-user isolation, a not-owned and a nonexistent `recipe_id` both returning the identical
403, and the soft-delete contract (see below).

**Phase 9b's meal-planner UI is done, and soft-delete filtering and display formatting moved into
the API.** Following the human's direction that the API is the source of truth and the UI stays
dumb (now in `AGENTS.md`):

- **Soft-deleted recipes:** filtered in `RecipeRepository.findById` (so `GET /api/recipes/{id}`
  404s on them, like update/delete) and in the meal-plan list query. Their entries stay in the
  table but are never returned, and planning one gets the same 403 as an unknown id.
- **Formatting:** `api/Display.kt` formats times and servings. Recipe responses carry a
  `display` object (`servings`, `totalTime`, `details`), and meal-plan entries carry
  `servingsDisplay` ("6 servings" scaled, or "×2" when the recipe has no numeric yield).
- **Servings math:** `POST /api/meal-plan` accepts `servings` and converts it to the multiplier
  itself. `frontend/src/format.js` was deleted.

**Verified in a real browser for the first time**: headless Chrome (a Playwright-cached
`chrome-headless-shell` binary already on the VM) driven over the DevTools protocol with Node's
built-in WebSocket, no new dependencies. 16 checks across registering, API-formatted card and
detail text, the add dialog in both modes, the success notice, the meal-plan list, remove,
soft-delete filtering, typing in the recipe form (the earlier "not editable" bug, now confirmed in a
real browser too), no horizontal overflow at 390px, and zero console errors. This run caught a
Phase 9a bug: on a logged-out page load the router fetched `/api/recipes` before auth was known,
showing "Missing or invalid session" on the login page. Loading now waits for a known user.

## System shape

```
Browser (VanJS UI, frontend/ — auth + recipe views done, Phase 9a; meal planner/shopping
          list views not yet built, Phase 9b)
      |
      v
App server (Kotlin, JDK stdlib HTTP, REST/JSON API + static file serving)
      |                       |
      v                       v
  Postgres (source of truth   Ingredient-parser sidecar (Python, ingredient-parser/) — bundled
   for everything,             into the SAME container/image as the app server as of Phase 10,
   separate container)         reachable only over loopback, not a separate compose service
```

Full rationale in `PROJECT_BRIEF.md` §3–4. All three arrows are real now — recipe creation/
update calls the sidecar, confirmed live, and the browser is served the real UI by the same app
server rather than a separate static host. As of Phase 10, `docker/docker-compose.yml` runs the
whole thing as two containers: `app` (backend + frontend + sidecar, one built image) and
`postgres` (official image, named volume) — see `docs/decisions.md` for why the sidecar is
bundled into the app's image rather than run as its own compose service, a deliberate deviation
from this plan's original sketch made at the human's explicit direction.

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
                                 .get/.post/.put/.delete for JSON routes. put() added Phase 5 —
                                 PUT /api/recipes/{id} was the first route that needed it.
                                 serveStatic() added Phase 9a — unmatched GET requests outside
                                 /api/ fall through to it instead of 404ing
        StaticFileHandler.kt     Phase 9a — serves LARDER_FRONTEND_DIR; simpler than shelf's
                                  equivalent since larder has no per-user filesystem content to
                                  path-safety-guard against, just a fixed set of app files (still
                                  canonicalizes + checks containment defensively)
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
        RecipeRequest.kt         request DTOs, shared by create/update/import; toFields()
                                   mapper onto RecipeFields, same shared-by-all-three principle
        RecipeResponse.kt         response DTOs + RecipeRow.toResponse()/
                                    PersistedRecipe.toWriteResponse() mappers — Read and Write
                                    response shapes are deliberately separate types (see
                                    docs/decisions.md)
        RecipeValidation.kt        validateRecipeRequest(), shared by create/update/import
        RecipeIngredientResolution.kt  resolveIngredientLines() — the parser+resolver call per
                                         raw line, shared by create/update/import, order-preserving
        RecipesListHandler.kt      GET /api/recipes?tag=... — excludes soft-deleted
        RecipeGetHandler.kt         GET /api/recipes/{id} — does NOT exclude soft-deleted
        RecipeCreateHandler.kt        POST /api/recipes
        RecipeUpdateHandler.kt         PUT /api/recipes/{id} — full replace, not a patch; 404
                                         on a deleted/missing/not-owned recipe, all identical
        RecipeDeleteHandler.kt           DELETE /api/recipes/{id} — soft delete; already-deleted
                                           is 404, not idempotent-200
        RecipeImportHandler.kt            POST /api/recipes/import — fetches + extracts, then
                                            feeds the same validate/resolve/persist path as
                                            create; 400 for a URL we won't fetch (bad scheme,
                                            SSRF-guard rejection, bad request body), 422 for a
                                            URL we fetched but couldn't use (no Recipe JSON-LD,
                                            or its data fails normal recipe validation)
        Display.kt                  display formatting (times, servings) — the API formats, the
                                       UI renders; see AGENTS.md
        MealPlanDto.kt              Phase 7 — request/response DTOs
        MealPlanHandlers.kt          Phase 7 — GET /api/meal-plan (whole list, soft-deleted
                                       recipes filtered out), POST /api/meal-plan (optional label,
                                       trimmed, max 100 chars; `servings` or `servingsMultiplier`;
                                       unknown, deleted, or not-yours recipe_id is one 403),
                                       DELETE /api/meal-plan/{id}
        IngredientMergeHandler.kt          POST /api/ingredients/{id}/merge-into/{targetId} — no
                                             ownership check, ingredients are global
      recipeimport/
        JsonLdRecipeParser.kt      extractRecipeFromHtml() — pure, no I/O; finds a Recipe
                                     object (top-level, in an array, or inside @graph) among
                                     every <script type="application/ld+json"> block on the
                                     page and maps its fields onto ImportedRecipe. Safe casts
                                     (as?) throughout, not the throwing .jsonObject/.jsonArray
                                     properties -- the JsonNull lesson from Phase 4b, applied
                                     proactively here rather than caught by a failing test
        ImportUrlValidator.kt       validateImportUrl() -- the SSRF guard SECURITY.md requires:
                                     rejects non-HTTP(S) schemes and any host resolving to a
                                     loopback/link-local (covers 169.254.169.254)/RFC 1918
                                     address
        RecipeUrlFetcher.kt          fetchRecipeHtml() -- follows redirects manually, one hop
                                       at a time, re-running validateImportUrl() on every hop
                                       (HttpClient's own redirect handling would bypass that),
                                       capped at 5 hops
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
        MealPlanEntryRow.kt / MealPlanRepository.kt  Phase 7 — owner_id-scoped; rows joined with
                                                      recipes for title + deleted flag, so
                                                      entries for soft-deleted recipes still
                                                      render; insertion order
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
      JsonLdRecipeParserTest.kt   8 cases -- one built from a real fetched food.com page
                                    (labeled as such in the file), the rest representative-
                                    synthetic (@graph, multi-typed @type, HowToSection) built
                                    from documented schema.org patterns, honestly distinguished
                                    from the real one rather than blurred together
      DisplayTest.kt              3 cases — time, number, and servings formatting
      ImportUrlValidatorTest.kt   7 cases, all using IP-literal URLs (127.0.0.1, 10.x, a real
                                    public IP for the negative case) so the suite stays
                                    hermetic -- an IP literal resolves locally, no real DNS
                                    lookup, unlike a hostname would need
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
  frontend/                    Phase 9a — vendored van.js, no bundler; served by
                                 StaticFileHandler, not a separate static host
    index.html                   single entry point; every view lives at path / (query-param
                                   routing, see src/router.js)
    app.js                        entry point: mounts Root(), which switches on
                                   state.authChecked/state.user/state.view — same shape as
                                   shelf's own app.js Root()
    style.css                     shelf's exact design tokens/component classes, plus
                                    recipe-specific ones (card grid, detail, form, dynamic rows)
    favicon.svg                   larder's own open-book icon (shelf's single-filled-path
                                    favicon pattern, larder's own design)
    lib/van-1.6.1.js               vendored verbatim from shelf
    src/
      state.js                       top-level van.state() values, no store/reducer abstraction
      api.js                          fetch wrapper, one function per endpoint (Phase 1/3/5/6
                                        API; meal-plan calls added in Phase 9b)
      router.js                       query-param routing (?view=&id=&tag=), matching shelf's
                                        own router.js pattern exactly (explicit human choice);
                                        navigate()/applyUrlToState() own triggering data loads,
                                        views only render state
      recipes.js                      refreshRecipeList()/loadRecipe() — the data-loading
                                        functions router.js calls into (only once a user is known)
      mealPlan.js                     refreshMealPlan()/addToMealPlan()/removeMealPlanEntry()
      icons.js                        minimal inline-SVG icon set, same icon() helper as shelf
      vanHelpers.js                   emptyNode(), ported from shelf (verified against the
                                        vendored van.js source that it still safely no-ops)
      components/
        TopBar.js                       reused across every logged-in view (list/detail/form),
                                          unlike shelf's, which only ever appeared in one view
        Toast.js                        errors and (Phase 9b) success notices; newest wins
        ImportDialog.js                  adapted from shelf's MkdirDialog.js pattern
        ConfirmDialog.js                 new — generic confirm/cancel modal; shelf had no
                                           equivalent since its dialogs were all task-specific
        DialogHost.js                    dispatches state.activeDialog to the right dialog
        AddToMealPlanDialog.js           optional label + servings (or batch multiplier when the
                                           recipe has no numeric yield), sent as entered
        RecipeCard.js                     used by the recipe-list card grid
      views/
        Login.js / Register.js            adapted from shelf's near-identical originals
        RecipeList.js                      card grid (explicit human choice over a table),
                                             debounced tag filter, import/new actions
        RecipeDetail.js                    raw_text ingredient display, numbered instructions,
                                             edit/delete actions
        RecipeForm.js                      shared create/edit form: scalar fields + dynamic
                                             ingredient/instruction rows (display:contents
                                             wrapper, same pattern as RecipeList's card grid)
        MealPlan.js                        Phase 9b — the meal-plan list: title, label chip,
                                             API-formatted servings, remove
  db/
    migrations/
      0001_initial_schema.sql   all v1 tables, indexes, and constraints (PROJECT_BRIEF.md §7)
      0002_seed_units.sql       21 starter units (volume/mass/count) with conversion factors
      0003_meal_plan_labels.sql  meal plan becomes a flat list: drops plan_date/meal_slot, adds label
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
  docker/                      Phase 10
    Dockerfile                   two-stage build: stage 1 compiles the Kotlin backend
                                   (eclipse-temurin JDK Alpine, pinned kotlinc, same pattern as
                                   shelf); stage 2 is the runtime, python:3.12-slim (glibc, same
                                   base ingredient-parser/Dockerfile already verified) +
                                   openjdk-21-jre-headless added via apt, bundling the compiled
                                   backend, frontend/, and the sidecar all into one image — a
                                   deliberate deviation from this plan's original separate-
                                   service sketch, see docs/decisions.md
    entrypoint.sh                 starts the sidecar and the app as two direct child processes,
                                    no supervisor dependency; only the app's own exit ends the
                                    container, not the sidecar's (verified live: killing the
                                    sidecar leaves the container running and the app serving,
                                    degrading gracefully) — deliberately diverges from shelf's
                                    standalone-entrypoint.sh precedent on this one point, see
                                    docs/decisions.md
    docker-compose.yml             app service (builds the Dockerfile above) + postgres service
                                     (official image, named volume); no PUID/PGID handling, no
                                     bind-mounted storage volume — no user-facing filesystem
                                     content exists to manage
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

**Frontend**: zero package-manager dependencies — `van.js` 1.6.1 is vendored verbatim into
`frontend/lib/`, same as `shelf`'s frontend. No bundler, no npm, no build step; the browser
loads `app.js` and its imports as native ES modules directly.

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
| `LARDER_INGREDIENT_PARSER_URL` | yes | — (`run.sh` defaults it to `http://localhost:8000` for local dev; the Docker image defaults it to `http://127.0.0.1:8000` since the sidecar is bundled into the same container as of Phase 10) |
| `LARDER_FRONTEND_DIR` | yes | — (`run.sh` defaults it to `../frontend` for local dev) |

`ingredient-parser/` has its own separate, small config surface — see its own README.md
(currently just `INGREDIENT_PARSER_PORT`, default `8000`). Not part of the table above; it's a
different process with its own env-var namespace.

## Not built yet

Phase 8 (shopping-list generation), and Phase 9b (their frontend
views). See `V1_PLAN.md` for the phase order.
