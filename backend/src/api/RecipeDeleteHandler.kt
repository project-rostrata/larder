package larder.api

import larder.db.RecipeRepository
import java.util.UUID

class RecipeDeleteHandler(private val recipes: RecipeRepository) {
    // Soft delete -- see PROJECT_BRIEF.md section 4. Sets deleted_at; never removes the row or
    // its recipe_ingredients. "doesn't exist", "not yours", and "already deleted" all report
    // the same 404, same as RecipeUpdateHandler.
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid recipe id")

        if (!recipes.softDelete(id, user.id)) {
            return Err(404, "NOT_FOUND", "Recipe not found")
        }

        return Ok("""{"status":"ok"}""")
    }
}
