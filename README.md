# larder

A small, self-hosted recipe manager and meal planner — replacing Nextcloud Cookbook. Browse,
add, and import recipes, plan meals, and generate a shopping list that combines what's needed
across multiple recipes into one list, deliberately without the image handling, sharing, and
app-ecosystem breadth a full groupware suite would bolt on.

## Why

`larder` is the same-ethos sibling of [`shelf`](../shelf), a self-hosted file-sharing app: a
small, auditable, low-dependency stack, with a real filesystem — not a database — as the source
of truth. Each recipe is one JSON file, readable and editable outside the app (over SSH, SMB,
whatever you already use). PostgreSQL holds metadata only — an index over recipe files, plus
meal-plan and shopping-list data that has no natural file form.

Where `shelf` never needed anything beyond a bare `kotlinc` build and the Postgres JDBC driver,
`larder`'s problem domain — parsing recipes out of arbitrary web pages, parsing free-text
ingredient lines into structured quantities and units, combining them across recipes — is
harder, so this project starts from the same minimal baseline but explicitly allows growing
into a real build tool (Gradle) and a few well-justified external libraries as specific needs
come up. See [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) section 2 for the exact policy.

See [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) for the full set of constraints this project holds
itself to, and [`docs/decisions.md`](docs/decisions.md) for an append-only log of *why* things
are built the way they are, not just what was built.

## Planned v1 features

- Accounts with username/password auth (PBKDF2, cookie-based sessions) — same pattern as `shelf`
- Per-user home directory of recipes, one JSON file per recipe, no images
- Recipe import from a URL (schema.org JSON-LD)
- Meal planning: assign recipes to dates/meal slots
- Shopping list generation from a meal plan or a hand-picked set of recipes, combining shared
  ingredients into single line items, saved and editable (check off, add/remove by hand)
- Responsive VanJS UI

Not planned for v1, deliberately: images, recipe search, sharing, nutrition info — see
[`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) §5–6 for what's deferred and why.

**Status: pre-implementation.** This repo currently holds the planning documents and directory
scaffold only — see [`V1_PLAN.md`](V1_PLAN.md) for the phase-by-phase build order.

## Quick start

Not yet runnable — Phase 1 of [`V1_PLAN.md`](V1_PLAN.md) is the first phase with any code to
build or run.

## Documentation

- [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) — the target design and non-negotiable constraints
- [`V1_PLAN.md`](V1_PLAN.md) — the phased build plan
- [`AGENTS.md`](AGENTS.md) — coding conventions for anyone (human or AI) working on the code
- [`docs/decisions.md`](docs/decisions.md) — an append-only log of design decisions and why

## License

Copyright (C) 2026 Casey Watson.

larder is free software: you can redistribute it and/or modify it under the terms of the GNU
General Public License as published by the Free Software Foundation, either version 3 of the
License, or (at your option) any later version. See [`LICENSE`](LICENSE) for the full text.

larder is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
