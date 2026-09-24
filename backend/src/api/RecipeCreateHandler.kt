package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeFields
import larder.db.RecipeRepository
import larder.ingredients.IngredientLineParser
import larder.ingredients.IngredientResolver
import java.math.BigDecimal

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
        val fields = RecipeFields(
            title = request.title,
            sourceUrl = request.sourceUrl,
            servings = request.servings?.let { BigDecimal.valueOf(it) },
            servingsText = request.servingsText,
            prepTimeMinutes = request.prepTimeMinutes,
            cookTimeMinutes = request.cookTimeMinutes,
            totalTimeMinutes = request.totalTimeMinutes,
            tags = request.tags,
            instructions = request.instructions,
        )

        val persisted = recipes.create(user.id, fields, resolvedLines)
        val response = persisted.toWriteResponse(resolvedLines.map { it.ingredientWasNewlyCreated })
        return Ok(Json.encodeToString(response))
    }
}
