package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.MealPlanRepository
import larder.db.RecipeRepository
import java.math.BigDecimal
import java.util.UUID

private const val MAX_SERVINGS_MULTIPLIER = 100.0
private const val MAX_LABEL_LENGTH = 100

class MealPlanListHandler(private val mealPlan: MealPlanRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val entries = mealPlan.list(user.id).map { it.toResponse() }
        return Ok(Json.encodeToString(MealPlanListResponse(entries)))
    }
}

class MealPlanCreateHandler(
    private val mealPlan: MealPlanRepository,
    private val recipes: RecipeRepository,
) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val request = try {
            Json.decodeFromString<MealPlanEntryRequest>(ctx.readBody())
        } catch (e: Exception) {
            return Err(400, "INVALID_BODY", "Malformed request body")
        }

        val label = request.label?.trim()?.takeIf { it.isNotEmpty() }
        if (label != null && label.length > MAX_LABEL_LENGTH) {
            return Err(400, "INVALID_INPUT", "label must be at most $MAX_LABEL_LENGTH characters")
        }
        val multiplier = request.servingsMultiplier
        if (!multiplier.isFinite() || multiplier <= 0.0 || multiplier > MAX_SERVINGS_MULTIPLIER) {
            return Err(400, "INVALID_INPUT", "servingsMultiplier must be > 0 and <= $MAX_SERVINGS_MULTIPLIER")
        }
        val recipeId = runCatching { UUID.fromString(request.recipeId) }.getOrNull()
            ?: return Err(400, "INVALID_INPUT", "invalid recipeId")

        // The explicit ownership check AGENTS.md requires for a client-supplied recipe_id. One
        // owner-scoped lookup answers both "doesn't exist" and "not yours" with the same 403,
        // so the response never reveals whether someone else's recipe id is real.
        val recipe = recipes.findById(recipeId, user.id)
            ?: return Err(403, "NOT_OWNED", "Recipe does not belong to the authenticated user")
        if (recipe.deletedAt != null) {
            return Err(422, "RECIPE_DELETED", "Cannot plan a deleted recipe")
        }

        val entry = mealPlan.create(user.id, recipeId, label, BigDecimal.valueOf(multiplier))
        return Ok(Json.encodeToString(entry.toResponse()))
    }
}

class MealPlanDeleteHandler(private val mealPlan: MealPlanRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid meal plan entry id")
        if (!mealPlan.delete(id, user.id)) return Err(404, "NOT_FOUND", "Meal plan entry not found")
        return Ok("""{"status":"ok"}""")
    }
}
