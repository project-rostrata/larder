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
