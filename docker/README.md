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

For a server, the Dockerfile's last stage, `standalone`, adds Postgres to the same image so one
`docker run` with one mounted `/data` directory is the whole deployment (same approach as
shelf's):

- `standalone-entrypoint.sh` — initializes Postgres under `/data/postgres` on first run, then
  runs Postgres, the sidecar and the app. Postgres listens on `127.0.0.1` only; the app and
  sidecar run as an unprivileged `larder` user. The container exits if Postgres or the app
  exits, but not if only the sidecar does (same rule as `entrypoint.sh`).
- CI (`.github/workflows/docker-build.yml`) builds and pushes it to GHCR. Build it locally with
  `docker build --target standalone -f docker/Dockerfile -t larder:standalone .`

See `docs/deployment.md` for how to run it.
