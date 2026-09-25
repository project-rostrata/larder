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
        if (request.servings != null && request.servingsMultiplier != null) {
            return Err(400, "INVALID_INPUT", "send servings or servingsMultiplier, not both")
        }
        val recipeId = runCatching { UUID.fromString(request.recipeId) }.getOrNull()
            ?: return Err(400, "INVALID_INPUT", "invalid recipeId")

        // The explicit ownership check AGENTS.md requires for a client-supplied recipe_id. One
        // owner-scoped, deleted-filtered lookup answers "doesn't exist", "deleted", and "not
        // yours" with the same 403, so the response never reveals whether someone else's recipe
        // id is real.
        val recipe = recipes.findById(recipeId, user.id)
            ?: return Err(403, "NOT_OWNED", "Recipe does not belong to the authenticated user")

        val multiplier = when {
            request.servings != null -> {
                val base = recipe.servings
                    ?: return Err(400, "INVALID_INPUT", "recipe has no numeric servings; send servingsMultiplier instead")
                if (!request.servings.isFinite() || request.servings <= 0.0) {
                    return Err(400, "INVALID_INPUT", "servings must be > 0")
                }
                request.servings / base.toDouble()
            }
            else -> request.servingsMultiplier ?: 1.0
        }
        if (!multiplier.isFinite() || multiplier <= 0.0 || multiplier > MAX_SERVINGS_MULTIPLIER) {
            return Err(400, "INVALID_INPUT", "servingsMultiplier must be > 0 and <= $MAX_SERVINGS_MULTIPLIER")
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

class MealPlanStartHandler(private val mealPlan: MealPlanRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val request = try {
            Json.decodeFromString<MealPlanStartRequest>(ctx.readBody().ifBlank { "{}" })
        } catch (e: Exception) {
            return Err(400, "INVALID_BODY", "Malformed request body")
        }
        val fromPlanId = request.fromPlanId?.let {
            runCatching { UUID.fromString(it) }.getOrNull() ?: return Err(400, "INVALID_INPUT", "invalid fromPlanId")
        }
        if (fromPlanId != null) {
            val (plan, _) = mealPlan.find(fromPlanId, user.id) ?: return Err(404, "NOT_FOUND", "Meal plan not found")
            if (plan.archivedAt == null) return Err(400, "INVALID_INPUT", "fromPlanId must be a past plan")
        }
        val current = mealPlan.activeEntryCount(user.id)
        if (current > 0 && !request.replace) {
            val recipes = if (current == 1) "1 recipe" else "$current recipes"
            return Err(409, "ACTIVE_PLAN_EXISTS", "Your current meal plan has $recipes. Replace it? It will be moved to history.")
        }
        mealPlan.startNew(user.id, fromPlanId)
        return Ok(Json.encodeToString(MealPlanListResponse(mealPlan.list(user.id).map { it.toResponse() })))
    }
}

class MealPlanHistoryHandler(private val mealPlan: MealPlanRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> =
        Ok(Json.encodeToString(MealPlanHistoryResponse(mealPlan.history(user.id).map { (plan, entries) -> plan.toSummary(entries) })))
}

class MealPlanGetHandler(private val mealPlan: MealPlanRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["id"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid meal plan id")
        val (plan, entries) = mealPlan.find(id, user.id) ?: return Err(404, "NOT_FOUND", "Meal plan not found")
        return Ok(Json.encodeToString(plan.toDetail(entries)))
    }
}
