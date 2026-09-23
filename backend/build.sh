#!/usr/bin/env bash
# Compiles the larder backend with the bare kotlinc compiler — no Gradle/Maven (yet; see
# AGENTS.md's dependency/build-tool policy for when that's expected to change).
# This is the single source of truth for the compile command; the Dockerfile
# build stage calls this script rather than duplicating the invocation.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")"

OUT_DIR="out"
LIB_DIR="lib"
KOTLIN_HOME="${KOTLIN_HOME:-/opt/kotlinc}"

CP="$LIB_DIR/postgresql-42.7.13.jar:$LIB_DIR/kotlinx-serialization-core-jvm-1.11.0.jar:$LIB_DIR/kotlinx-serialization-json-jvm-1.11.0.jar"

rm -rf "$OUT_DIR"
mkdir -p "$OUT_DIR"

kotlinc \
  -Xplugin="$KOTLIN_HOME/lib/kotlinx-serialization-compiler-plugin.jar" \
  -cp "$CP" \
  -d "$OUT_DIR" \
  $(find src -name '*.kt')

echo "Built to $OUT_DIR/"
