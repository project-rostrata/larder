import com.sun.net.httpserver.HttpServer
import larder.api.Router
import larder.api.VersionHandler
import larder.api.healthHandler
import larder.db.ConnectionPool
import larder.db.Database
import java.net.InetSocketAddress
import java.util.concurrent.Executors
import java.util.logging.Logger

private val logger = Logger.getLogger("larder.Main")

// Composition root — every service is constructed here, once, and wired into the router by
// hand. No DI framework; see AGENTS.md. No migration runner or schema-dependent wiring yet —
// that lands in Phase 2, since Phase 1's only job is the router/JSON/JDBC pattern itself.
fun main() {
    val port = (System.getenv("LARDER_PORT") ?: "8080").toInt()
    val dbUrl = System.getenv("LARDER_DB_URL") ?: error("LARDER_DB_URL is required")
    val dbUser = System.getenv("LARDER_DB_USER") ?: error("LARDER_DB_USER is required")
    val dbPassword = System.getenv("LARDER_DB_PASSWORD") ?: error("LARDER_DB_PASSWORD is required")
    // Default comfortably covers the HTTP server's 8-thread executor below, with headroom —
    // same rationale as shelf's identical default.
    val dbPoolSize = (System.getenv("LARDER_DB_POOL_SIZE") ?: "10").toInt()

    val pool = ConnectionPool.open(dbUrl, dbUser, dbPassword, dbPoolSize)
    val database = Database(pool)
    val versionHandler = VersionHandler(database)

    val router = Router()
    router.get("/api/health", ::healthHandler)
    router.get("/api/version", versionHandler::handle)

    val server = HttpServer.create(InetSocketAddress(port), 0)
    server.executor = Executors.newFixedThreadPool(8)
    server.createContext("/", router.toHttpHandler())
    server.start()

    logger.info("larder listening on port $port")
}
