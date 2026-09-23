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
Each row is `{quantity, unit, name, notes, raw_text}`: `raw_text` is the original ingredient
line as entered or imported, always preserved and always what's shown by default in the UI;
`quantity`/`unit`/`name`/`notes` are the parsed-out structured fields used for shopping-list
combination, editable by hand when the parse is wrong. Instructions are a simple ordered list
(`TEXT[]` column on `recipes` — no need for a child table there, nothing downstream needs to
query individual steps relationally). See section 7 for the full sketch.

**Recipe URL import: schema.org JSON-LD, no HTML-parsing library in v1.** Fetch the page with
`java.net.http.HttpClient` (already in the JDK — zero new dependency), extract
`<script type="application/ld+json">` blocks with a regex, and parse each with
`kotlinx.serialization.json` looking for an object whose `@type` contains `"Recipe"` (directly,
or nested inside an `@graph` array — both are common). This covers the large majority of recipe
sites today, since nearly every recipe SEO plugin/CMS emits schema.org JSON-LD for search-engine
rich results. Sites with no structured data at all are out of scope for automatic import in
v1 — the recipe can still be entered by hand. **If JSON-LD proves too lossy in practice** (e.g.
sites that only emit the older `itemprop` microdata format, which needs a real DOM to extract
reliably), a proper HTML parser library (e.g. `jsoup`) is the anticipated next step — flagged
here as an expected future dependency request per section 2's policy, not pre-approved.

**Ingredient-line parsing is its own standalone module, built and tested before anything
depends on it.** Neither schema.org's `recipeIngredient` (a list of free-text strings like
`"2 1/2 cups all-purpose flour, sifted"`) nor hand-typed ingredient entry gives structured data
for free — something has to turn a raw line into `{quantity, unit, name, notes}`. v1's approach
is a regex-based heuristic parser: parse a leading mixed number/fraction/decimal as quantity,
match the next token against a fixed unit vocabulary, treat a trailing comma-clause as notes,
and take what's left as the name. This is expected to be the single hardest and most-iterated
piece of the app, and it will get real lines wrong sometimes — `raw_text` always being preserved
and always being what displays by default is the safety net for that, not a footnote.

**Shopping list generation and combination — the core feature.** The user selects a set of
recipes (directly, or via a meal-plan date range), the server gathers every parsed ingredient
across them (a straightforward query against `recipe_ingredients`, no file reads involved), and
combines items that share a normalized name and a compatible unit:
- **Name matching in v1: exact, after normalization (lowercase, trim) only.** No stemming, no
  fuzzy matching, no synonym table (`"scallion"` and `"green onion"` will *not* combine). This
  is a known, explicit limitation — see section 5.
- **Unit combination happens within a unit *family* only**, via a small hand-rolled conversion
  table: volume (tsp/tbsp/cup/fl oz/pint/quart/gallon) and mass (g/kg, oz/lb) each convert
  within themselves. **Volume-to-mass conversion is an explicit non-goal** — it depends on
  ingredient density, which is a different, much harder problem, and v1 does not attempt it.
  Items that can't be combined (mismatched unit families, unparsed quantity, "to taste") are
  listed as separate line items rather than guessed at.
- The generated list is **saved, not just computed and discarded** — items can be checked off
  and hand-edited (add/remove/adjust) afterward, e.g. during an actual shopping trip.

## 5. Open questions — need a human decision before being built

- **Recipe organization/browsing.** Proposed default: freeform tags (a `TEXT[]` column) plus a
  simple listing/filter-by-tag view; full-text search deferred to v1.5, same as `shelf`
  deferred its own search feature.
- **Recipe scaling.** Proposed default: each meal-plan entry carries a `servings_multiplier`
  that scales ingredient quantities before shopping-list combination; the recipe row itself
  always stores its original-serving-size quantities, never a scaled copy.
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
recipes(id, owner_id, title, source_url NULL, servings NULL, prep_time_minutes NULL,
        cook_time_minutes NULL, total_time_minutes NULL, tags TEXT[], instructions TEXT[],
        created_at, updated_at)
recipe_ingredients(id, recipe_id, position, quantity NUMERIC NULL, unit TEXT NULL, name TEXT,
                    notes TEXT NULL, raw_text TEXT)
meal_plan_entries(id, owner_id, plan_date, meal_slot, recipe_id, servings_multiplier,
                   created_at)
shopping_lists(id, owner_id, name, created_at)
shopping_list_items(id, shopping_list_id, name, quantity NULL, unit NULL, raw_text,
                     checked, source_recipe_ids INTEGER[], sort_order)
```

Every table here is a real, authoritative table — none of it is a cache of anything else. This
is the main structural difference from `shelf`'s data model, where `file_index` was explicitly
*not* authoritative over the filesystem.

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
- Section 5 items need a human decision before being built — surface the question rather than
  guessing, same as `shelf`'s brief asked.
- Everything else here is a working default, not gospel — reasonable refinements are expected
  as implementation details get worked out, same as `shelf`.
