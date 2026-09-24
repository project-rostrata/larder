package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeFields
import larder.db.RecipeRepository
import larder.ingredients.IngredientLineParser
import larder.ingredients.IngredientResolver
import java.math.BigDecimal
import java.util.UUID

class RecipeUpdateHandler(
    private val recipes: RecipeRepository,
    private val parser: IngredientLineParser,
    private val resolver: IngredientResolver,
) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid recipe id")

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

        // update() returns null for "doesn't exist", "not yours", and "already deleted" alike
        // -- same 404 either way, per PROJECT_BRIEF.md section 4's soft-delete note.
        val persisted = recipes.update(id, user.id, fields, resolvedLines)
            ?: return Err(404, "NOT_FOUND", "Recipe not found")

        val response = persisted.toWriteResponse(resolvedLines.map { it.ingredientWasNewlyCreated })
        return Ok(Json.encodeToString(response))
    }
}
