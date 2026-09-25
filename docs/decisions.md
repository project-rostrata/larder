# Decisions log

Append-only record of decisions made while building larder, beyond what `PROJECT_BRIEF.md` and
`V1_PLAN.md` already fix. Newest entries at the bottom. Don't re-litigate any of these without
flagging it to the human first.

## App name: larder

Chosen during the initial planning conversation, from a shortlist of one-word,
`shelf`-style names (larder, pantry, crate, trove).

## Multi-user accounts, same per-user home-directory model as `shelf`

Considered a single shared login with one collection instead. Decided to mirror `shelf`'s
account model directly — a household that wants one shared collection can just use one
account, and it keeps the two apps' operational patterns consistent (same username-keyed
storage layout, same auth code shape to port over).

## Recipe storage format: JSON, not Markdown+YAML-frontmatter or pure YAML

Considered Markdown with a YAML frontmatter header (closer to how recipes are already written
in blogs/cookbooks, more pleasant to hand-edit) and pure YAML. Decided on JSON specifically
because the shopping-list combination feature — the main reason this app exists — needs
structured ingredient data (quantity/unit/name, not a prose line) regardless of format choice,
which erases Markdown's main advantage (natural prose) for the one field (instructions) where
it would have mattered; and JSON's shape maps directly onto schema.org's `Recipe` type, which
is what recipe-URL import parses out of pages, keeping the import path a straight structural
mapping instead of a format translation.

## Dependency and build-tool policy loosened relative to `shelf`

`shelf` held a hard line: no Gradle/Maven, only the Postgres JDBC driver and `kotlinx.*`
without asking. For larder, the human explicitly said it's fine to move to a real build tool
and add external libraries when a specific need justifies it, because this app's problem
domain (recipe/HTML parsing, ingredient text parsing) is harder than a file browser's. The
project still starts on bare `kotlinc` with zero non-`kotlinx` dependencies, same as `shelf`'s
Phase 1 — this is a raised ceiling, not a different floor. See `PROJECT_BRIEF.md` §2 and
`AGENTS.md`'s dependency policy section for the exact rule (propose before adding, always).

## No images in v1

Explicit scope cut from the human at the start of planning — recipe photos, and the
storage/proxying/thumbnailing subsystem a "replace Nextcloud Cookbook" app would otherwise
need for them, are out of scope for v1 entirely.

## Recipe storage reversed: Postgres, not flat files

Supersedes the "Recipe storage format: JSON" entry above. That entry decided one-JSON-file-per-recipe, mirroring `shelf` directly. Revisited
immediately after, before any code existed, and reversed to normalized Postgres tables
(`recipes` + `recipe_ingredients`) instead. This log is append-only, so that earlier entry
stays as written rather than being edited away — treat this entry as the one that supersedes
it going forward.

Reasoning, in order of weight:
1. Shopping-list combination — the feature this whole app exists for — needs relational
   ingredient data (a join/aggregation over rows), not a JSON blob plus a cache index built to
   keep that blob's metadata queryable.
2. `meal_plan_entries` and `shopping_lists` were always going to be plain Postgres rows with no
   file backing (no natural file form for them). File-backed recipes would have been the only
   thing in the app needing `shelf`'s reconciliation machinery (mtime scanning, nightly
   backstop scan, path-safety layer, optimistic-concurrency conflict handling) — a meaningful
   amount of complexity paid for by exactly one table.
3. That machinery earns its keep in `shelf` because files really do get edited outside the app
   (SMB, SSH) while the app also serves them. Recipes don't have an equivalent use case in
   practice — edits go through the app. There was no real benefit being bought with that
   complexity here.

A JSON-export feature (recipes out as plain files, for backup/portability, without going back
to file-backed *storage*) was discussed as a way to keep some of what `shelf`'s "just files"
property offered, and was explicitly deferred rather than ruled out — see
`PROJECT_BRIEF.md` §5 and §6 (v1.5). The human's framing: "DB as truth, without the export
feature just yet."

Downstream effects of this reversal, all reflected in `PROJECT_BRIEF.md` and `V1_PLAN.md`
directly rather than left as an exercise for later: no path-safety phase, no reconciliation
phase, no per-user home directory, `owner_id` replaces "does this path resolve under the
user's root" as the access-control invariant that gets audited in the hardening phase, and the
Docker setup drops the bind-mounted storage volume and `PUID`/`PGID` entrypoint entirely since
there's no user-facing filesystem content left to manage.

## Ingredient/unit model redesigned after researching Mealie, Tandoor Recipes, and Grocy

Before building Phase 4 (ingredient parser) or Phase 8 (shopping-list generation), three real
OSS recipe/grocery apps were researched specifically for how they solve ingredient identity,
unit conversion, shopping-list combination, and serving-size scaling — the human asked for this
explicitly rather than having the design proceed on assumption alone. Findings:

