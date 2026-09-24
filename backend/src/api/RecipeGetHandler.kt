package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeRepository
import java.util.UUID

class RecipeGetHandler(private val recipes: RecipeRepository) {
    // Direct fetch by id, unlike listing, does NOT filter deleted_at -- a historical
    // meal_plan_entries row still needs to resolve the recipe it references after that recipe
    // has been soft-deleted. See PROJECT_BRIEF.md section 4.
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid recipe id")

        val recipe = recipes.findById(id, user.id) ?: return Err(404, "NOT_FOUND", "Recipe not found")
        val ingredients = recipes.findIngredients(recipe.id)

        return Ok(Json.encodeToString(recipe.toResponse(ingredients)))
    }
}
