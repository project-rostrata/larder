package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeRepository
import larder.ingredients.IngredientLineParser
import larder.ingredients.IngredientResolver
import larder.recipeimport.RecipeFetchException
import larder.recipeimport.UnsafeImportUrlException
import larder.recipeimport.extractRecipeFromHtml
import larder.recipeimport.fetchRecipeHtml
import java.net.http.HttpClient
import java.time.Duration

class RecipeImportHandler(
    private val recipes: RecipeRepository,
    private val parser: IngredientLineParser,
    private val resolver: IngredientResolver,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(10))
        // Redirects are followed manually (larder.recipeimport.fetchRecipeHtml), each hop
        // re-validated against the SSRF guard -- HttpClient's own redirect handling would
        // bypass that.
        .followRedirects(HttpClient.Redirect.NEVER)
        .build(),
) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val request = try {
            Json.decodeFromString<RecipeImportRequest>(ctx.readBody())
        } catch (e: Exception) {
            return Err(400, "INVALID_BODY", "Malformed request body")
        }

        // A bad URL (wrong scheme, resolves to a private/internal address) is a client-input
        // problem -- 400, same bucket as any other INVALID_INPUT. A URL that's fine but
        // doesn't yield a usable recipe is a different situation -- 422, per AGENTS.md's
        // error-handling table -- since the *request* was well-formed, the *content* wasn't
        // usable.
        val html = try {
            fetchRecipeHtml(httpClient, request.url)
        } catch (e: UnsafeImportUrlException) {
            return Err(400, "INVALID_INPUT", e.message ?: "Invalid URL")
        } catch (e: RecipeFetchException) {
            return Err(422, "IMPORT_FAILED", e.message ?: "Could not fetch that URL")
        }

        val imported = extractRecipeFromHtml(html, request.url)
            ?: return Err(422, "IMPORT_FAILED", "No recipe data found at that URL")

        val recipeRequest = imported.toRecipeRequest()
        // Same validation as create/update, but any failure here means "the page's data isn't
        // usable" (422), not "the client's request to us was malformed" (400) -- the request
        // to *us* (just a URL) was perfectly well-formed.
        validateRecipeRequest(recipeRequest)?.let { return Err(422, "IMPORT_FAILED", it) }

        val resolvedLines = resolveIngredientLines(parser, resolver, recipeRequest.ingredients)
        val persisted = recipes.create(user.id, recipeRequest.toFields(), resolvedLines)
        val response = persisted.toWriteResponse(resolvedLines.map { it.ingredientWasNewlyCreated })
        return Ok(Json.encodeToString(response))
    }
}
