#!/usr/bin/env bash
# Runs both processes bundled into this single image -- the ingredient-parser sidecar (Python)
# and the larder app itself (JVM) -- as direct children of this script. No process supervisor
# (s6-overlay, supervisord) -- matches shelf's docker/standalone-entrypoint.sh precedent for
# coordinating more than one process in a single container without an added dependency.
#
# Unlike that precedent (bundled Postgres, which the app genuinely cannot run without), the
# sidecar is an optional enhancement the app already degrades gracefully without -- verified
# live in Phase 5: an unreachable sidecar mid-session falls back to raw-text-only ingredients,
# never throws. So only the app process's own exit ends this container; a dead sidecar alone
# doesn't take it down with it. See docs/decisions.md for this being a deliberate divergence
# from shelf's "either process dying ends the container" pattern, not an oversight.
set -euo pipefail

python3 /app/ingredient-parser/app.py &
SIDECAR_PID=$!

java -cp /app/out:/app/lib/* MainKt &
APP_PID=$!

shutdown() {
  kill -TERM "$APP_PID" "$SIDECAR_PID" 2>/dev/null || true
}
trap shutdown TERM INT

wait "$APP_PID"
EXIT_CODE=$?
shutdown
wait "$SIDECAR_PID" 2>/dev/null || true
exit "$EXIT_CODE"
