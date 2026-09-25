package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeRepository
import java.util.UUID

class RecipeGetHandler(private val recipes: RecipeRepository) {
    // A soft-deleted recipe is a 404, same as update/delete -- RecipeRepository.findById filters it.
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid recipe id")

        val recipe = recipes.findById(id, user.id) ?: return Err(404, "NOT_FOUND", "Recipe not found")
        val ingredients = recipes.findIngredients(recipe.id, user.id)

        return Ok(Json.encodeToString(recipe.toResponse(ingredients)))
    }
}