- **Ingredient identity (e.g. "scallion" and "green onion" being the same thing) has no
  automated solution in any of them.** Mealie has a canonical `IngredientFood` with an alias
  list, but a maintainer confirmed alias creation is manual, not semantic/NLP matching. Tandoor
  is exact-match only, with a user-authored rewrite-rule mechanism and a `merge_into` admin
  action for fixing duplicates after the fact. Conclusion: a curated `ingredients` +
  `ingredient_aliases` table, exact match only, with a Tandoor-style merge-after-the-fact
  recovery path, is the actual state of the art here, not a corner being cut.
- **Units: Mealie's `standard_quantity`/`standard_unit` design (each unit declares a factor
  against a shared per-dimension reference unit) is cleaner than a hardcoded pairwise
  conversion table**, so larder adopted that shape (`units.to_base_factor`) for the common
  volume/mass case. Tandoor (a per-food `UnitConversion` model) and Grocy (per-product "QU
  Conversions") independently confirmed the same pattern for the exception case — count-style
  units like "clove" or "can" needing an ingredient-specific conversion factor, not a universal
  one. Both pieces went into larder's `units`/`unit_conversions` design — see
  `PROJECT_BRIEF.md` §4 and §7 for the resulting schema.
- **Shopping-list combination is a real, unsolved gap even in the most mature OSS competitor
  researched.** Tandoor does not combine ingredients across recipes at all — one line item per
  recipe-ingredient, unsummed. Mealie does combine (`can_merge`/`merge_items`), and that logic
  is the concrete reference larder's own combination rule is modeled on.
- **Scaling: neither app stores quantity as a fraction.** Mealie uses a plain float and has a
  live, reported floating-point bug from it. Tandoor uses a high-precision `Decimal`, which
  avoids that specific bug but still can't represent a repeating fraction like `1/3` exactly.
  larder stores quantity as an integer numerator/denominator instead — not hedging on this as a
  possible over-engineering; it's a documented real gap in both apps researched. Tandoor's
  separate `servings`/`servings_text` split (one numeric, used for scaling math; one free-text,
  display-only) was adopted directly.

**Follow-up decisions made the same session, from the human directly, after reviewing these
findings:**
- **A manual "merge these shopping-list items" operation is the accepted workaround for
  whatever automatic ingredient matching misses**, rather than trying to push automatic
  matching further (e.g. toward fuzzy/semantic matching, which section 4 already treats as
  effectively unsolved in this space). The human's own current tool for this problem — a paid,
  more curation-focused competing app — takes the same "not perfect, but has an easy manual
  fix" approach, which is the bar larder is aiming for too, not perfection.
- **Every combined shopping-list item must retain each contributing recipe's original quantity
  and unit**, not just a converted total — e.g. a combined "1 lb flour" item should still be
  able to show "4 cups, from Recipe A" and "3 tbsp, from Recipe B" on demand. This is a stated
  requirement, not a nice-to-have, and is why `shopping_list_item_sources` exists as a real
  snapshot table rather than the flat `source_recipe_ids` array originally sketched.
- **Recipe curation/discovery stays explicitly out of scope** — recipes are entered or imported
  one at a time by a human; larder is not trying to compete with the discovery-focused side of
  what that paid competing app does, only its shopping-list-combination behavior was relevant
  inspiration here.
- **Two UX surfaces are deliberately deferred without deferring the data model that supports
  them:** whether a recipe entry/import view indicates that an ingredient is newly-created vs.
  already-known, and what the interaction for manually merging shopping-list items looks like.
  Both are explicit "build the capability now, design the UI later" calls from the human, not
  oversights — see `PROJECT_BRIEF.md` §5.

## Recipe deletion is a soft delete — reversing an unexamined inheritance from `shelf`

While reviewing what else Phase 2 needed settled before implementation, an early draft proposed
`meal_plan_entries.recipe_id ON DELETE CASCADE` — reasoning it as "the natural extension of
`shelf`'s hard-delete, no-trash philosophy," which `PROJECT_BRIEF.md` had otherwise carried over
unexamined. The human caught this: unlike `shelf` (where a deleted file really should just be
gone), larder needs recipe deletion to preserve historical meal-plan integrity — a meal plan
from three weeks ago should still show what was planned even if that recipe has since been
removed from the active collection.

`recipes` now carries `deleted_at TIMESTAMPTZ NULL` instead. This is deliberately narrower than
a full trash feature (no restore endpoint, no purge job, no trash view in v1) — it solves the
specific "a plain FK shouldn't lose its target" problem, not a general undo feature. Notably,
`shopping_list_item_sources` never had this problem in the first place: it was already designed
(before this decision) to snapshot recipe title/quantity/unit specifically so a generated
shopping list survives its source recipes being edited or deleted later. Soft-deleting recipes
fixes the same underlying concern for `meal_plan_entries` (and anything else that references
`recipe_id` in the future) at the source, rather than requiring every consumer to duplicate that
snapshot pattern. Scoped to recipes only — `meal_plan_entries` and `shopping_lists`/
`shopping_list_items` still hard-delete normally; an ingredient's `merge-into` cleanup delete is
unaffected since nothing references that row by the time it's removed.

**Asked to account for which other Phase-2 decisions were actually inspected from `shelf`'s
behavior versus independently reasoned, the honest breakdown turned out to be:**
- **Actually inspected from `shelf`'s real schema** (`shelf/db/migrations/0001_initial_schema.sql`,
  read directly, not recalled from memory): UUID primary keys via `gen_random_uuid()` and
  `TIMESTAMPTZ NOT NULL DEFAULT now()` for timestamps — except `sessions.id`, which `shelf`
  deliberately gives **no default**, generating the token explicitly in application code with a
  CSPRNG rather than leaving it to the database; larder's `sessions` table follows that same
  exception. Also inspected and matched: `shelf`'s `CHECK (... IN (...))` pattern for small
  fixed-vocabulary text columns (used there for `share_type`/`permissions`) — applied to
  `units.dimension` (`'volume' | 'mass' | 'count'`), which is a truly closed set the app's own
  conversion logic depends on. And the Postgres version pin (18.6-alpine), matching `shelf`'s
  own pinned version with no reason to diverge.
