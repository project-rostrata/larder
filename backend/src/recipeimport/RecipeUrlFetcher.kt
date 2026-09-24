package larder.recipeimport

import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

class RecipeFetchException(message: String) : Exception(message)

private const val MAX_REDIRECTS = 5
private const val USER_AGENT = "larder-recipe-importer/1.0 (+https://github.com/; self-hosted recipe manager)"

// Redirects are followed manually, one hop at a time, with validateImportUrl() re-run on every
// hop -- not HttpClient's built-in redirect handling. A URL that passes the SSRF guard once
// could still redirect to an internal address; only re-validating each hop actually closes
// that hole. Capped at MAX_REDIRECTS to avoid a redirect loop.
fun fetchRecipeHtml(httpClient: HttpClient, url: String): String {
    var currentUrl = url
    repeat(MAX_REDIRECTS + 1) {
        val uri = validateImportUrl(currentUrl)
        val request = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", USER_AGENT)
            .GET()
            .build()

        val response = try {
            httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        } catch (e: Exception) {
            throw RecipeFetchException("Could not reach that URL: ${e.message}")
        }

        when (response.statusCode()) {
            in 300..399 -> {
                val location = response.headers().firstValue("location").orElse(null)
                    ?: throw RecipeFetchException("Redirect response with no Location header")
                currentUrl = URI(currentUrl).resolve(location).toString()
            }
            200 -> return response.body()
            else -> throw RecipeFetchException("Fetch failed with HTTP status ${response.statusCode()}")
        }
    }
    throw RecipeFetchException("Too many redirects")
}
