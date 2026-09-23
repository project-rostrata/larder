#!/usr/bin/env bash
# Runs the compiled larder backend. Single source of truth for the run
# command; the Dockerfile runtime stage calls this script directly.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

OUT_DIR="out"
LIB_DIR="lib"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/kotlinc}"

CP="$OUT_DIR:$LIB_DIR/postgresql-42.7.13.jar:$LIB_DIR/kotlinx-serialization-core-jvm-1.11.0.jar:$LIB_DIR/kotlinx-serialization-json-jvm-1.11.0.jar:$KOTLIN_HOME/lib/kotlin-stdlib.jar"

# Local-dev default, relative to this script's cwd (backend/); the Docker image sets this explicitly instead.
: "${LARDER_MIGRATIONS_DIR:=../db/migrations}"
export LARDER_MIGRATIONS_DIR

java \
  -cp "$CP" \
  MainKt "$@"
