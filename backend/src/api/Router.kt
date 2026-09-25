package larder.api

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpHandler
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.net.URLDecoder
import java.util.logging.Level
import java.util.logging.Logger

typealias Handler = (RouteContext) -> ApiResult<String>

class RouteContext(
    val pathParams: Map<String, String>,
    val query: Map<String, String>,
    val exchange: HttpExchange,
)

fun RouteContext.readBody(): String = exchange.requestBody.readAllBytes().decodeToString()

// A non-JSON response — used only for serving the frontend's static files (Phase 9). Unlike
// shelf's equivalent, larder's frontend files are all small (no arbitrary-size user downloads
// exist in this app at all), so this buffers the whole body rather than streaming — simpler,
// and there's nothing here that would ever be large enough for that tradeoff to matter.
sealed class StaticResult
data class StaticFile(val contentType: String, val body: ByteArray) : StaticResult()
data class StaticFailed(val err: Err) : StaticResult()

sealed class MatchResult {
    data class Matched(val handler: Handler, val pathParams: Map<String, String>) : MatchResult()
    object NotFound : MatchResult()
}

private sealed class Segment {
    data class Literal(val value: String) : Segment()
    data class Param(val name: String) : Segment()
}

private data class Route(val method: String, val segments: List<Segment>, val handler: Handler)

fun parseQuery(raw: String?): Map<String, String> {
    if (raw.isNullOrEmpty()) return emptyMap()
    return raw.split('&').mapNotNull { pair ->
        val idx = pair.indexOf('=')
        if (idx < 0) null
        else URLDecoder.decode(pair.substring(0, idx), "UTF-8") to URLDecoder.decode(pair.substring(idx + 1), "UTF-8")
    }.toMap()
}

private fun splitPath(path: String): List<String> = path.trim('/').split('/').filter { it.isNotEmpty() }

// Hand-rolled router on com.sun.net.httpserver.HttpServer, ported from shelf's Router.kt — see
// PROJECT_BRIEF.md §2 and V1_PLAN.md Phase 1. serveStatic() added Phase 9 — the first thing
// needing a non-JSON response (browsers are strict about Content-Type for <script type="module">,
// so static files can't go through the JSON-envelope respond() path below).
class Router {
    private val logger = Logger.getLogger(Router::class.qualifiedName)
    private val routes = mutableListOf<Route>()
    private var staticFallback: ((String) -> StaticResult)? = null

    fun get(pattern: String, handler: Handler) {
        routes += Route("GET", parsePattern(pattern), handler)
    }

    fun post(pattern: String, handler: Handler) {
        routes += Route("POST", parsePattern(pattern), handler)
    }

    fun put(pattern: String, handler: Handler) {
        routes += Route("PUT", parsePattern(pattern), handler)
    }

    fun patch(pattern: String, handler: Handler) {
        routes += Route("PATCH", parsePattern(pattern), handler)
    }

    fun delete(pattern: String, handler: Handler) {
        routes += Route("DELETE", parsePattern(pattern), handler)
    }

    // Tried only when no /api/* route matched, method is GET, and the path isn't under /api/ —
    // a mistyped API route still gets a clean API-shaped 404, not a static "no such file".
    fun serveStatic(handler: (String) -> StaticResult) {
        staticFallback = handler
    }

    private fun parsePattern(pattern: String): List<Segment> =
        splitPath(pattern).map {
            if (it.startsWith(":")) Segment.Param(it.substring(1)) else Segment.Literal(it)
        }

    fun match(method: String, path: String): MatchResult {
        val pathSegments = splitPath(path)
        val route = routes.firstOrNull { it.method == method && segmentsMatch(it.segments, pathSegments) }
            ?: return MatchResult.NotFound
        return MatchResult.Matched(route.handler, extractParams(route.segments, pathSegments))
    }

    private fun segmentsMatch(segments: List<Segment>, path: List<String>): Boolean {
        if (segments.size != path.size) return false
        return segments.zip(path).all { (seg, actual) ->
            when (seg) {
                is Segment.Literal -> seg.value == actual
                is Segment.Param -> true
            }
        }
    }

    private fun extractParams(segments: List<Segment>, path: List<String>): Map<String, String> =
        segments.zip(path).mapNotNull { (seg, actual) ->
            if (seg is Segment.Param) seg.name to URLDecoder.decode(actual, "UTF-8") else null
        }.toMap()

    fun toHttpHandler(): HttpHandler = HttpHandler { exchange ->
        try {
            dispatch(exchange)
        } finally {
            exchange.close()
        }
    }

    private fun dispatch(exchange: HttpExchange) {
        val method = exchange.requestMethod
        val path = exchange.requestURI.path
        when (val match = match(method, path)) {
            is MatchResult.NotFound -> {
                val fallback = staticFallback
                if (method == "GET" && !path.startsWith("/api/") && fallback != null) {
                    respondStatic(exchange, guardedStatic(method, path) { fallback(path) })
                } else {
                    respondJson(exchange, Err(404, "NOT_FOUND", "No route for $method $path"))
                }
            }
            is MatchResult.Matched -> {
                val ctx = RouteContext(match.pathParams, parseQuery(exchange.requestURI.rawQuery), exchange)
                respondJson(exchange, guarded(method, path) { match.handler(ctx) })
            }
        }
    }

    private fun guarded(method: String, path: String, block: () -> ApiResult<String>): ApiResult<String> = try {
        block()
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "unhandled exception in handler for $method $path", e)
        Err(500, "INTERNAL_ERROR", "Unexpected server error")
    }

    private fun guardedStatic(method: String, path: String, block: () -> StaticResult): StaticResult = try {
        block()
    } catch (e: Exception) {
        logger.log(Level.SEVERE, "unhandled exception serving static file for $method $path", e)
        StaticFailed(Err(500, "INTERNAL_ERROR", "Unexpected server error"))
    }

    private fun respondJson(exchange: HttpExchange, result: ApiResult<String>) {
        val status: Int
        val body: String
        when (result) {
            is Ok -> {
                status = 200
                body = result.value
            }
            is Err -> {
                status = result.status
                body = Json.encodeToString(ErrorEnvelope(ErrorBody(result.code, result.message)))
            }
        }
        val bytes = body.toByteArray(Charsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json; charset=utf-8")
        exchange.sendResponseHeaders(status, bytes.size.toLong())
        exchange.responseBody.write(bytes)
    }

    private fun respondStatic(exchange: HttpExchange, result: StaticResult) {
        when (result) {
            is StaticFailed -> respondJson(exchange, result.err)
            is StaticFile -> {
                exchange.responseHeaders.add("Content-Type", result.contentType)
                exchange.sendResponseHeaders(200, result.body.size.toLong())
                exchange.responseBody.write(result.body)
            }
        }
    }
}
