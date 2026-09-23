package larder.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.auth.AuthConfig
import larder.auth.PasswordHasher
import larder.db.SessionRepository
import larder.db.UserRepository
import java.sql.SQLException
import java.time.Instant
import java.time.temporal.ChronoUnit

@Serializable
private data class RegisterRequest(val username: String, val password: String)

private const val MIN_PASSWORD_LENGTH = 8
private const val UNIQUE_VIOLATION_SQLSTATE = "23505"

// Deliberately conservative, not filesystem-driven the way shelf's identical-looking check
// was (larder has no per-user directory) — just enough to keep a username readable and rule
// out whitespace-only/control-character values.
private val VALID_USERNAME = Regex("^[A-Za-z0-9._-]{1,64}$")

class RegisterHandler(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val config: AuthConfig,
) {
    // No email: registration collects nothing beyond username and password — the human was
    // explicit about this, don't add it back.
    fun handle(ctx: RouteContext): ApiResult<String> {
        val request = try {
            Json.decodeFromString<RegisterRequest>(ctx.readBody())
        } catch (e: Exception) {
            return Err(400, "INVALID_BODY", "Malformed request body")
        }

        if (!VALID_USERNAME.matches(request.username)) {
            return Err(400, "INVALID_INPUT", "username may only contain letters, numbers, dots, underscores, and hyphens")
        }
        if (request.password.length < MIN_PASSWORD_LENGTH) {
            return Err(400, "INVALID_INPUT", "password must be at least $MIN_PASSWORD_LENGTH characters")
        }

        val passwordChars = request.password.toCharArray()
        val passwordHash = try {
            PasswordHasher.hash(passwordChars)
        } finally {
            passwordChars.fill('\u0000')
        }

        val user = try {
            users.create(request.username, passwordHash)
        } catch (e: SQLException) {
            if (e.sqlState == UNIQUE_VIOLATION_SQLSTATE) {
                return Err(409, "USERNAME_TAKEN", "That username is already registered")
            }
            throw e
        }

        val expiresAt = Instant.now().plus(config.sessionDurationHours, ChronoUnit.HOURS)
        val session = sessions.create(user.id, expiresAt)
        setCookie(ctx.exchange, SESSION_COOKIE_NAME, session.id.toString(), config.sessionDurationHours * 3600, config.secureCookies)

        return Ok(Json.encodeToString(AuthResponse(user.id.toString(), user.username)))
    }
}
