package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeRepository
import larder.ingredients.IngredientLineParser
import larder.ingredients.IngredientResolver

class RecipeCreateHandler(
    private val recipes: RecipeRepository,
    private val parser: IngredientLineParser,
    private val resolver: IngredientResolver,
) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val request = try {
            Json.decodeFromString<RecipeRequest>(ctx.readBody())
        } catch (e: Exception) {
            return Err(400, "INVALID_BODY", "Malformed request body")
        }

        validateRecipeRequest(request)?.let { return Err(400, "INVALID_INPUT", it) }

        val resolvedLines = resolveIngredientLines(parser, resolver, request.ingredients)
        val persisted = recipes.create(user.id, request.toFields(), resolvedLines)
        val response = persisted.toWriteResponse(resolvedLines.map { it.ingredientWasNewlyCreated })
        return Ok(Json.encodeToString(response))
    }
}
