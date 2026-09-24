# Security Policy

## Reporting a vulnerability

Please use GitHub's private vulnerability reporting rather than opening a public issue:

**Security → Advisories → Report a vulnerability**, on this repository's GitHub page. (This
requires the repo owner to have enabled *private vulnerability reporting* under
**Settings → Security** first — it's off by default on a new repository.)

This sends the report privately to the repository maintainer(s) only — it won't be visible to
the public until (and unless) it's disclosed. If you're unable to use that feature for any
reason, open a regular issue asking for an alternative contact method, without describing the
vulnerability itself.

## Scope

larder is a self-hosted application: you run your own instance, on infrastructure you control.
The main areas worth extra scrutiny in reports:

- **Ownership checks** — every query against `recipes`, `recipe_ingredients`,
  `meal_plan_entries`, `shopping_lists`, `shopping_list_items`, or `shopping_list_item_sources`
  is required to filter by the authenticated user's `owner_id` (or join through it); see
  `PROJECT_BRIEF.md` §4 and `AGENTS.md`'s error-handling table. A request that can read, write,
  or delete another user's rows via a missing ownership check is a real vulnerability — this is
  larder's equivalent of `shelf`'s path-safety rule. This includes a client-supplied id
  referencing another owner-scoped row (e.g. a `recipe_id` in a meal-plan-entry or
  shopping-list-generation request) — a foreign key only proves the row exists, not that the
  caller owns it; an endpoint that lets one user plan a meal or generate a shopping list from
  another user's private recipe by guessing/enumerating its id is exactly this vulnerability.
  (`ingredients`, `ingredient_aliases`, `units`, and `unit_conversions` are intentionally
  global/unscoped — see `PROJECT_BRIEF.md` §4 — don't report the absence of an `owner_id` check
  on those four as a finding.)
- **Auth/session handling** — password hashing and session cookie handling.
- **SQL injection** — every query is expected to use `PreparedStatement` with bound parameters.
- **Server-side request forgery via recipe URL import** — the import feature fetches an
  arbitrary user-supplied URL server-side (`PROJECT_BRIEF.md` §4). This needs the same care
  `shelf` didn't have to take: reject non-HTTP(S) schemes, and treat requests to internal/
  private network addresses (loopback, link-local, RFC 1918 ranges, cloud metadata endpoints
  like `169.254.169.254`) as out of scope for the fetch, not just "trust the user typed a
  recipe site." A report showing the import endpoint can be used to probe or reach internal
  infrastructure is a real vulnerability, not a low-priority nitpick. **Implemented as of Phase
  6**: `larder.recipeimport.validateImportUrl` (`backend/src/recipeimport/ImportUrlValidator.kt`)
  rejects non-HTTP(S) schemes and resolves the host to check for loopback/link-local/private
  addresses before every fetch — and, since a URL that passes this check once can still
  redirect to an internal address afterward, redirects are followed manually
  (`RecipeUrlFetcher.kt`), with every hop re-validated the same way, rather than trusting
  `HttpClient`'s own redirect handling to preserve that guarantee. A report finding a gap in
  this specific mechanism (an address class it misses, a redirect path that bypasses
  re-validation) is exactly the kind of report this scope note is asking for.

## Supported versions

There's no formal LTS policy — this project is pre-implementation. Security fixes will land on
`main`; there's no back-porting to older tags at this time.
