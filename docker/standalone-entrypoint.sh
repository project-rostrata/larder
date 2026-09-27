#!/usr/bin/env bash
# Entrypoint for the Dockerfile's `standalone` stage: bootstraps a bundled Postgres (loopback
# only, never exposed) and runs it, the ingredient-parser sidecar, and the larder app as direct
# background children of this script. No process supervisor, same as shelf's
# docker/standalone-entrypoint.sh, which this follows closely.
#
# Exit policy combines the two precedents: the container stops the moment Postgres or the app
# exits (shelf's rule -- neither is useful without the other), but NOT when only the sidecar
# does (docker/entrypoint.sh's rule -- the app degrades to raw-text ingredients without it).
set -euo pipefail

LARDER_DATA_DIR="${LARDER_DATA_DIR:-/data}"
LARDER_DB_PASSWORD="${LARDER_DB_PASSWORD:?LARDER_DB_PASSWORD is required}"
PGDATA="$LARDER_DATA_DIR/postgres"

as_user() { local user="$1"; shift; setpriv --reuid="$user" --regid="$user" --init-groups -- "$@"; }

# ---- the one mounted directory holds Postgres's data (larder has no other on-disk state) ----
mkdir -p "$PGDATA"
chown postgres:postgres "$PGDATA" || echo "warning: could not chown $PGDATA to postgres -- continuing, assuming it's already correct" >&2
chmod 700 "$PGDATA" || true

# Postgres's unix-socket directory. The Debian package normally creates it through its service
# scripts, which aren't used here; without it Postgres can't create its lock file.
mkdir -p /run/postgresql
chown postgres:postgres /run/postgresql

# ---- initialize the data directory on first run only. The socket (used only by this script)
# trusts the postgres OS user; TCP, which the app uses, requires the password. ----
if [ ! -s "$PGDATA/PG_VERSION" ]; then
  as_user postgres initdb -D "$PGDATA" --username=postgres --encoding=UTF8 --locale=C.UTF-8 \
    --auth-local=trust --auth-host=scram-sha-256 >/tmp/initdb.log 2>&1 \
    || { cat /tmp/initdb.log >&2; exit 1; }
fi

# ---- start Postgres in the foreground (not pg_ctl's daemon mode) so it stays our child ----
as_user postgres postgres -D "$PGDATA" -c listen_addresses=127.0.0.1 \
  -c unix_socket_directories=/run/postgresql -c logging_collector=off &
PG_PID=$!

until as_user postgres pg_isready -q -h /run/postgresql 2>/dev/null; do
  kill -0 "$PG_PID" 2>/dev/null || { echo "postgres exited during startup" >&2; exit 1; }
  sleep 0.3
done

# ---- ensure the larder role and database exist; the role's password is re-synced to the
# current env var on every start, in case LARDER_DB_PASSWORD changes across restarts ----
as_user postgres psql -h /run/postgresql -v ON_ERROR_STOP=1 -v pass="$LARDER_DB_PASSWORD" \
  --username postgres --dbname postgres --quiet <<-'EOSQL'
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'larder') THEN
    CREATE ROLE larder LOGIN;
  END IF;
END
$$;
ALTER ROLE larder WITH PASSWORD :'pass';
SELECT 'CREATE DATABASE larder OWNER larder' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'larder')\gexec
EOSQL

export LARDER_DB_URL="jdbc:postgresql://127.0.0.1:5432/larder"
export LARDER_DB_USER="larder"
export LARDER_DB_PASSWORD
export HOME=/tmp

# ---- the sidecar and the app, as the unprivileged larder user ----
as_user larder python3 /app/ingredient-parser/app.py &
SIDECAR_PID=$!

as_user larder java -cp '/app/out:/app/lib/*' MainKt &
APP_PID=$!

SHUTTING_DOWN=0
shutdown() {
  if [ "$SHUTTING_DOWN" = "1" ]; then return; fi
  SHUTTING_DOWN=1
  kill -TERM "$APP_PID" "$SIDECAR_PID" 2>/dev/null || true
  wait "$APP_PID" 2>/dev/null || true
  as_user postgres pg_ctl -D "$PGDATA" -m fast stop >/dev/null 2>&1 || true
}
trap shutdown TERM INT

# Wait for Postgres or the app specifically (bash 5.1+ `wait -n` with pids) -- the sidecar
# exiting on its own is survivable, see the header comment.
set +e
wait -n "$PG_PID" "$APP_PID"
EXIT_CODE=$?
set -e
shutdown
wait "$PG_PID" "$SIDECAR_PID" 2>/dev/null || true
exit "$EXIT_CODE"
