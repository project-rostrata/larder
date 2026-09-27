# Single image bundling everything larder needs to run except Postgres: the Kotlin backend, the
# static frontend, and the ingredient-parser Python sidecar. Unlike shelf (one process, one
# language), larder's problem domain needs a second runtime for ingredient parsing (see
# AGENTS.md's "new runtime/service" policy) -- rather than ship that as a second image/service,
# the human asked for a single deployable image, so both processes are bundled here and started
# together by docker/entrypoint.sh. See docs/decisions.md for the full reasoning, including why
# this differs from shelf's docker-compose shape.

# ---- stage 1: compile the Kotlin backend -- same pattern as shelf's build stage ----
FROM eclipse-temurin:25.0.4_7-jdk-alpine AS kotlin-build

ARG KOTLIN_VERSION=2.4.20
ARG KOTLIN_COMPILER_SHA256=59e9ca74c7904ef2c122b12114937673ccce68de820a663f0ed66ccf8799e0b7

RUN apk add --no-cache bash curl

RUN curl -fsSLo /tmp/kotlin-compiler.zip \
      "https://github.com/JetBrains/kotlin/releases/download/v${KOTLIN_VERSION}/kotlin-compiler-${KOTLIN_VERSION}.zip" \
    && echo "${KOTLIN_COMPILER_SHA256}  /tmp/kotlin-compiler.zip" | sha256sum -c - \
    && unzip -q /tmp/kotlin-compiler.zip -d /opt \
    && rm /tmp/kotlin-compiler.zip
ENV KOTLIN_HOME=/opt/kotlinc
ENV PATH="${KOTLIN_HOME}/bin:${PATH}"

WORKDIR /build
COPY backend/ backend/
RUN backend/lib/fetch-deps.sh
RUN backend/build.sh

# ---- stage 2: runtime -- needs both a JRE (for the app) and Python (for the sidecar), so this
# starts from the sidecar's own already-verified base (python:3.12-slim, glibc -- Alpine/musl
# would force numpy/regex to compile from source instead of installing their manylinux wheels,
# see ingredient-parser/Dockerfile) and adds a headless JRE on top, rather than the other
# direction ----
FROM python:3.12-slim AS runtime

RUN apt-get update \
    && apt-get install -y --no-install-recommends openjdk-21-jre-headless \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app

# ---- ingredient-parser sidecar -- identical steps to ingredient-parser/Dockerfile, just
# installed under /app/ingredient-parser instead of being the whole image ----
ENV NLTK_DATA=/usr/local/share/nltk_data
COPY ingredient-parser/requirements.txt ingredient-parser/
RUN pip install --no-cache-dir -r ingredient-parser/requirements.txt
RUN python -c "import nltk; nltk.download('averaged_perceptron_tagger_eng', download_dir='${NLTK_DATA}', quiet=True)"
RUN python -c "import ingredient_parser"
COPY ingredient-parser/app.py ingredient-parser/

# ---- larder app itself ----
COPY --from=kotlin-build /build/backend/out/ ./out/
COPY --from=kotlin-build /build/backend/lib/*.jar ./lib/
COPY --from=kotlin-build /opt/kotlinc/lib/kotlin-stdlib.jar ./lib/kotlin-stdlib.jar
COPY db/migrations/ ./db/migrations/
COPY frontend/ ./frontend/
COPY docker/entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENV LARDER_PORT=8080
ENV LARDER_MIGRATIONS_DIR=/app/db/migrations
ENV LARDER_FRONTEND_DIR=/app/frontend
# Loopback only -- the sidecar is never published/exposed outside this container, see
# entrypoint.sh and docker-compose.yml.
ENV LARDER_INGREDIENT_PARSER_URL=http://127.0.0.1:8000
ENV INGREDIENT_PARSER_HOST=127.0.0.1
ENV INGREDIENT_PARSER_PORT=8000
EXPOSE 8080

ENTRYPOINT ["/entrypoint.sh"]

# ---- stage 3: standalone -- runtime + a bundled Postgres, for a single `docker run` on a server
# with no compose file and no separate database container. Same approach as shelf's standalone
# stage (see docs/decisions.md): built FROM runtime, so the app and sidecar are byte-for-byte the
# same as the compose deployment's, plus Postgres and a different entrypoint.
#
# Keep this the LAST stage but never the implicit default: docker-compose.yml pins
# `target: runtime`, since `docker build` without --target builds the last stage in the file.
FROM runtime AS standalone

# Postgres 18 from the PostgreSQL project's own apt repo (PGDG), since Debian trixie only ships
# 17 and docker-compose.yml pins 18. The repo signing key is checksum-pinned, same pattern as the
# kotlinc download above (fingerprint B97B 0AFC AA1A 47F0 44F2 44A0 7FCC 7D46 ACCC 4CF8). Only
# the major version is pinned: PGDG only keeps the latest minor release, and minor releases share
# an on-disk format. createcluster.conf stops the package from creating a default cluster -- the
# entrypoint initializes its own under /data instead.
ARG PGDG_KEY_SHA256=0144068502a1eddd2a0280ede10ef607d1ec592ce819940991203941564e8e76
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl \
    && install -d /usr/share/postgresql-common/pgdg /etc/postgresql-common \
    && curl -fsSLo /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc https://www.postgresql.org/media/keys/ACCC4CF8.asc \
    && echo "${PGDG_KEY_SHA256}  /usr/share/postgresql-common/pgdg/apt.postgresql.org.asc" | sha256sum -c - \
    && . /etc/os-release \
    && echo "deb [signed-by=/usr/share/postgresql-common/pgdg/apt.postgresql.org.asc] https://apt.postgresql.org/pub/repos/apt ${VERSION_CODENAME}-pgdg main" > /etc/apt/sources.list.d/pgdg.list \
    && echo "create_main_cluster = false" > /etc/postgresql-common/createcluster.conf \
    && apt-get update \
    && apt-get install -y --no-install-recommends postgresql-18 \
    && apt-get purge -y curl \
    && apt-get autoremove -y \
    && rm -rf /var/lib/apt/lists/*
ENV PATH="/usr/lib/postgresql/18/bin:${PATH}"

# The app and sidecar run as this unprivileged user; Postgres runs as the package's own
# `postgres` user. Nothing runs as root after the entrypoint's setup steps.
RUN useradd --system --no-create-home --home-dir /nonexistent --shell /usr/sbin/nologin larder

COPY docker/standalone-entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh

ENV LARDER_DATA_DIR=/data
VOLUME /data
