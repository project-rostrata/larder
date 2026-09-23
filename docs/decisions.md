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
