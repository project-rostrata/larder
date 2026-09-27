package larder.api

import java.nio.file.Files
import java.nio.file.Path

// Serves the frontend's static files — a fixed, developer-controlled directory, not
// user-supplied content, so this doesn't need shelf's resolveUserPath path-safety machinery
// (larder has no per-user filesystem content at all, see PROJECT_BRIEF.md section 4). Still
// canonicalizes and checks containment defensively, since the request path segment comes from
// the URL regardless of whose content it is.
class StaticFileHandler(root: Path) {
    private val realRoot = root.toRealPath()

    fun serve(requestPath: String): StaticResult {
        val relative = if (requestPath == "/" || requestPath.isEmpty()) "index.html" else requestPath.removePrefix("/")
        val resolved = try {
            realRoot.resolve(relative).toRealPath()
        } catch (e: Exception) {
            return StaticFailed(Err(404, "NOT_FOUND", "No such file: $requestPath"))
        }

        if (!resolved.startsWith(realRoot) || !Files.isRegularFile(resolved)) {
            return StaticFailed(Err(404, "NOT_FOUND", "No such file: $requestPath"))
        }

        return StaticFile(contentTypeFor(resolved), Files.readAllBytes(resolved))
    }

    // Extension-based, not Files.probeContentType() — browsers are strict about
    // Content-Type: text/javascript for <script type="module">, and probeContentType()'s
    // OS-level MIME detection isn't reliable on a minimal container. Same reasoning as shelf's
    // identical StaticFileHandler.
    private fun contentTypeFor(path: Path): String = when (path.toString().substringAfterLast('.', "")) {
        "html" -> "text/html; charset=utf-8"
        "js" -> "text/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "ico" -> "image/x-icon"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }
}
