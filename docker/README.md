Phase 10 — Docker packaging. Two files build the deployable image, one runs it locally:

- `Dockerfile` — multi-stage: compiles the Kotlin backend, then builds a runtime image bundling
  it with the frontend and the `ingredient-parser` Python sidecar. One image, not two — the
  human asked for a single deployable image rather than a separate sidecar service/container.
- `entrypoint.sh` — starts both the sidecar and the app as direct children, no supervisor
  dependency. See its own comments and `docs/decisions.md` for why a dead sidecar alone doesn't
  end the container (unlike a dead app), which is a deliberate divergence from `shelf`'s
  equivalent multi-process entrypoint pattern.
- `docker-compose.yml` — the `app` image above + a `postgres` service (data via a named
  volume). No `PUID`/`PGID` handling and no bind-mounted storage directory, unlike `shelf` —
  there's no user-facing filesystem content to manage here at all (`PROJECT_BRIEF.md` §4/§8),
  Postgres is the only state.

From the repo root: `docker compose -f docker/docker-compose.yml up --build`.
