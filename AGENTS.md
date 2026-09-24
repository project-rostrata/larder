# AGENTS.md — conventions for anyone (human or AI) writing code in this repo

This file is for coding conventions and patterns — the "how" of writing larder's code.
`PROJECT_BRIEF.md` is the "what" (target design, non-negotiable constraints), `V1_PLAN.md` is
the "in what order," and `docs/decisions.md` is the append-only log of *why* each convention
below was chosen over the alternatives. If anything here ever conflicts with the brief, the
brief wins — flag the conflict rather than silently picking one.

Most of these conventions are inherited directly from `shelf`, this project's predecessor and
same-ethos sibling app — they're repeated here rather than just referenced so this repo is
self-contained. Where larder's conventions diverge from `shelf`'s, it's called out explicitly.

## Language & style

- `val` over `var`. Reach for `var` only when there's a real, local reason (a loop accumulator,
  etc.), never as a default.
- Non-null types by default. Avoid `!!` — if you're reaching for it, either the type shouldn't
  be nullable or the code needs an explicit null check with a real error path.
- `data class` for every value object — DB rows and API DTOs alike (see layering below).
- Classes stay small and single-purpose. If a class's responsibility needs "and" to describe
  it, split it.
- Minimal public surface: `private` by default, widen only what callers actually need.
- No user-content file I/O anywhere in this app — recipes, meal plans, and shopping lists are
  all Postgres rows (see `PROJECT_BRIEF.md` section 4). Where the app does touch files at all
  (reading migration SQL, serving static frontend assets), plain NIO.2
  (`java.nio.file.Path`/`Files`) is fine; there's no path-safety layer to keep consistent with
  here the way `shelf` had.

## Layering: database objects vs. API objects

Database-row classes and API request/response classes are separate `data class` hierarchies,
never shared. Convert between them with small, explicit mapper functions (e.g.
`fun RecipeRow.toApiRecipe(): ApiRecipe`) kept next to whichever side owns the conversion. This
keeps a schema change from silently reshaping the public API, and vice versa.

**No AutoValue/AutoFactory.** Both exist to give Java the immutable-value-object ergonomics
Kotlin's `data class` already provides for free. See `shelf`'s `docs/decisions.md` for the full
reasoning if it needs re-litigating here (it shouldn't).

## Dependency injection

**No DI framework.** Wire the object graph by hand, once, in a single composition root
(`Main.kt`'s `main()`, or a small `Wiring.kt` it calls into). Every service is constructed
there and handed to whatever needs it via its constructor.

**Singletons:** most services in this app (auth, recipe storage, the ingredient parser) are
conceptually one-per-process. Implement that as *one instance, constructed once in
the composition root, passed via constructor* — not a Kotlin `object` declaration. Reserve
`object` for genuinely stateless things — pure helper functions, constants.

## Error handling

Handlers signal outcomes with a sealed result type, not exceptions, for expected failure cases:

```kotlin
sealed class ApiResult<out T>
data class Ok<T>(val value: T) : ApiResult<T>()
data class Err(val status: Int, val code: String, val message: String) : ApiResult<Nothing>()
```

Reserve thrown exceptions for genuinely unexpected/programmer errors, caught once at the top of
the router and turned into a 500.

Every error response is the same JSON shape:

```json
{"error": {"code": "NOT_OWNED", "message": "Recipe does not belong to the authenticated user"}}
```

Status codes are used consistently:

| Status | Meaning |
|---|---|
| 400 | Malformed or invalid request input |
| 401 | Missing or invalid session |
| 403 | An authenticated user acting on a row they don't own — every query against `recipes`, `recipe_ingredients`, `meal_plan_entries`, `shopping_lists`, `shopping_list_items`, or `shopping_list_item_sources` must filter by `owner_id` (or join through it); this is larder's equivalent of `shelf`'s path-safety rule and gets the same "no exceptions" treatment, audited in `V1_PLAN.md` Phase 11. **This extends to any owner-scoped id accepted as input, not just the row being directly queried**: a client-supplied `recipe_id` (in a meal-plan-entry create, or a shopping-list-generation request) must be verified to resolve to a recipe owned by the authenticated user before it's used — the FK only proves the row exists, not that it's theirs. `ingredients`/`ingredient_aliases`/`units`/`unit_conversions` are the one deliberate exception — global, not `owner_id`-scoped, see `PROJECT_BRIEF.md` §4 |
| 404 | Resource not found |
| 409 | A conflict with existing state — either an optimistic-concurrency conflict (stale `updated_at` on write) or a uniqueness conflict (e.g. `USERNAME_TAKEN` on registration) |
| 422 | Well-formed request the app can't act on (e.g. a recipe import URL with no parseable JSON-LD) |
| 500 | Unexpected/programmer error |

422 is the one addition over `shelf`'s table — recipe import is expected to fail on a
well-formed URL that just doesn't have usable structured data, which is a different situation
from a malformed request (400).

## Concurrency

Handlers are plain blocking code, run on a bounded thread-pool `Executor` set via
`HttpServer.setExecutor(...)`. No coroutines by default; `kotlinx.coroutines` is pre-approved
if a genuine need shows up, same as `shelf`'s policy.

There's no reconciliation/background-scan job in this app at all (unlike `shelf` — see
`PROJECT_BRIEF.md` section 4). If a genuine need for periodic background work shows up later,
a plain `ScheduledExecutorService` is the default — no job-scheduling framework.

## Logging

`java.util.logging` — already in the JDK, zero new dependency. No SLF4J/Logback, unless a real
need for structured logging surfaces later (see dependency policy below — that would need
sign-off like anything else).

## Configuration

Environment variables only, read at startup with sane defaults where one makes sense. No
config-file parser, no config library. `LARDER_`-prefixed, matching `shelf`'s `SHELF_`
convention (e.g. `LARDER_DB_URL`, `LARDER_PORT`).

## Testing

Start the same way `shelf` did: no test framework dependency, `kotlin-test.jar` from the
installed `kotlinc` distribution, a hand-rolled `TestMain.kt` reflectively discovering
top-level `test<Something>()` functions in `.kt` files under `backend/test/` (parallel to
`backend/src/`, never nested inside it). `backend/test.sh` compiles both and runs it, same as
`shelf`'s script.

**Ingredient-line parsing needs unusually thorough tests, on both sides of Phase 4's split.**
Phase 4a's sidecar (`ingredient-parser/`, Python/pytest, not this hand-rolled Kotlin runner —
already built, real ingredient-line test cases, not synthetic ones) covers the parsing itself.
Phase 4b's Kotlin-side resolution logic (mapping the sidecar's output onto
`units`/`ingredients`, collapsing its possibly-multiple `amount` entries into one
`recipe_ingredients` row) needs its own real-world test cases here, for the same reason: this
module's real-world accuracy is what the shopping-list feature actually depends on. If the
hand-rolled Kotlin runner starts straining under a large, data-driven test set for that,
that's a reasonable, specific trigger to raise adding a real test framework — per the
dependency policy below, propose it rather than just adding it.

