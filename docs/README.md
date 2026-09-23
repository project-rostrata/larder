# larder — docs index

larder is a self-hosted recipe manager and meal planner: import and organize recipes, plan
meals, and generate a combined shopping list — without a full groupware suite's app ecosystem
bolted on, and without image handling. Kotlin backend starting on the bare JDK stdlib (no
framework, though unlike `shelf` this project may grow into Gradle and a few libraries as real
needs come up), VanJS frontend, Postgres as a metadata/index cache, real filesystem as the
source of truth for recipe content.

Keep this page short — it's an index, not a place to write things up. Put depth in the linked
docs instead.

- **[../PROJECT_BRIEF.md](../PROJECT_BRIEF.md)** — the original spec: non-negotiable
  constraints, architecture, rationale, phasing. Read this first.
- **[../V1_PLAN.md](../V1_PLAN.md)** — the phased build plan derived from the brief.
- **[../AGENTS.md](../AGENTS.md)** — coding conventions: layering, dependency injection, error
  handling, concurrency, logging, config, testing, dependency policy. Read this before writing
  any code.
- **[decisions.md](decisions.md)** — append-only log of decisions made while building larder
  that aren't already settled by the brief or plan. Check here before re-litigating something.
- **architecture.md** — *not yet written; add once Phase 1 lands.* Living doc of what's
  actually built, updated every time the as-built system changes.
- **database.md** — *not yet written; add once Phase 2 lands.* Postgres version, extensions,
  schema notes.
- **phases/** — *not yet created; add per-phase planning docs here as `V1_PLAN.md`'s phases are
  started*, one level more concrete than the plan itself, same pattern `shelf` used.
