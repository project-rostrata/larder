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