- **Not inspected from `shelf` — independent judgment on larder-only concepts with no `shelf`
  analog to inspect:** the case-insensitive uniqueness constraints on `ingredients.name` and
  `ingredient_aliases.alias` (preventing the auto-create-on-miss lookup from racing into
  duplicates or ambiguous alias mappings), `unit_conversions` storing one row per pair rather
  than both directions, `recipes.servings` being `NUMERIC` rather than `INTEGER`, and
  `meal_plan_entries.meal_slot` being deliberately *un*constrained `TEXT` rather than a
  `CHECK`-constrained enum (the opposite choice from `units.dimension`, made because meal-slot
  labels are user-facing vocabulary that shouldn't be closed, unlike a physical-unit dimension).
  None of these have a `shelf` equivalent to have copied from.

## Phase 2 implementation: dropped `is_admin`, added a cross-reference ownership rule

Two things caught while actually writing the migration, both fixed in the same pass rather
than left for later:

- **`users.is_admin`** was in `PROJECT_BRIEF.md` §7's original sketch, copied from `shelf`'s
  own `users` table without being re-examined — the same category of mistake as the hard-delete
  carry-over above, just lower-stakes. larder has no admin panel, no multi-tenant management
  feature, and nothing in `V1_PLAN.md` ever reads this column. Dropped from the schema; trivial
  to add back with a one-line migration if an actual admin feature is ever planned.
- **Cross-referenced ownership isn't the same check as direct-row ownership.** `shelf` never had
  this problem — none of its tables reference another owner-scoped row by id. larder's
  `meal_plan_entries.recipe_id` and Phase 8's shopping-list generation both accept a `recipe_id`
  from the client and use it to look up a *different* owner-scoped row than the one being
  written. A foreign key only proves that row exists, not that the caller owns it — verifying
  `recipe.owner_id == authenticated_user.id` for every such reference is a distinct check from
  "does this query filter by owner_id," easy to miss precisely because it looks like the same
  rule. Added explicitly to `AGENTS.md`'s ownership row, `SECURITY.md`, and `V1_PLAN.md`'s
  Phase 7/8/11 text so it isn't missed when those phases are actually built.

## Ingredient-line parsing: a Python sidecar, not a hand-rolled Kotlin parser

Phase 4 was originally planned as a hand-rolled Kotlin regex parser (see the "Ingredient/unit
model redesigned..." entry above, which researched Mealie/Tandoor/Grocy's *ingredient-identity
and unit-conversion* design but not ingredient-line *text-parsing* libraries specifically — that
research existed only inline in `PROJECT_BRIEF.md`/`V1_PLAN.md` until now, never logged here;
this entry closes that gap). Before building it, the human asked directly whether existing work
could be leveraged instead. Two research passes (an Explore pass confirming what was already
decided, then a dedicated Plan-agent research pass) found:

- **No JVM/Kotlin-native ingredient-parsing library exists.** Confirmed via Maven Central's
  search API (zero results for "ingredient") plus broad web/GitHub search. The one near-hit
  (`JFfarrell/pantry`) is a 10-day-old, unlicensed, unverifiable personal Android project, not
  a usable library.
- **Porting `strangetom/ingredient-parser`'s trained model to Kotlin is not the small job the
  premise assumed.** Reading the actual source: the CRF Viterbi decoder genuinely is simple
  (~80–100 lines, pure NumPy matrix ops) — but it's useless without the feature-extraction
  pipeline feeding it (NLTK POS tagging — itself a separately-trained model — ~20–25
  hand-engineered features, a custom GloVe embedding) and the 2,400-line postprocessing engine
  after it (ranges, composite amounts, multipliers, size/preparation/comment separation). Real
  scope: a multi-thousand-line port across three separately-trained-or-engineered components,
  with silent-failure risk (a mis-ported feature doesn't crash, it quietly produces worse
  predictions). Not proportionate for v1 or v1.5 of a hobby-scale self-hosted app. The model
  file itself is small, well-documented JSON, MIT-licensed, and trivially obtainable — none of
  that was ever the hard part.
- **Zestful, a hosted ingredient-parsing API, is real and still operating** (built by the same
  person behind the original NYT ingredient-phrase-tagger) but costs $0.02/ingredient with no
  bounded ceiling, is a single-maintainer commercial product, and would send every ingredient
  line any self-hoster ever types to a third party — directly against the self-hosted/
  low-dependency ethos this project and `shelf` both hold. Not adopted as a default dependency.
- **Running `strangetom/ingredient-parser` itself as a small sidecar container is what actually
  clears the bar**: MIT-licensed, actively maintained, 94.9% sentence-level / 98% word-level
  accuracy on 81k+ training sentences, a clean `parse_ingredient()` entry point. Its accuracy
  advantage lands specifically on failure modes that would otherwise undermine larder's
  shopping-list-combination feature directly — e.g. separating size words from the ingredient
  name (`"2 large eggs"` → name `"eggs"`, not `"large eggs"`) is what lets that combine with a
  plain `"3 eggs"` elsewhere. A regex parser's "whatever's left is the name" rule would produce
  two different names that never combine.

**The human's own follow-up questions shaped the final architecture, not just the initial
choice:**
- Asked specifically what the accuracy advantage looks like in practice, not just as a
  percentage — the size-word-separation example above, and several others (parenthetical
  package sizes like `"1 (14.5 oz) can diced tomatoes"`, multiple trailing clauses like
  `"sifted, divided"`, quantity appearing after the name like `"Flour, 2 cups"`) became the
  concrete basis for Phase 4a's actual test cases, not hypothetical justification.
- Asked about CPU-only inference performance given the real usage pattern (parsing happens when
  a recipe is saved/imported, not constantly). Reasoned estimate at the time: single-digit-to-
  low-double-digit milliseconds once warm, since the model has no GPU/neural-net dependency
  anywhere in its stack. **Confirmed by direct measurement once Phase 4a was actually built and
  running: ~4.5–5.8ms per request against a warm container.**
- Asked directly how the sidecar would run mechanically — not a subprocess shelled out from
  Kotlin (would pay the full model-load cost on every call, and would force Python into the
  same container as the JVM app) and not the `ingredient-parser` project's own "webtools" (a
  training-data labeling tool, not a production API). The actual answer: a small first-party
  HTTP wrapper we write ourselves, run as its own container, called over the internal Docker
  network — this became Phase 4a's design directly.
