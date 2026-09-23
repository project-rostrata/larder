import com.sun.net.httpserver.HttpServer
import larder.api.LoginHandler
import larder.api.LogoutHandler
import larder.api.MeHandler
import larder.api.RegisterHandler
import larder.api.Router
import larder.api.VersionHandler
import larder.api.healthHandler
import larder.api.requireAuth
import larder.auth.AuthConfig
import larder.db.ConnectionPool
import larder.db.Database
import larder.db.MigrationRunner
import larder.db.SessionRepository
import larder.db.UserRepository
import java.net.InetSocketAddress
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.logging.Logger

private val logger = Logger.getLogger("larder.Main")

// Composition root — every service is constructed here, once, and wired into the router by
// hand. No DI framework; see AGENTS.md.
fun main() {
    val port = (System.getenv("LARDER_PORT") ?: "8080").toInt()
    val dbUrl = System.getenv("LARDER_DB_URL") ?: error("LARDER_DB_URL is required")
    val dbUser = System.getenv("LARDER_DB_USER") ?: error("LARDER_DB_USER is required")
    val dbPassword = System.getenv("LARDER_DB_PASSWORD") ?: error("LARDER_DB_PASSWORD is required")
    val migrationsDir = System.getenv("LARDER_MIGRATIONS_DIR") ?: error("LARDER_MIGRATIONS_DIR is required")
    // Default comfortably covers the HTTP server's 8-thread executor below, with headroom —
    // same rationale as shelf's identical default.
    val dbPoolSize = (System.getenv("LARDER_DB_POOL_SIZE") ?: "10").toInt()
    val sessionDurationHours = (System.getenv("LARDER_SESSION_DURATION_HOURS") ?: "720").toLong()
    // Real deployments MUST override this to true — plain HTTP local dev needs it off. Same
    // caveat as shelf's identical setting.
    val secureCookies = (System.getenv("LARDER_SECURE_COOKIES") ?: "false").toBoolean()

    val pool = ConnectionPool.open(dbUrl, dbUser, dbPassword, dbPoolSize)
    pool.use { connection -> MigrationRunner(connection, Path.of(migrationsDir)).run() }

    val database = Database(pool)
    val authConfig = AuthConfig(sessionDurationHours, secureCookies)
    val users = UserRepository(database)
    val sessions = SessionRepository(database)

    val versionHandler = VersionHandler(database)
    val registerHandler = RegisterHandler(users, sessions, authConfig)
    val loginHandler = LoginHandler(users, sessions, authConfig)
    val logoutHandler = LogoutHandler(sessions, authConfig.secureCookies)
    val meHandler = MeHandler()

    val router = Router()
    router.get("/api/health", ::healthHandler)
    router.get("/api/version", versionHandler::handle)
    router.post("/api/register", registerHandler::handle)
    router.post("/api/login", loginHandler::handle)
    router.post("/api/logout", logoutHandler::handle)
    router.get("/api/me", requireAuth(sessions, users, meHandler::handle))

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(8)
    server.createContext("/", router.toHttpHandler())
    server.start()

    logger.info("larder listening on port $port")
}
