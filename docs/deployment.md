# Deployment

How to run larder on a server, and the settings that matter once it's reachable from anywhere
other than your own machine. For why it's built this way, see `decisions.md`; for what exists,
`architecture.md`.

## On a server: one image, one `docker run`

The simplest way to run larder for real. One image holds everything: the app (API and web UI),
the ingredient-parser sidecar, and Postgres. There's no compose file and no separate database
container. You mount one directory for the data:

```
docker run -d --name larder --restart unless-stopped \
  -p 8080:8080 \
  -e LARDER_DB_PASSWORD='<a real password>' \
  -e TZ=America/New_York \
  -v /srv/larder:/data \
  ghcr.io/project-rostrata/larder:latest
```

Then visit `http://<server>:8080/` and register the first account.

**Where the image comes from.** `.github/workflows/docker-build.yml` runs the backend and
sidecar tests on every push to `main`, and only if they pass, builds this image and pushes it
to GitHub's container registry:
- `:latest` tracks `main`;
- `:sha-<short sha>` pins one exact build;
- pushing a git tag like `v1.0.0` also publishes `:v1.0.0`.

The package is public, so the server can pull it without logging in.

**Or build it yourself** from a checkout, and use `larder:standalone` as the image name above:

```
docker build --target standalone -f docker/Dockerfile -t larder:standalone .
```

### What's in the data directory

`/data` (here `/srv/larder` on the host) holds one subdirectory, `postgres/`, which is
Postgres's own data directory. It's created and initialized on first start, and it's the only
state larder has: accounts, recipes, meal plans, shopping lists and the pantry. Back it up.

The simplest consistent backup is a dump from the running container:

```
docker exec larder su postgres -c 'pg_dump -h /run/postgresql larder' > larder-$(date +%F).sql
```

Or stop the container and copy the directory.

### How it runs inside

`docker/standalone-entrypoint.sh` starts three processes and stops them cleanly on `docker stop`:
- **Postgres 18**, as the `postgres` user, listening on `127.0.0.1` only. The app connects over
  TCP with `LARDER_DB_PASSWORD`. On every start, the script makes sure the `larder` role and
  database exist and re-syncs the role's password to `LARDER_DB_PASSWORD`, so changing it
  between restarts just works.
- **The ingredient-parser sidecar**, as an unprivileged `larder` user, on `127.0.0.1:8000`.
- **The app**, as the `larder` user, on port 8080. This is the only port reachable from outside
  the container.

If Postgres or the app exits, the container exits, and `--restart` brings it back. If only the
sidecar dies, the container keeps running and new ingredient lines are saved as raw text,
unparsed, until the next restart.

## Settings

| Variable | Needed? | Notes |
|---|---|---|
| `LARDER_DB_PASSWORD` | **required** | The container refuses to start without it. |
| `TZ` | recommended | Your zone, e.g. `America/New_York`. Display dates like "Sep 24, 2026" use it; the default `UTC` rolls over at UTC midnight. |
| `LARDER_SECURE_COOKIES` | **set `true` behind HTTPS** | See below. |
| `LARDER_SESSION_DURATION_HOURS` | optional | How long a login lasts. Default `720` (30 days). |
| `LARDER_DB_POOL_SIZE` | optional | Default `10`. Rarely needs changing. |

In the one-image setup, don't set `LARDER_DB_URL` or `LARDER_DB_USER`; the entrypoint sets them.

## Before exposing it beyond your own network

1. **A real `LARDER_DB_PASSWORD`.** Postgres isn't reachable from outside the container, but
   don't rely on that alone.
2. **TLS.** The app only speaks plain HTTP. Put a reverse proxy (Caddy, nginx, Traefik) in
   front of it to terminate TLS.
3. **`LARDER_SECURE_COOKIES=true`** once it's served over HTTPS, so the session cookie is never
   sent over plain HTTP. It defaults to `false` only so local development over HTTP works;
   nothing turns it on automatically, since the app can't tell it's behind a TLS proxy.

## Upgrading

Pull the new image and recreate the container with the same `-v` mount:

```
docker pull ghcr.io/project-rostrata/larder:latest
docker rm -f larder
docker run ...   # the same command as above
```

Database migrations apply automatically on start. Postgres minor updates (18.x) need nothing.
A future move to a new Postgres major version would need a dump and restore, the same as any
Postgres upgrade; it will be called out in `decisions.md` when it happens.

## Alternative: Docker Compose (two containers)

For local development, or if you'd rather run Postgres as its own container:

```
docker compose -f docker/docker-compose.yml up --build -d
```

This builds the Dockerfile's `runtime` stage (the app and sidecar, no Postgres) and runs the
official `postgres:18.6-alpine3.23` next to it, with data in a named Docker volume. Settings go in
`docker/.env`. The same list applies, and here `LARDER_DB_PASSWORD` defaults to `devpassword`,
so override it for anything real.