- **Decided to build the sidecar as an explicit phase now**, rather than gating it on evidence
  of the regex parser's real-world shortfall — the "ship the weaker approach, upgrade later if
  it's not good enough" framing was the original recommendation, but the human chose to build
  the better-evidenced approach directly instead.

**Scope split, not a renumbering**: `V1_PLAN.md`'s "Phase 4" stays one heading, split into
Phase 4a (the sidecar itself — done, see `ingredient-parser/README.md`) and Phase 4b (the
Kotlin-side integration — not yet built). No other phase's number changed, matching `shelf`'s
own precedent of annotating scope changes in place rather than renumbering a document other
files already cross-reference by number.

**One real implementation-time correction, caught while building Phase 4a**: the Dockerfile
originally set `ENV NLTK_DATA=/usr/local/share/nltk_data` assuming NLTK's downloader would
write there — it doesn't; `nltk.download()` ignores `NLTK_DATA` for its *write* location
(though it does honor it for the *search* path) and defaults to the current user's home
directory regardless. Confirmed by testing, not assumed. Fixed by calling
`nltk.download(..., download_dir=...)` explicitly rather than relying on the env var alone —
otherwise this would have been a latent bug waiting for whenever the image adds a non-root
`USER` (root's home directory wouldn't be relevant to a different user at that point, and the
container would silently need runtime internet access it wasn't supposed to need).

## Phase 4b: the multi-amount-collapsing rule, and where the Kotlin test suite actually started

**The design question `V1_PLAN.md` flagged in advance — how to collapse the sidecar's
possibly-multiple `amount` entries into larder's single quantity/unit pair — was resolved by
inspecting real sidecar output, not guessed at.** Sent several real lines through the actual
running service before writing the resolution code:
- `"1 (14.5 oz) can diced tomatoes"` and `"3 15-oz cans black beans"` each return two plain
  amounts (count unit first, mass unit second) — no field reliably distinguishes "this is the
  primary purchasable quantity" from "this is descriptive package-size context" (`MULTIPLIER`
  is only set for an explicit `"N x"` phrasing like `"2 x 400g cans"`, not this parenthetical
  form; `SINGULAR` doesn't cleanly separate the cases either).
- `"1 cup plus 2 tablespoons flour"` returns something structurally different: one
  `CompositeIngredientAmount` wrapping two sub-amounts (with `join`/`subtractive` fields), not
  two plain amounts — a real, additive relationship the library itself distinguishes from the
  count/package-size case above.
- `"2-3 cloves garlic, minced"` returns one amount with `RANGE: true`, `quantity: 2`,
  `quantity_max: 3`.

**Decision**: always take the first entry in the `amount` list (confirmed ordered by
`starting_index`) as the row's quantity/unit. A composite recurses into its own first
sub-amount rather than being summed. A range amount uses `quantity_max`, not `quantity` — for a
shopping list, better to slightly over-buy than under-buy. Everything not captured this way is
never lost, only unstructured — `raw_text` always has the full original line regardless.
Properly summing composite amounts, or choosing more cleverly between a package's count and its
per-unit size, would need the same unit-family conversion machinery Phase 8 builds for
shopping-list combination — judged not worth duplicating here for v1; revisit if real recipes
turn out to lean on composite amounts often enough for the current simplification to matter.

**`backend/test/TestMain.kt` and `backend/test.sh` were built here, not earlier.**
`AGENTS.md`'s testing section had already flagged "Phase 1 or Phase 4" as the likely trigger for
when larder's first real Kotlin tests — and therefore the hand-rolled test-discovery
infrastructure itself, ported from `shelf`'s — would show up. Phase 4a turned out to be Python
(its own `pytest` suite, nothing to do with this), so Phase 4b is where it actually landed, as
anticipated. Following `shelf`'s own demonstrated pattern exactly (confirmed by reading
`shelf`'s `MigrationRunnerTest.kt`, which tests only the pure filename-parsing logic and not
the live-database-touching `run()` method): the hand-rolled suite stays scoped to hermetic
tests that need no external service to run. The JSON-extraction logic
(`parseSidecarResponse`/`extractPrimaryAmount`) is pure and lives there, built from real
captured sidecar responses, not synthetic ones. `IngredientRepository`/`UnitRepository`/
`IngredientResolver` genuinely need a live Postgres and were instead verified with a temporary,
not-committed Kotlin entry point run against real containers — same tier of verification every
other phase has gotten, just not embedded in the checked-in suite.

