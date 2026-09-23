# Self-hosted recipe & meal-planning app — project brief

This is a handoff document from a planning conversation, written the same way `shelf`'s was:
it captures decisions already made, the reasoning behind them, and what's still open. Treat
section 2 as hard constraints, section 4 as decisions not to relitigate without flagging it to
the human first, section 5 as things to check with the human before building, and everything
else as a reasonable default that can be refined.

## 1. What this is

A self-hosted web app for storing, importing, and organizing recipes, and for turning a
selection of them into a combined shopping list — replacing Nextcloud Cookbook. Same
development ethos as `shelf` (this project's predecessor, a self-hosted file-sharing app):
small, auditable, low-dependency, with a real filesystem as the source of truth rather than a
database. No image storage or handling in v1 — explicitly out of scope per the human.

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
- **Database: PostgreSQL**, used strictly as a metadata/index store — an index over recipe
  files and structured data for meal plans and shopping lists. It is never the sole home of
  anything meant to be a durable, human-editable file; see storage below.
- **Storage: a real filesystem**, path configured by the deployer, local disk or NFS — the app
  must not assume or depend on which. Each recipe is one file, in a human-readable format,
  editable outside the app.
- **Deployment: Docker / docker-compose.** App container + Postgres container + a bind-mounted
  storage directory — same shape as `shelf`.
- **No images anywhere in v1.** No recipe photo upload, no image proxying/thumbnailing during
  URL import, no image fields in the storage format. This is a deliberate scope cut, not an
  oversight — it removes an entire subsystem (storage, proxying, thumbnailing) that a "replace
  Nextcloud Cookbook" app would otherwise need.

## 3. Architecture

```
Browser (VanJS UI)
      |
      v
App server (Kotlin, JDK stdlib HTTP, REST/JSON API)
      |                |                    |
      v                v                    v
  Postgres        Filesystem          External recipe URLs
 (users,         (recipe JSON        (fetched at import time
  sessions,       files; meal         only — never stored or
  recipe/meal-    plan + shopping     proxied by this app)
  plan/shopping-  list data)
  list index)
```

Postgres holds a **cache/index** of recipe files' state (path, title, tags, timings, mtime),
not a mirror of their content — exactly `shelf`'s `file_index` pattern, applied to recipes
instead of arbitrary files. The filesystem is always ground truth for a recipe's content. Meal
plan entries and shopping lists are structured data with no natural "file" representation, so
those live in Postgres as real rows, not a cache of anything.

## 4. Key design decisions already made (with rationale)

**Language: Kotlin, same rationale as `shelf`.** Not re-litigated here — see `shelf`'s own
brief if the reasoning is needed again (data classes, sealed result types, JDK stdlib, lower
boilerplate than Java without a framework dependency).

**Filesystem/database reconciliation strategy: identical to `shelf`'s.** Real-time filesystem
watching (inotify) is unreliable over NFS, which this app must support. So: on every recipe
listing request, `stat()` the user's recipe directory; if its mtime has changed since
`last_scanned_at`, non-recursively rescan and upsert the index. A periodic full recursive scan
runs as a backstop. No inotify or filesystem-watch API anywhere in this path.

**Accounts: multi-user, same per-user home-directory model as `shelf`.** Each user gets a home
directory (`<storage root>/<username>/recipes/`) — mirrors `shelf`'s username-keyed layout so
the same operational pattern (point the app at an existing username-keyed directory tree) still
applies. A household that wants one shared collection can just use one account; nothing in v1
requires per-user collections to be used that way.

**Recipe storage format: one JSON file per recipe.** Structured fields throughout — including
ingredients as a list of objects, not freeform text — because the shopping-list combination
feature (the whole reason this app exists) needs machine-parseable quantity/unit/name per
ingredient, not prose. Each ingredient object is `{quantity, unit, name, notes, raw_text}`:
`raw_text` is the original ingredient line, always preserved and always what's shown by
default in the UI; `quantity`/`unit`/`name`/`notes` are the parsed-out structured fields, used
for shopping-list combination and editable by hand when the parse is wrong. A recipe file looks
roughly like:

```json
{
  "title": "Weeknight Chili",
  "source_url": "https://example.com/weeknight-chili",
  "servings": 4,
  "prep_time_minutes": 15,
  "cook_time_minutes": 45,
  "tags": ["dinner", "beef", "one-pot"],
  "ingredients": [
    {"quantity": 1, "unit": "lb", "name": "ground beef", "notes": null, "raw_text": "1 lb ground beef"},
    {"quantity": 2, "unit": "tbsp", "name": "chili powder", "notes": null, "raw_text": "2 tbsp chili powder"},
    {"quantity": null, "unit": null, "name": "salt", "notes": "to taste", "raw_text": "salt, to taste"}
  ],
  "instructions": [
    "Brown the ground beef in a large pot over medium-high heat.",
    "Stir in the chili powder and cook for another minute.",
    "..."
  ]
}
```

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
across them, and combines items that share a normalized name and a compatible unit:
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

Unlike `shelf`'s brief (written after these were already resolved), several of these are
genuinely still open. Defaults are proposed for each so a phase can start from something
concrete, but flag it back to the human before locking it in if it matters to them:

- **Recipe organization/browsing.** Proposed default: freeform tags (as in the format above)
  plus a simple listing/filter-by-tag view; filename/full-text search deferred to v1.5, same
  as `shelf` deferred its own search feature.
- **Recipe scaling.** Proposed default: each meal-plan entry carries a `servings_multiplier`
  that scales ingredient quantities before shopping-list combination; the recipe file itself
  always stores its original-serving-size quantities, never a scaled copy.
- **Nutrition info.** Nextcloud Cookbook and schema.org's `Recipe` type both support it.
  Proposed default: deferred out of v1 entirely — not mentioned as a goal, adds scope to both
  the storage format and the importer.
- **Print/export view for a single recipe.** Proposed default: deferred to v1.5.
- **Shopping list grouped by store aisle/category.** Would materially improve the shopping-list
  feature's real-world usefulness but needs either a user-maintained aisle-mapping table or a
  built-in guess table. Proposed default: deferred to v1.5 — v1 ships one flat, combined list.

## 6. Suggested scope phasing

**v1 (MVP)**
- Accounts + auth (username/password, PBKDF2 hashing, cookie-based sessions) — same pattern as
  `shelf`
- Per-user home directory of recipe files
- Recipe CRUD: create, view, edit, delete, stored as the JSON format above, indexed in Postgres
- Recipe URL import (schema.org JSON-LD)
- Ingredient-line parser (structured quantity/unit/name from free text)
- Meal planning: assign recipes to calendar dates/slots, with a per-entry servings multiplier
- Shopping list generation from a set of recipes or a meal-plan date range, with combination,
  persisted and editable (check off, add/remove items by hand)
- Responsive VanJS UI

**v1.5**
- Fuzzy/synonym ingredient-name matching for better shopping-list combination
- Filename/tag search
- Nutrition info
- Print/export view
- Shopping list grouped by aisle/category
- HTML-microdata import fallback (likely needs `jsoup` — see section 4)

**v2 / stretch**
- Public share links or user-to-user sharing, if ever wanted (not a stated goal today)
- Multi-household support beyond "one account per household"
- Pantry/inventory tracking

## 7. Data model sketch (Postgres)

```
users(id, username, password_hash, created_at, is_admin)
sessions(id, user_id, created_at, expires_at)
recipe_index(id, owner_id, path, title, source_url NULL, tags TEXT[], servings NULL,
             prep_time_minutes NULL, cook_time_minutes NULL, total_time_minutes NULL,
             size_bytes, mtime, last_scanned_at)
meal_plan_entries(id, owner_id, plan_date, meal_slot, recipe_path, servings_multiplier,
                   created_at)
shopping_lists(id, owner_id, name, created_at)
shopping_list_items(id, shopping_list_id, name, quantity NULL, unit NULL, raw_text,
                     checked, source_recipe_paths TEXT[], sort_order)
```

`recipe_index` is a cache of each recipe file's state, kept current by the reconciliation
strategy in section 4 — never treat it as authoritative over the actual JSON file on disk.
`meal_plan_entries` and `shopping_lists`/`shopping_list_items` are real rows, not a cache of
anything — they have no file-backed representation.

## 8. Docker and storage notes

Same pattern as `shelf`: bind-mount the storage root (works identically for local disk or an
NFS mount already mounted on the host); run the app container with a `PUID`/`PGID` entrypoint
matching the host's file owner; coordinate through Postgres, not OS-level file locks (`flock`
over NFS is unreliable); `docker-compose.yml` defines the app service, the Postgres service,
and an optional reverse-proxy service for TLS.

## 9. Notes for the agent picking this up

- Section 2 is settled, **except** that the dependency/build-tool policy is deliberately looser
  than `shelf`'s — raising a new dependency or a move to Gradle is expected to happen at some
  point in this project, unlike `shelf`. Raise it explicitly when it does; don't add it
  silently either way.
- Section 5 items need a human decision before being built — surface the question rather than
  guessing, same as `shelf`'s brief asked.
- Everything else here is a working default, not gospel — reasonable refinements are expected
  as implementation details get worked out, same as `shelf`.