## Dependency and build-tool policy — looser than `shelf`'s, same discipline

`shelf` never needed anything beyond the Postgres JDBC driver and `kotlinx.*`, built with bare
`kotlinc`. larder starts from that identical baseline — **pre-approved, no need to ask: the
Postgres JDBC driver, any `kotlinx.*` library, and `kotlin-test`** — but the human has
explicitly said it's fine to move to Gradle and to add other external dependencies when a
specific, real need justifies it (this app's problem domain — HTML/recipe parsing, ingredient
text parsing — is harder than a file browser's). The most likely candidate, flagged in advance
in `PROJECT_BRIEF.md` section 4 and `V1_PLAN.md` Phase 6, is a proper HTML parser (`jsoup`) if
schema.org JSON-LD extraction turns out not to cover enough real recipe sites.

The rule that doesn't loosen: **propose the specific dependency (or the move to Gradle) and the
specific need it solves, get sign-off, then add it.** Never add one silently, speculatively, or
"to save time" — the same standard `shelf` held itself to, just with a lower bar for *when* a
proposal is reasonable to make.

**When a new dependency jar is approved, add it to `backend/lib/DEPENDENCIES.sha1` (filename,
sha1, source URL) and let `backend/lib/fetch-deps.sh` fetch it — never commit the `.jar` itself
to git.** Same rule as `shelf`, same reasoning: binary artifacts don't belong in git history:
fetch them with a checksum-verified script instead.

**If/when the project moves to Gradle**, the same "no hidden build-tool magic" spirit still
applies as much as practical — keep the build file readable and avoid plugin sprawl — but the
hard "no Gradle at all" line from `shelf` is not a larder constraint. Record the move itself in
`docs/decisions.md` when it happens, with the specific reason.

**A new runtime/service is a bigger category of change than a jar or a Gradle move, and this
policy's examples above didn't originally contemplate it — the `ingredient-parser/` Python
sidecar (`PROJECT_BRIEF.md` §4) is the concrete precedent now.** Everything above this point
assumes "still one JVM process" (a jar on the classpath, or a build tool driving that same
compile). A second runtime — a separate container with its own language, its own dependency
tree, its own failure modes, reachable over the network instead of a function call — is a
qualitatively different kind of addition: more to audit, a new network hop on whatever calls
it, a new image to build and keep patched. It needs the same propose-first discipline as
everything else in this section (the specific need, not just "this would be nice"), but expect
the bar for "does the need justify it" to be higher, precisely because the cost is higher.
Wire it into `docker-compose.yml` on an internal-only network (no host port unless something
outside the deployment genuinely needs to reach it directly), same as `ingredient-parser`'s own
service is scoped.