## Phase 5 implementation notes

Four things worth recording, none of them re-litigating anything settled earlier — all either
filled a gap the earlier phases' text left implicit, or are ordinary implementation-level calls
made and documented as they came up:

- **Wrong-owner access to a single resource by id returns 404, not 403.** `AGENTS.md`'s
  ownership rule says every query must filter by `owner_id`, which is exactly what
  `GET/PUT/DELETE /api/recipes/{id}` do (`WHERE id = ? AND owner_id = ?`) — a row belonging to
  someone else simply doesn't match the query, indistinguishable at the SQL level from not
  existing at all. This collapses "not yours" and "doesn't exist" into the same 404, which is
  also the safer convention (never let an unauthorized caller learn a resource exists at all).
  **403 is reserved for a different case**, already anticipated in the Phase 2 entry above:
  a client-supplied id that references a *different* owner-scoped row (e.g. a future
  `recipe_id` inside a Phase 7 meal-plan-entry request) — there the FK doesn't naturally
  produce a 404, so an explicit ownership check has to run and 403 on failure. Both are correct;
  they're answering different questions.
- **`Database` gained transaction support** (`Database.transaction { tx -> ... }`, and a
  `Transaction` class offering the same query/update shape as `Database` itself but bound to
  one already-open connection). Nothing before Phase 5 needed more than one statement to commit
  atomically; a recipe plus its `recipe_ingredients` rows do. Refactored `Database`'s existing
  four methods to delegate to shared private connection-level functions rather than duplicate
  their bodies in `Transaction` — worth the small touch to Phase-1 code to avoid that.
- **Recipe writes and ingredient resolution are deliberately NOT in the same transaction.**
  Resolving an ingredient line can auto-create a new canonical `ingredients` row
  (`IngredientRepository.findOrCreate`) before the recipe's own insert/update transaction even
  opens. If that later transaction then fails and rolls back, any newly-auto-created ingredient
  from it stays committed anyway. This is fine, not a bug: `ingredients` is a global vocabulary
  table with no expectation that every row is referenced by some recipe — an unreferenced
  canonical ingredient sitting there is exactly as harmless as one sitting unused right after
  the seed migration runs. Keeping ingredient resolution outside the recipe transaction avoids
  giving `IngredientRepository`/`UnitRepository` a second, transaction-scoped code path for no
  real benefit.
- **`servings` is a plain `Double` in the API, not an exact-fraction pair like ingredient
  quantities.** The fraction treatment exists because ingredient quantities need exact scaling
  arithmetic and natural fraction display (`PROJECT_BRIEF.md` section 4). Servings counts are
  always simple, short values in practice (4, 6, 2.5) that `Double` represents exactly regardless
  — float imprecision only bites on repeating binary fractions like 1/3, which servings numbers
  don't produce. Applying the fraction treatment here anyway would be process, not precision.

## Phase 6: jsoup wasn't needed, an SSRF gap closed before it existed, and real-vs-synthetic test fixtures

