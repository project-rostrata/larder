# larder

A small, self-hosted recipe manager and meal planner — replacing Nextcloud Cookbook. Browse,
add, and import recipes, plan meals, and generate a shopping list that combines what's needed
across multiple recipes into one list, deliberately without the image handling, sharing, and
app-ecosystem breadth a full groupware suite would bolt on.

## Why

`larder` is the same-ethos sibling of [`shelf`](../shelf), a self-hosted file-sharing app: a
small, auditable, low-dependency stack. Unlike `shelf`, larder has no filesystem-backed content
at all — recipes, meal plans, and shopping lists are all rows in PostgreSQL, which is the real
source of truth here rather than just a metadata cache. That's a deliberate divergence from
`shelf`'s "just files" philosophy: recipes need relational, structured ingredient data to make
the shopping-list combination feature work in the first place, and there's no real-world
"someone edits this over SMB outside the app" use case for a recipe the way there is for an
arbitrary file. See [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) section 4 for the full reasoning.

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
- Recipes stored as normalized Postgres rows (title, tags, structured ingredients,
  instructions), no images
- Recipe import from a URL (schema.org JSON-LD)
- Meal planning: assign recipes to dates/meal slots
- Shopping list generation from a meal plan or a hand-picked set of recipes, combining shared
  ingredients into single line items, saved and editable (check off, add/remove by hand)
- Responsive VanJS UI

Not planned for v1, deliberately: images, recipe search, sharing, nutrition info — see
[`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) §5–6 for what's deferred and why.

**Status: early build.** Phases 0–6, 9a, and 10 of [`V1_PLAN.md`](V1_PLAN.md) are done — a
backend skeleton, the full database schema, auth (register/login/logout), a standalone Python
ingredient-parser sidecar (see [`ingredient-parser/README.md`](ingredient-parser/README.md)),
recipe CRUD with ingredient-line parsing wired all the way through, recipe import from a URL
(schema.org JSON-LD, verified against a real live recipe page), a VanJS frontend for
auth/recipe browsing/creating/editing, and Docker packaging (one built image bundling the
backend, frontend, and sidecar together, plus a `docker-compose.yml` with Postgres). No meal
planning or shopping lists yet, and no frontend for them either (Phase 9b) — see
[`docs/architecture.md`](docs/architecture.md) for what's actually built right now.

## Quick start

### Docker (recommended)

```
docker compose -f docker/docker-compose.yml up --build
```

Brings up two containers: `app` (backend + frontend + ingredient-parser sidecar, all one built
image — see [`docker/README.md`](docker/README.md)) and `postgres`. Visit
`http://localhost:8080/`. Override `LARDER_DB_PASSWORD`/`LARDER_PORT`/etc. via a `docker/.env`
file or the shell environment; see `docker/docker-compose.yml` for the full list.

### Bare metal

To run the backend (which also serves the frontend) directly against a local Postgres and a
separately-running sidecar:

```
cd backend
./lib/fetch-deps.sh
./build.sh
LARDER_DB_URL=jdbc:postgresql://localhost:5432/larder \
LARDER_DB_USER=larder \
LARDER_DB_PASSWORD=... \
  ./run.sh
```

Port 8080 by default; visit `http://localhost:8080/` for the UI. Routes so far:
`GET /api/health`, `GET /api/version`, `POST /api/register`, `POST /api/login`,
`POST /api/logout`, `GET /api/me`, `GET /api/recipes`,
`GET/POST/PUT/DELETE /api/recipes/{id}` (POST for create is on the collection route, not
`{id}`), `POST /api/recipes/import`, `POST /api/ingredients/{id}/merge-into/{targetId}`. Any
other `GET` outside `/api/` serves the frontend from `LARDER_FRONTEND_DIR` (defaults to
`../frontend` via `run.sh`).

Also needs `LARDER_INGREDIENT_PARSER_URL` pointing at a running
[`ingredient-parser`](ingredient-parser/README.md) instance (defaults to
`http://localhost:8000` via `run.sh`) — recipe create/update calls it to parse ingredient
lines, though it degrades gracefully to raw-text-only ingredients if that's unreachable.

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
