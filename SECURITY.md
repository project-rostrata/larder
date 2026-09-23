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

- **Path safety** — every recipe-file-touching endpoint is required to resolve user-supplied
  paths through a single canonicalizing function before touching disk; see `PROJECT_BRIEF.md`
  §4. A path that can read, write, or delete outside a user's own home directory is a real
  vulnerability.
- **Auth/session handling** — password hashing and session cookie handling.
- **SQL injection** — every query is expected to use `PreparedStatement` with bound parameters.
- **Server-side request forgery via recipe URL import** — the import feature fetches an
  arbitrary user-supplied URL server-side (`PROJECT_BRIEF.md` §4). This needs the same care
  `shelf` didn't have to take: reject non-HTTP(S) schemes, and treat requests to internal/
  private network addresses (loopback, link-local, RFC 1918 ranges, cloud metadata endpoints
  like `169.254.169.254`) as out of scope for the fetch, not just "trust the user typed a
  recipe site." A report showing the import endpoint can be used to probe or reach internal
  infrastructure is a real vulnerability, not a low-priority nitpick.

## Supported versions

There's no formal LTS policy — this project is pre-implementation. Security fixes will land on
`main`; there's no back-porting to older tags at this time.
