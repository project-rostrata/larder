# Self-hosted recipe & meal-planning app — project brief

This is a handoff document from a planning conversation, written the same way `shelf`'s was:
it captures decisions already made, the reasoning behind them, and what's still open. Treat
section 2 as hard constraints, section 4 as decisions not to relitigate without flagging it to
the human first, section 5 as things to check with the human before building, and everything
else as a reasonable default that can be refined.

## 1. What this is

A self-hosted web app for storing, importing, and organizing recipes, and for turning a
selection of them into a combined shopping list — replacing Nextcloud Cookbook. Same
development ethos as `shelf` (this project's predecessor, a self-hosted file-sharing app) in
spirit — small, auditable, low-dependency — but **not** in storage architecture: unlike
`shelf`, larder has no filesystem-backed content at all (see section 4). No image storage or
handling in v1 — explicitly out of scope per the human.

The standout feature, and the main reason this app exists rather than just using Nextcloud
Cookbook: select recipes into a meal plan and generate a shopping list that **combines
ingredients required by multiple recipes into one list item**, instead of listing each
recipe's ingredients separately.

**Recipe curation/discovery is explicitly out of scope.** Recipes are entered or imported one
at a time by a human, never bulk-imported or pulled from a built-in catalog — larder is not
trying to be a recipe-discovery app. (A paid competing app the human currently uses is more of
a curation/discovery tool; larder is deliberately narrower — see section 4's shopping-list
notes for the one thing that app does that's worth learning from.)

## 2. Non-negotiable constraints

- **Backend language: Kotlin.** Start the same way `shelf` did: written directly against the
  JDK standard library, no web framework (not Spring, not Ktor) — `com.sun.net.httpserver.HttpServer`
  for HTTP serving/routing (hand-rolled router), `java.sql`/JDBC for Postgres access,
  `javax.crypto.SecretKeyFactory` (`PBKDF2WithHmacSHA256`) for password hashing.
- **Dependency and build-tool policy is deliberately looser than `shelf`'s.** `shelf` never
  needed anything beyond the Postgres JDBC driver and `kotlinx.*`, compiled with bare `kotlinc`
  and no build tool. This app's problem domain — parsing recipes out of arbitrary web pages,
  parsing free-text ingredient lines into structured quantities/units, combining them across
  recipes — is genuinely harder, and the human has explicitly said it's fine to reach for a
  real build tool (Gradle) and external libraries when a specific need justifies it (e.g. a
  proper HTML parser for recipe import, if regex-based JSON-LD extraction turns out to be
  insufficient in practice). The discipline stays the same as `shelf`'s: **propose the specific
  dependency (or the move to Gradle) and the specific need it solves, get sign-off, then add
  it** — never add one by default or "to save time." Start on bare `kotlinc` with zero
  non-`kotlinx` dependencies, same as `shelf`'s Phase 1 pattern, and graduate only when a real
  wall is hit.
- **Frontend: VanJS.** No bundler, no npm dependency tree — same as `shelf`. Nothing about this
  app's frontend complexity currently argues for anything different.
- **Database: PostgreSQL, and it is the source of truth.** Unlike `shelf` (where Postgres is
  strictly a metadata/index cache over a filesystem that's the real source of truth), larder
  has no filesystem-backed content at all — recipes, meal-plan entries, and shopping lists are
  all real rows in Postgres, full stop. See section 4 for why this diverges from `shelf`'s
  file-first philosophy.
- **Deployment: Docker / docker-compose.** App container + Postgres container. No bind-mounted
  user-storage volume — there's no user-facing filesystem content to mount (see section 8).
- **No images anywhere in v1.** No recipe photo upload, no image proxying/thumbnailing during
  URL import, no image fields in the data model. This is a deliberate scope cut, not an
  oversight — it removes an entire subsystem (storage, proxying, thumbnailing) that a "replace
  Nextcloud Cookbook" app would otherwise need.

## 3. Architecture

```
Browser (VanJS UI)
      |
      v
App server (Kotlin, JDK stdlib HTTP, REST/JSON API)
      |                              |
      v                              v
  Postgres                   External recipe URLs
 (users, sessions,          (fetched at import time
  recipes, ingredients,      only — never stored or
  meal plan, shopping        proxied by this app)
  lists — all of it,
  no filesystem layer)
```

There is no filesystem layer in this architecture at all — that's the headline difference from
`shelf`'s three-way split between browser, app server, and a filesystem-plus-Postgres-index
pair. Postgres is simply where everything durable lives.

## 4. Key design decisions already made (with rationale)

**Language: Kotlin, same rationale as `shelf`.** Not re-litigated here — see `shelf`'s own
brief if the reasoning is needed again (data classes, sealed result types, JDK stdlib, lower
boilerplate than Java without a framework dependency).

**Recipe storage: normalized Postgres tables, not files — a deliberate divergence from
`shelf`'s file-as-truth philosophy.** The brief originally specified one JSON file per recipe,
mirroring `shelf` directly. That was reversed during planning, before any code existed, for
three concrete reasons:
1. The shopping-list feature (the whole reason this app exists) needs *relational* ingredient
   data — combining quantities across recipes is naturally a join/aggregation over rows, not
   something a flat-file-plus-cache-index model does any favors for.
2. Meal-plan entries and shopping lists were already going to be plain Postgres rows with no
   file backing (they have no natural file form) — recipes being file-backed would have been
   the *only* place in the app that needed `shelf`'s reconciliation machinery (mtime scanning,
   a nightly backstop scan, a path-safety layer, optimistic-concurrency conflict handling on
   writes) at all.
3. That machinery exists in `shelf` to support a real use case — editing files over SMB/SSH
   outside the app while the app also serves them. Recipes don't have an equivalent use case:
   in practice, all edits go through this app. Paying for reconciliation complexity with no
   corresponding benefit isn't a good trade.
   
   A JSON-export feature (for backup/portability, letting someone still get their recipes out
   as plain files on demand) was discussed as a middle ground and **explicitly deferred** —
   not built in v1, but not ruled out either; see section 6.
- See `docs/decisions.md` for this decision recorded as a dated log entry, alongside the
  original (now-superseded) file-based decision it replaces — that log is append-only and
  doesn't get rewritten, so both entries stay visible.

**Accounts: multi-user, ownership via a foreign key, not a home directory.** Each row
(`recipes`, `meal_plan_entries`, `shopping_lists`) carries an `owner_id`, scoping it to a user
— the same shape `meal_plan_entries`/`shopping_lists` already had from the start, now applied
to recipes too. There is no per-user directory on disk to create or manage. A household that
wants one shared collection can just use one account.

**Recipe data model.** Ingredients are a proper child table (`recipe_ingredients`), not a JSON
blob column, specifically so shopping-list generation can query/aggregate them relationally.
`raw_text` is the original ingredient line as entered or imported, always preserved and always
what's shown by default in the UI. `notes` is the parsed-out trailing clause (e.g. "sifted").
`quantity` is stored as an **exact fraction** — `quantity_numerator`/`quantity_denominator`
integers, reduced to lowest terms — not a decimal or float. This is a deliberate refinement over
what both real OSS competitors researched for this decision actually do (see the
ingredient/unit canonicalization note below): a fraction scales exactly (`1/3 × 3 = 1`, not
`0.999...`) and displays the way ingredient quantities are natively written ("2 1/2 cups").
Instructions are a simple ordered list (`TEXT[]` column on `recipes` — no need for a child
table there, nothing downstream needs to query individual steps relationally). See section 7
for the full sketch.

**Ingredient and unit canonicalization — informed by researching Mealie, Tandoor Recipes, and
Grocy.** Before building this, the three most relevant OSS projects in this space were
researched specifically for how they solve (a) recognizing that two different ingredient names
refer to the same thing (e.g. "scallion" and "green onion"), (b) unit conversion, and (c)
shopping-list combination and recipe scaling. Findings, and what larder does with them:

- **Ingredient identity has no automated solution anywhere in this space, confirmed twice
  over.** Mealie has a canonical `IngredientFood` entity with an alias list, but a maintainer
  confirmed alias creation is manual — there's no semantic/NLP matching. Tandoor is exact-match
  only, with two manual recovery mechanisms: user-authored rewrite rules applied before lookup,
  and an admin `merge_into` action that folds a duplicate into the canonical entity after the
  fact, reassigning every row that referenced it. larder adopts the same shape as the honest,
  achievable v1 answer: a canonical `ingredients` table, an `ingredient_aliases` table
  (exact-match lookup only, after normalization — lowercase, trim), and a `merge-into`
  operation matching Tandoor's pattern for fixing duplicates after the fact. A first-seen
  ingredient name auto-creates a new canonical row rather than blocking recipe entry on
  curation — the vocabulary is meant to improve over time, not be complete on day one.
- **The create/import API tells the caller which ingredient references were matched to an
  existing canonical ingredient vs. newly created during that call.** This falls out of the
  get-or-create lookup for free — no extra persistent column needed. This is the hook a future
  "this ingredient is unrecognized, want to link it to an existing one?" affordance on the
  recipe entry/import view would use. The human has explicitly asked for the data model to
  support this now, without designing that UI yet — this is that support; the UI is
  intentionally undesigned.
- **Units split into two layers, synthesizing what Mealie does well with what Tandoor and Grocy
  independently confirmed.** Most units (volume: tsp/tbsp/cup/fl oz/…; mass: g/kg/oz/lb) belong
  to a `dimension` and declare a `to_base_factor` — how many of a shared per-dimension base unit
  (milliliters for volume, grams for mass) one of them equals. Converting between any two units
  in the same dimension is then arithmetic, not a maintained pairwise table — this is Mealie's
  `standard_quantity`/`standard_unit` design, which is cleaner than a hardcoded conversion
  table. Count-style units ("clove," "can," "bunch," "pack") have no universal factor — "1
  clove of garlic ≈ 3g" is a fact about garlic, not about the word "clove" — so a small
  `unit_conversions` table holds these as exceptions, each row optionally scoped to a specific
  `ingredient_id`. Tandoor (a per-food `UnitConversion` model) and Grocy (per-product "QU
  Conversions") independently arrived at the same scoped-exception pattern, which is stronger
  validation than either alone. Unlike Tandoor, v1 does not chain multi-hop conversions (its
  BFS over a conversion graph, e.g. pinch→tsp→gram) — a direct lookup (same unit, same
  dimension, or one matching `unit_conversions` row) is enough for v1 and meaningfully simpler.
  A bare quantity with no unit word (`unit_id` is `NULL`, e.g. "3 eggs") is treated as a count
  of the ingredient itself and needs no conversion at all to combine with another bare count of
  the same ingredient.
- **`ingredients`, `ingredient_aliases`, `units`, and `unit_conversions` are global,
  instance-wide tables — not `owner_id`-scoped, unlike every other table in this app.** This is
  a deliberate exception to the ownership rule in `AGENTS.md`, not an oversight the Phase 11
  hardening audit should flag: a self-hosted larder instance is fundamentally a single
  household, shared vocabulary curation compounds in value across whoever uses that instance,
  and duplicating "flour"/"salt"/"egg" per account would be pure waste. Units are additionally
  a small, fixed, developer-seeded vocabulary (not user-grown), so unit spelling variants
  (`tbsp`/`tablespoon`/`tablespoons`) live as a plain `aliases TEXT[]` column directly on
  `units` rather than a child table — `ingredient_aliases` stays a full child table because
  that vocabulary is open-ended and grows from user curation over time.

**Recipe URL import: schema.org JSON-LD, no HTML-parsing library in v1.** Fetch the page with
`java.net.http.HttpClient` (already in the JDK — zero new dependency), extract
`<script type="application/ld+json">` blocks with a regex, and parse each with
`kotlinx.serialization.json` looking for an object whose `@type` contains `"Recipe"` (directly,
or nested inside an `@graph` array — both are common). This covers the large majority of recipe
sites today, since nearly every recipe SEO plugin/CMS emits schema.org JSON-LD for search-engine
rich results. Sites with no structured data at all are out of scope for automatic import in
v1 — the recipe can still be entered by hand. Confirmed directly against the `recipe-scrapers`
library's source during this research pass: schema.org's `recipeIngredient` is always a list of
raw, unparsed strings — no site or scraping library hands over structured quantity/unit/name,
so larder's own ingredient-line parser (below) is load-bearing for every import, not just a
fallback. **If JSON-LD proves too lossy in practice** (e.g. sites that only emit the older
`itemprop` microdata format, which needs a real DOM to extract reliably), a proper HTML parser
library (e.g. `jsoup`) is the anticipated next step — flagged here as an expected future
dependency request per section 2's policy, not pre-approved.

**Ingredient-line parsing is its own standalone module, built and tested before anything
depends on it.** Neither schema.org's `recipeIngredient` nor hand-typed ingredient entry gives
structured data for free — something has to turn a raw line into a quantity, unit, and
ingredient. v1's approach is a regex-based heuristic parser: parse a leading mixed
number/fraction/decimal as the quantity fraction, match the next token against the unit
vocabulary (including its aliases) to resolve `unit_id`, treat a trailing comma-clause as
`notes`, and resolve whatever's left as the name against `ingredients`/`ingredient_aliases`
(exact match, auto-creating a new canonical ingredient on a miss, per the canonicalization
note above) to get `ingredient_id`. This is expected to be the single hardest and
most-iterated piece of the app, and it will get real lines wrong sometimes. Context from
researching the field: the closest real prior art (a CRF model trained on ~100k lines, which
Mealie itself ships, and its modern open-source successor `strangetom/ingredient-parser`,
94.9% sentence-level accuracy on 81k+ training sentences) is real machine learning, not
regex — larder's hand-rolled parser will not match that accuracy, and that's an accepted
tradeoff, not an oversight. It's acceptable because failure degrades gracefully: `raw_text` is
always preserved and always what displays by default, so a bad parse means an ingredient
doesn't get auto-combined on a shopping list — never a data-loss or wrong-display failure. The
manual-merge escape hatch below is the accepted workaround for exactly this gap.

**Recipe scaling.** A recipe's `servings` (numeric) is what ingredient quantities are written
against; a separate `servings_text` (free-text, e.g. "4–6 servings", display-only, never used
in scaling math — a distinction Tandoor's data model already makes and larder adopts directly)
holds whatever a recipe actually says about yield when that isn't a single clean number. Scale
factor = desired servings ÷ `recipes.servings`, applied to each ingredient's quantity fraction
(multiply and reduce) at render or shopping-list-generation time — the stored recipe is never
mutated. Scaling isn't gated behind meal planning specifically; viewing a single recipe scaled
(e.g. "×2") is the same operation. This settles what was an open question in earlier drafts of
this brief.

**Shopping list generation and combination — the core feature, confirmed to be a real gap even
in the most mature OSS competitor researched.** Tandoor Recipes — the most feature-rich of the
three apps researched for this decision — does not combine ingredients across recipes at all;
it emits one shopping-list line per recipe-ingredient and only sorts them for display adjacency.
Mealie does combine, and its logic (`can_merge`/`merge_items`) is the concrete reference
larder's own logic is modeled on. The user selects a set of recipes (directly, or via a
meal-plan date range, scaled by each entry's `servings_multiplier`), the server gathers every
ingredient across them, and:
- **Two ingredient lines combine when they resolve to the same `ingredient_id`** and either
  share a `unit_id`, or their units are convertible (same dimension via `to_base_factor`, or a
  matching `unit_conversions` row). As a free win beyond curated-ingredient matching: two
  *unresolved* lines (`ingredient_id` still `NULL`) with identical normalized `raw_text` also
  combine — a literal string match needs no curation to be safe. Lines that don't resolve to a
  shared ingredient, or resolve but have no compatible unit path, are listed separately.
  **Volume-to-mass conversion remains an explicit non-goal** — it depends on ingredient
  density, a different and harder problem.
- **Each contributing recipe's original contribution is preserved, not collapsed away**, in a
  `shopping_list_item_sources` row per source: a snapshot of that recipe's title, the specific
  ingredient line's `raw_text`, and its quantity/unit *as scaled by the meal plan but before
  unit conversion* — so the combined item can show a converted total ("1 lb flour") while still
  answering "which recipes want this, and how much did each actually call for" ("4 cups" from
  one recipe, "3 tbsp" from another) on demand. This is a stated requirement, not a nice-to-have:
  the combined display unit is derived, but the original per-recipe units always stay
  available. These rows are snapshots, not live joins — a source recipe being edited or
  deleted later must not corrupt or blank out a previously generated list.
- **A generated list is saved, not just computed and discarded**, and supports both automatic
  and manual editing: items can be checked off, added, or removed by hand, and — new in this
  revision — **two or more existing items can be manually merged into one** after generation.
  This is the explicit, accepted workaround for whatever the automatic matching misses (the
  same tradeoff the paid competing app referenced in section 1 makes: not perfect, backed by an
  easy manual fix). A manual merge unions the merged items' `shopping_list_item_sources` rows
  (so "which recipes wanted this" stays correct) and sums quantities when units are compatible.
  The exact display treatment when they aren't compatible is deliberately left open — the human
  has asked for the data model to support this operation now without the UI being designed yet.

## 5. Open questions — need a human decision before being built

- **Recipe organization/browsing.** Proposed default: freeform tags (a `TEXT[]` column) plus a
  simple listing/filter-by-tag view; full-text search deferred to v1.5, same as `shelf`
  deferred its own search feature.
- **Import/entry-time "is this ingredient known?" UI.** Decided that the API will expose which
  ingredient references were matched to an existing canonical ingredient vs. newly created
  during a create/import call (see section 4) — that decision is settled. What the recipe
  entry/import view actually does with that information (inline indicator? a confirmation
  step? silent?) is explicitly undesigned — deferred, not forgotten.
- **Manual shopping-list-item merge UI.** Decided that the API supports merging two or more
  generated shopping-list items into one (see section 4) — that decision is settled. The
  interaction itself (multi-select? drag-together? a "combine with…" picker?) and the display
  treatment when merged items have incompatible units are both explicitly undesigned — deferred,
  not forgotten.
- **Nutrition info.** Nextcloud Cookbook and schema.org's `Recipe` type both support it.
  Proposed default: deferred out of v1 entirely — not mentioned as a goal, adds scope to both
  the data model and the importer.
- **Print/export view for a single recipe.** Proposed default: deferred to v1.5.
- **Shopping list grouped by store aisle/category.** Would materially improve the shopping-list
  feature's real-world usefulness but needs either a user-maintained aisle-mapping table or a
  built-in guess table. Proposed default: deferred to v1.5 — v1 ships one flat, combined list.
- **JSON export for backup/portability.** Discussed as a middle ground when the storage-model
  decision was made (section 4) and explicitly deferred, not ruled out. Proposed default: not
  in v1; revisit in v1.5 if losing `shelf`'s "just files" portability turns out to matter in
  practice once the app is actually in use.

## 6. Suggested scope phasing

**v1 (MVP)**
- Accounts + auth (username/password, PBKDF2 hashing, cookie-based sessions) — same pattern as
  `shelf`
- Recipe CRUD: create, view, edit, delete, stored as normalized Postgres rows
- Recipe URL import (schema.org JSON-LD)
- Ingredient-line parser (structured quantity/unit/name from free text)
- Meal planning: assign recipes to calendar dates/slots, with a per-entry servings multiplier
- Shopping list generation from a set of recipes or a meal-plan date range, with combination,
  persisted and editable (check off, add/remove items by hand)
- Responsive VanJS UI

**v1.5**
- Fuzzy/synonym ingredient-name matching for better shopping-list combination
- Full-text/tag search
- Nutrition info
- Print/export view
- Shopping list grouped by aisle/category
- JSON export for backup/portability (see section 5)
- HTML-microdata import fallback (likely needs `jsoup` — see section 4)

**v2 / stretch**
- Public share links or user-to-user sharing, if ever wanted (not a stated goal today)
- Multi-household support beyond "one account per household"
- Pantry/inventory tracking

## 7. Data model sketch (Postgres)

```
users(id, username, password_hash, created_at, is_admin)
sessions(id, user_id, created_at, expires_at)

-- Global, instance-wide — NOT owner_id-scoped; see section 4's canonicalization note.
ingredients(id, name, plural_name NULL)
ingredient_aliases(id, ingredient_id, alias)                    -- exact-match lookup only
units(id, name, abbreviation NULL, dimension,                    -- dimension: volume | mass | count
      to_base_factor NULL, aliases TEXT[])                       -- NULL factor for count-dimension units
unit_conversions(id, from_unit_id, to_unit_id, factor,
                  ingredient_id NULL)                             -- NULL = global exception, else scoped

recipes(id, owner_id, title, source_url NULL, servings NUMERIC NULL, servings_text NULL,
        prep_time_minutes NULL, cook_time_minutes NULL, total_time_minutes NULL, tags TEXT[],
        instructions TEXT[], created_at, updated_at)
recipe_ingredients(id, recipe_id, position, raw_text, notes NULL,
                    quantity_numerator NULL, quantity_denominator NULL,
                    unit_id NULL, ingredient_id NULL)

meal_plan_entries(id, owner_id, plan_date, meal_slot, recipe_id, servings_multiplier,
                   created_at)

shopping_lists(id, owner_id, name, created_at)
shopping_list_items(id, shopping_list_id, ingredient_id NULL, raw_text,
                     quantity_numerator NULL, quantity_denominator NULL, unit_id NULL,
                     checked, sort_order)
-- One row per recipe that contributed to a shopping_list_item — see section 4. Snapshotted
-- (recipe_title, raw_text, quantity, unit as that recipe actually called for it), not a live
-- join, so a generated list survives its source recipes later being edited or deleted.
shopping_list_item_sources(id, shopping_list_item_id, recipe_id NULL, recipe_title,
                            meal_plan_entry_id NULL, raw_text,
                            quantity_numerator NULL, quantity_denominator NULL, unit_id NULL)
```

Every table here is a real, authoritative table — none of it is a cache of anything else. This
is the main structural difference from `shelf`'s data model, where `file_index` was explicitly
*not* authoritative over the filesystem. `ingredients`/`ingredient_aliases`/`units`/
`unit_conversions` are the one deliberate exception to `owner_id` scoping in the whole schema —
see section 4.

## 8. Docker and storage notes

Much simpler than `shelf`'s, because there's no user-facing filesystem content to manage:
- `docker-compose.yml` defines the app service and the Postgres service. Postgres's own data
  directory uses a named Docker volume (same as `shelf`'s `shelf-postgres-data` pattern) — that
  volume is infrastructure for Postgres itself, not a user-facing storage mount.
- **No bind-mounted storage directory, and no `PUID`/`PGID` entrypoint pattern** — both existed
  in `shelf` specifically to make host-filesystem files readable/writable by both the container
  and the host user, which only matters when there's a bind mount in the first place.
- An optional reverse-proxy service (e.g. Caddy) for TLS, same as `shelf`, kept optional.

## 9. Notes for the agent picking this up

- Section 2 is settled, **except** that the dependency/build-tool policy is deliberately looser
  than `shelf`'s — raising a new dependency or a move to Gradle is expected to happen at some
  point in this project, unlike `shelf`. Raise it explicitly when it does; don't add it
  silently either way.
- Section 4's storage-model decision (Postgres, not files) is settled — don't reintroduce
  file-backed recipe storage or `shelf`'s reconciliation/path-safety patterns without flagging
  it to the human first, the same way any other section-4 decision would be treated.
- Section 4's ingredient/unit canonicalization model (curated aliases, no automatic semantic
  matching, the two-layer unit-conversion design, quantities as exact fractions, and
  `ingredients`/`ingredient_aliases`/`units`/`unit_conversions` being global rather than
  `owner_id`-scoped) is settled and was arrived at by directly researching Mealie, Tandoor
  Recipes, and Grocy — see `docs/decisions.md` for the full findings. Don't propose a fuzzy/ML
  ingredient-matching scheme or re-scope those four tables to `owner_id` without flagging it
  first; both were considered and deliberately rejected/scoped this way.
- Section 5 items need a human decision before being built — surface the question rather than
  guessing, same as `shelf`'s brief asked.
- Everything else here is a working default, not gospel — reasonable refinements are expected
  as implementation details get worked out, same as `shelf`.