**The `jsoup` contingency flagged as far back as the original planning conversation (`brief
section 4: "if JSON-LD proves too lossy in practice... a proper HTML parser library is the
anticipated next step"`) was not needed.** This phase was specifically the trigger point for
that question. Rather than guess, it was tested directly: fetched a real, live recipe page
(food.com, one of the few real recipe sites this sandbox's outbound network could actually
reach — most major sites' bot-protection blocked it) and confirmed the regex/`kotlinx.serialization`
JSON-LD extraction handled it correctly end to end, messy real-world data included (stray
whitespace in ingredient lines, a `recipeYield` string that isn't a clean number). One real site
isn't exhaustive proof no site will ever need `jsoup`, but it's real evidence against a
real-but-unconfirmed assumption, which is what this phase was for. Not proposed, not added,
per `AGENTS.md`'s propose-first discipline either way.

**The SSRF guard (`SECURITY.md`'s already-documented requirement) does full redirect
re-validation, not just a check on the original URL.** A first draft validated the URL once,
before the fetch — but a URL that passes that check can still 3xx-redirect to
`http://169.254.169.254/...` or similar, and `HttpClient`'s own automatic redirect handling
would follow that without ever re-running the guard. Caught this before it shipped, not after a
report: redirects are now followed manually
(`larder.recipeimport.fetchRecipeHtml`), one hop at a time, re-running
`validateImportUrl` on every hop, capped at 5 redirects. `HttpClient` itself is configured with
`followRedirects(NEVER)` so there's no chance of the built-in handling silently doing the
unvalidated version anyway.

**Error code mapping: 400 for a URL we won't fetch, 422 for a URL we fetched but couldn't use.**
Rejected by the SSRF guard, wrong scheme, or a malformed request body are all 400 — the
client's *own* input to *our* API was the problem. A URL that's fine, fetches fine, but yields
no parseable `Recipe` JSON-LD (or one that fails the normal recipe validation, e.g. no title) is
422 — `AGENTS.md`'s "well-formed request the app can't act on," since the request to *us* (a
plain URL) was entirely well-formed; it's the *content at that URL* that wasn't usable. This
distinction was already established by `AGENTS.md`'s error table before this phase; this is
just the first phase to actually need both codes on the same endpoint.

**Test fixtures are honestly labeled real vs. representative-synthetic, not blurred together.**
`JsonLdRecipeParserTest.kt`'s food.com fixture is real captured JSON-LD, fetched live during
this phase's design — the file says so directly. The `@graph`, multi-typed-`@type`, and
`HowToSection` test cases are synthetic, built from well-documented, standardized schema.org
patterns this sandbox's network couldn't reach a real example of during this pass — the file
says that too, rather than presenting hand-constructed fixtures as if they were captured the
same way the food.com one was.

## Phase 9a: query-param routing, card-grid recipe list, and two real bugs caught before shipping

**Two consequential frontend forks were resolved by asking the human directly rather than
guessing**, since both are hard to change later without a rewrite: routing model (query-param
state on a single route, matching `shelf`'s own `router.js` pattern exactly, vs. real
path-based routes with client-side history) and recipe-list layout (a card grid vs. a table like
`shelf`'s `FileTable`). The human picked the recommended option both times — query-param
routing, card grid — so both are now fixed conventions for the rest of the frontend, including
Phase 9b.

**Query-param routing means the static file server needs no SPA-fallback logic.** Every view
lives at path `/`; only the `?view=`/`&id=`/`&tag=` query string changes, and query strings
don't affect which file a GET request resolves to. `StaticFileHandler` can stay as simple as
"serve the file at this exact path, or `index.html` isn't special-cased at all beyond being the
file `GET /` naturally resolves to" — no "any unknown path serves index.html" rule was needed,
which real path-based routing would have required.

**Card grid, not a table.** `shelf`'s `FileTable` fits files well (name, size, modified — dense
tabular metadata). A recipe's most useful at-a-glance information (title, a tag or two, roughly
how long it takes) fits a card better than a table row, and a grid reads better at a glance when
browsing something you'll pick from rather than manage. Chosen despite table-based `FileTable`
being the more direct `shelf` precedent — matching `shelf`'s *ethos* (plain, unfussy, no icon
library) doesn't mean copying every literal layout choice.

**Two real bugs were caught and fixed during this phase's own live verification, not reported
after the fact:**

1. `RecipeList.js`'s `Grid()` originally wrapped the reactively-rendered recipe cards in a plain
   `<div>` inside `.recipe-grid`. CSS Grid lays out its *direct* children according to
   `grid-template-columns`; an extra wrapper div becomes the one grid item instead, and the
   cards inside it just stack in normal document flow — the grid would have silently degraded to
   a single column no matter the viewport width. VanJS reactive bindings can only return one
   node, so some wrapper is unavoidable when a binding needs to produce a variable-length list of
   siblings; the fix is `style: "display:contents"` on that wrapper, which removes it from the
   box-layout tree entirely while keeping it in the DOM, so its children lay out as if they were
   direct children of `.recipe-grid`. The same pattern was then applied proactively in
   `RecipeForm.js`'s `DynamicRows()`, which has the identical "reactive binding needs to render a
   variable-length list of sibling rows" shape.
