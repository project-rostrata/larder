`Dockerfile` and `docker-compose.yml` land here in `V1_PLAN.md` Phase 10: app service +
Postgres service (data via a named volume). No `entrypoint.sh`, no bind-mounted storage
directory, no `PUID`/`PGID` handling — unlike `shelf`, there's no user-facing filesystem
content to manage (see `PROJECT_BRIEF.md` §4 and §8).
