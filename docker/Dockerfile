# Single image bundling everything larder needs to run except Postgres: the Kotlin backend, the
# static frontend, and the ingredient-parser Python sidecar. Unlike shelf (one process, one
# language), larder's problem domain needs a second runtime for ingredient parsing (see
# AGENTS.md's "new runtime/service" policy) -- rather than ship that as a second image/service,
# the human asked for a single deployable image, so both processes are bundled here and started
# together by docker/entrypoint.sh. See docs/decisions.md for the full reasoning, including why
# this differs from shelf's docker-compose shape.

# ---- pinned base images: bump versions here and only here. CI builds its test stages from this
# same file, so the tests always run on exactly these images. Explicit patch versions and distro
# codenames, so nothing changes under a rebuild unless it's changed on purpose; see
# docs/decisions.md for why these are the versions they are.
# Temurin 25, the current Java LTS, for both building and running the backend.
ARG JAVA_IMAGE_VERSION=25.0.4_7
# Ubuntu 26.04 LTS, the base of the Temurin images.
ARG UBUNTU_CODENAME=resolute
# The newest stable Python (longest-supported, until 2030) on Debian 13, the current stable.
ARG PYTHON_IMAGE=python:3.14.7-slim-trixie

# ---- stage 1: compile the Kotlin backend -- same pattern as shelf's build stage ----
FROM eclipse-temurin:${JAVA_IMAGE_VERSION}-jdk-${UBUNTU_CODENAME} AS kotlin-build

ARG KOTLIN_VERSION=2.4.20
ARG KOTLIN_COMPILER_SHA256=59e9ca74c7904ef2c122b12114937673ccce68de820a663f0ed66ccf8799e0b7

RUN apt-get update \
    && apt-get install -y --no-install-recommends curl unzip \
    && rm -rf /var/lib/apt/lists/*

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

# ---- backend tests, for CI only (`docker build --target backend-test`); never in an image ----
FROM kotlin-build AS backend-test
RUN backend/test.sh

# ---- the Java runtime the app runs on: the same Temurin release the build stage compiles with ----
FROM eclipse-temurin:${JAVA_IMAGE_VERSION}-jre-${UBUNTU_CODENAME} AS jre

# ---- stage 2: runtime -- needs both a JRE (for the app) and Python (for the sidecar), so this
# starts from the sidecar's own base (Debian slim, glibc -- Alpine/musl would force numpy/regex
# to compile from source instead of installing their manylinux wheels, see
# ingredient-parser/Dockerfile) and adds the JRE on top, rather than the other direction ----
FROM ${PYTHON_IMAGE} AS runtime

# Temurin's JRE copied in, rather than Debian's openjdk-25-jre-headless package, so build and
# runtime are the exact same, explicitly pinned Java 25 release; Debian's package version moves
# with every apt update. Temurin's Linux builds target an old glibc, so they run on any current
# distro.
COPY --from=jre /opt/java/openjdk /opt/java/openjdk
ENV JAVA_HOME=/opt/java/openjdk
ENV PATH="${JAVA_HOME}/bin:${PATH}"

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

# ---- sidecar tests, for CI only (`docker build --target parser-test`): the runtime image plus
# pytest, so the tests run on the exact Python and packages that ship. Never in an image. ----
FROM runtime AS parser-test
COPY ingredient-parser/requirements-test.txt ingredient-parser/test_app.py ingredient-parser/
RUN pip install --no-cache-dir -r ingredient-parser/requirements-test.txt
RUN cd ingredient-parser && python -m pytest -q -p no:cacheprovider

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
