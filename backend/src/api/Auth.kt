package larder.api

import larder.db.SessionRepository
import larder.db.SessionRow
import larder.db.UserRepository
import java.util.UUID

// The minimal, request-scoped identity every authenticated handler needs — resolved once here
// rather than each handler doing its own UserRepository lookup. Ported from shelf's
// AuthenticatedUser; unlike shelf, larder has no filesystem path keyed by username, but the
// username is still useful for handlers to have on hand (e.g. GET /api/me's response), so it
// stays on this type rather than being dropped down to just the id.
data class AuthenticatedUser(val id: UUID, val username: String)

private fun resolveSession(ctx: RouteContext, sessions: SessionRepository): SessionRow? {
    val token = requestCookie(ctx.exchange, SESSION_COOKIE_NAME)
    val sessionId = token?.let { runCatching { UUID.fromString(it) }.getOrNull() }
    return sessionId?.let { sessions.findValid(it) }
}

fun requireAuth(
    sessions: SessionRepository,
    users: UserRepository,
    inner: (RouteContext, AuthenticatedUser) -> ApiResult<String>,
): Handler = { ctx ->
    val session = resolveSession(ctx, sessions)
    val user = session?.let { users.findById(it.userId) }
    if (session == null || user == null) {
        Err(401, "UNAUTHORIZED", "Missing or invalid session")
    } else {
        inner(ctx, AuthenticatedUser(user.id, user.username))
    }
}