2. `RecipeList()` called `refreshRecipeList()` itself on mount, not realizing `router.js`'s
   `navigate()`/`applyUrlToState()` already calls it via `loadForCurrentView()` on every
   navigation to the `"recipes"` view — including the very first page load. Every visit to the
   list was firing two identical `GET /api/recipes` requests. Fixed by making the rule explicit
   and consistent across every view: `router.js` owns triggering data loads, view components
   only render `state`, they never fetch on their own. `RecipeDetail.js` and `RecipeForm.js`
   were written to this rule from the start rather than needing the same fix.

**No headless-browser verification was available or attempted.** Everything above was verified
against the real HTTP API (Postgres + the real sidecar + the compiled backend serving the real
`frontend/` directory) and by hand-tracing VanJS's reactivity semantics against its vendored
source, plus a `node --check` syntax pass on every frontend file — but nothing rendered the UI
in an actual browser or DOM. Neither Playwright, jsdom, nor Selenium was present in this
environment, and installing one (a network fetch of a browser binary) wasn't requested, so this
is flagged here rather than silently treated as "the UI works."

## Phase 10: one bundled image instead of a separate sidecar service, and why a dead sidecar doesn't end the container

**The human asked for a single deployable Docker image, not the separate
app-service/sidecar-service split `V1_PLAN.md`'s original Phase 10 sketch implicitly assumed.**
`AGENTS.md`'s "new runtime/service" policy already treats the sidecar as a bigger category of
change than a jar, and its stated default was to wire it into `docker-compose.yml` as its own
service on an internal-only network, mirroring `ingredient-parser`'s standalone Dockerfile.
Bundling it into the app's own image instead is a direct, explicit instruction from the human,
not a default the app arrived at on its own — recorded here because it's a real deviation from
that policy's stated default, not because the policy was wrong.

**The runtime stage is built on `python:3.12-slim` (glibc) with a JRE added via `apt`, not an
Alpine JRE base with Python added via `apk`.** `ingredient-parser/Dockerfile` already
established that its dependencies need glibc — `numpy` and `regex` ship manylinux wheels that
only install on glibc; Alpine's musl libc would force both to compile from source at build time
instead, slower and more fragile. Starting the combined image from the already-proven Python
base and adding the JRE (rather than the reverse) avoids re-litigating that finding. One real
snag hit and fixed during this phase: `python:3.12-slim`'s current base is Debian trixie, whose
apt repos dropped `openjdk-17-jre-headless` in favor of 21 — `apt-get install` failed with a
clear "no installation candidate" error, not a silent wrong-version install, so this was caught
immediately and the Dockerfile now installs `openjdk-21-jre-headless`.

