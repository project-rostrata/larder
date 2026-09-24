# Contributing to larder

## Before you write any code

Read [`PROJECT_BRIEF.md`](PROJECT_BRIEF.md) (the non-negotiable constraints) and
[`AGENTS.md`](AGENTS.md) (coding conventions). The single most important rule for a
contribution to land cleanly:

**Don't add a dependency, or move the build off bare `kotlinc`, without asking first.** The
Postgres JDBC driver, any `kotlinx.*` library, and `kotlin-test` are pre-approved. Unlike
`shelf`, this project does expect to eventually need a real build tool and a library or two
(recipe/HTML parsing is genuinely harder than file browsing) — but "expects to eventually need"
means *propose it when the need is concrete*, not add it speculatively. Raise it in an issue or
PR description before the code that needs it, not discovered by a reviewer in the diff.

## Building and testing locally

No Docker required for backend-only work, once Phase 1 lands:

```
cd backend
./build.sh   # compiles src/ with bare kotlinc (or the current build tool, once that changes)
./test.sh    # compiles src/ + test/, runs the hand-rolled test runner
./run.sh     # runs the compiled backend directly (needs a local Postgres — see below)
```

For the full stack (app + Postgres + frontend, one command), once Phase 11 lands:

```
docker compose -f docker/docker-compose.yml up --build
```

There's no test framework dependency initially — tests are plain top-level `test*()` functions
under `backend/test/`, discovered reflectively by `backend/test/TestMain.kt`. Add new ones the
same way; register the new file's compiled class name in `TestMain.kt`'s list if it's a new
file. (This may itself change if Phase 4b's Kotlin-side ingredient-resolution test suite grows
large enough to justify a real test framework — see `AGENTS.md`'s testing section. Phase 4a's
own tests are Python/pytest, in `ingredient-parser/`, unaffected by this either way.)

The frontend (`frontend/`) has no build step — it's vendored VanJS and plain ES modules, served
directly. Changes there are effective on a page reload.

## Before opening a PR

- Run `backend/test.sh` and make sure it passes.
- If you touched a query against `recipes`, `recipe_ingredients`, `meal_plan_entries`,
  `shopping_lists`, or `shopping_list_items`, re-read the ownership rule in `PROJECT_BRIEF.md`
  §4 and `AGENTS.md` — it must filter by `owner_id` (or join through it), no exceptions.
- If your change touches the ingredient parser or the shopping-list combination logic, add
  real-world test cases, not just synthetic ones — see `AGENTS.md`'s testing section for why.
- If your change is a real design decision (not just a bug fix), consider adding an entry to
  [`docs/decisions.md`](docs/decisions.md) explaining why, for whoever reads the history next.

## Reporting bugs / security issues

Regular bugs: open a GitHub issue. Security vulnerabilities: see
[`SECURITY.md`](SECURITY.md) — please don't open a public issue for those.
