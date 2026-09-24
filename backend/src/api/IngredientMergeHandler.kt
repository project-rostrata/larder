package larder.api

import larder.db.IngredientRepository
import java.util.UUID

// Any authenticated user, not just whoever created either ingredient -- ingredients are global,
// shared vocabulary (PROJECT_BRIEF.md section 4), not owner_id-scoped, so there's no per-user
// ownership to check here, deliberately.
class IngredientMergeHandler(private val ingredients: IngredientRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid ingredient id")
        val targetId = ctx.pathParams["targetId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid target ingredient id")

        if (id == targetId) {
            return Err(400, "INVALID_INPUT", "cannot merge an ingredient into itself")
        }
        if (ingredients.findById(id) == null) {
            return Err(404, "NOT_FOUND", "Ingredient not found")
        }
        if (ingredients.findById(targetId) == null) {
            return Err(404, "NOT_FOUND", "Target ingredient not found")
        }

        ingredients.mergeInto(id, targetId)

        return Ok("""{"status":"ok"}""")
    }
}