**`docker/entrypoint.sh` starts the sidecar and the app as direct child processes with no
supervisor dependency (no s6-overlay, no supervisord)** — mirroring the pattern `shelf`'s
`docker/standalone-entrypoint.sh` already established for coordinating more than one process in
a single container. **It deliberately diverges from that precedent on one point: only the app
process's own exit ends the container, not the sidecar's.** `shelf`'s standalone entrypoint
bundles Postgres, which the app genuinely cannot function without, so either process dying means
the container isn't healthy. The sidecar is different in kind — Phase 5's live verification
already confirmed the app degrades gracefully to raw-text-only ingredients when the sidecar is
unreachable, mid-session, without throwing. Ending the container on a sidecar crash would make
the *bundled* deployment strictly less resilient than the *unbundled* one (a separate sidecar
container restarting on its own, per Docker's normal restart policy, while the app kept serving)
— the opposite of what bundling was supposed to simplify. This was verified directly, not just
reasoned about: the sidecar's process was killed inside a running container, the container
stayed up, `GET /api/health` kept responding, and a subsequent recipe create correctly fell back
to an all-null unresolved ingredient rather than erroring. A clean `docker stop` afterward still
exited promptly (exit code 143, the normal SIGTERM result) with no forced kill needed.

**No reverse-proxy service, unlike the original Phase 10 sketch's "optional" mention.** Not
asked for, and TLS termination is a deployment-specific decision (whatever the human already
runs, if anything) rather than something this repo should prescribe. Can be added later if a
specific need shows up.

Verified live end-to-end via `docker compose -f docker/docker-compose.yml up --build`: the app
image serves the real frontend and API on the same port as before, the bundled sidecar answers
over loopback inside the container (confirmed via real ingredient parsing through the full
create-recipe flow — the same size-word-stripping and multi-clause-notes behavior already
verified in Phase 9a), and Postgres migrations apply automatically on first start via the
existing `healthcheck`-gated `depends_on` ordering.

## Bug fix: recipe form fields weren't editable — reading `.val` as a prop value taints the wrong van.js binding

**Reported by the human after trying the real UI**: on the recipe create/edit form, the title
and other text fields couldn't be typed into. Root cause, confirmed by reading `van.js`'s
source directly (not guessed): its dependency tracker (`stateProto`'s `get val()`) records a
`.val` read against whatever binding function is *currently executing*, unconditionally,
regardless of how deep in the call stack that read happens — `curDeps?._getters?.add(this)` has
no notion of "this state was created inside the function I'm now calling," only "was a binding
function active when this getter ran." `RecipeForm.js`'s `buildForm()` read
`value: title.val` (and the same for every other scalar field) directly, as a bare expression,
while itself being invoked synchronously from inside `RecipeForm()`'s own `() => {...}` binding
— so `title` (a `van.state()` freshly created *inside* `buildForm()`, never exposed outside it)
got silently recorded as a dependency of that *outer* binding. Every keystroke's `oninput`
handler then wrote `title.val = ...`, which retriggered the outer binding, which called
`buildForm()` again from scratch — creating a brand-new `title` state reset to its original
initial value and replacing the whole form's DOM subtree. That's what "not editable" actually
was: each character appeared to vanish because the entire form was silently rebuilt out from
under the user on every single keystroke.

The same pattern existed in `RecipeList.js`'s `Toolbar()` (`value: state.tagFilter.val`), one
level up — since `Toolbar()` is constructed from inside `app.js`'s top-level `Root()` binding,
that read would have tainted `Root()` itself, remounting the *entire app* on every debounced
tag-filter navigation, not just the input.

**Fix**: `tag()`'s own prop-handling logic (also read directly, not inferred) already has a
principled way to bind a specific attribute reactively *without* tainting the enclosing scope —
pass the `State` object itself as the prop value (`value: title`, not `value: title.val`), which
`tag()` detects (`protoOf(v) === stateProto`) and wraps in its own dedicated, isolated `bind()`
call for just that one attribute. Every scalar-field `value:` prop in `RecipeForm.js` and the
tag-filter input in `RecipeList.js` were changed from `.val` reads to bare state references.
This is also a strictly better fix than "downgrade to a plain uncontrolled variable" would have
been, since it keeps the fields genuinely reactive if `state.tagFilter` (or a form field) is
ever driven from outside the input itself.

**Verified with a real reproduction, not just source-reading**: built a minimal headless DOM
stub (`document.createElement`, a fake element with a real `value` property setter,
`appendChild`/`replaceWith`, event listeners) and imported the *actual* `van.js` and
`RecipeForm.js` modules against it in Node — mounted a real `RecipeForm()`, fired real `input`
events one keystroke at a time with a `queueMicrotask` flush between each (matching `van.js`'s
own async DOM-update scheduling), and asserted both that the title `<input>` DOM node identity
stayed the same across keystrokes and that its value accumulated correctly. Confirmed this
reproduction actually fails against the pre-fix code (the input got replaced on the very first
keystroke and its value reset to `""`, exactly matching the reported symptom) and passes against
the fix — not a test that happened to pass either way.

## Phase 7: meal planning API

- **`meal_slot` is a fixed set** (`breakfast`, `lunch`, `dinner`, `snack`), validated in the
  handler rather than a DB `CHECK`/enum. The planned UI is a date x slot grid; free text would
  scatter "Dinner"/"dinner"/"supper" into separate rows. Kept out of the schema so adding a slot
  later is a code change, not a migration.
- **A `recipe_id` that's unknown or not yours is a single 403**, via one owner-scoped
  `findById`. This is the "referenced id" case the Phase 5 entry reserved 403 for; checking
  existence separately to return 404 for nonexistent ids would let a caller probe whether
  another user's recipe id is real.
- **Planning a soft-deleted recipe is a 422**, not a 403/404 — the recipe is yours and exists,
  it just can't be acted on. Existing entries for a recipe that gets soft-deleted afterward are
  untouched and still returned (joined title + `recipeDeleted: true`) — the reason recipe
  deletion is soft in the first place.
- **List requires both `from` and `to`, capped at 366 days**, so the query is always bounded.
- **No update endpoint** — the plan scoped Phase 7 to GET/POST/DELETE; moving an entry is
  delete + create. Easy to add if the Phase 9b UI wants drag-to-move.
- **Entry deletion is a hard delete** — nothing references a meal-plan entry.

## Meal plan is a flat list with optional labels — reverses Phase 7's dates and fixed slots

The human's direction after reviewing Phase 7's first cut: "For a meal plan, we just want a list
of recipes, and optional labels. We don't want fixed meal slots." This supersedes the Phase 7
entry above on slots, date ranges, and the 366-day cap. Read as dropping calendar dates as well
as slots — "just a list" — so a meal plan is one running list per user, each entry a recipe, an
optional free-text label (e.g. "Monday", "for guests"), and the servings multiplier (kept:
Phase 8 needs it to scale quantities).

- New migration `0003_meal_plan_labels.sql` rather than editing `0001` — `0001` has already been
  applied to real databases (including the human's own Docker deployment). It drops
  `plan_date`/`meal_slot` and adds nullable `label`; existing entries keep their recipe and
  multiplier, their old date/slot is discarded rather than folded into a label (verified: a
  pre-`0003` database migrates cleanly with its entry intact).
- Label is trimmed, blank becomes null, capped at 100 characters. Free text on purpose — unlike
  the fixed slots it replaces, labels aren't meant to line up into grid rows.
- Phase 8's meal-plan source becomes `{ from_meal_plan: true }` (the whole list) instead of a
  date range.
