package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.MealPlanRepository
import larder.db.RecipeRepository
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.UUID

private const val MAX_RANGE_DAYS = 366
private const val MAX_SERVINGS_MULTIPLIER = 100.0

private fun parseDate(raw: String?): LocalDate? =
    raw?.let { try { LocalDate.parse(it) } catch (e: DateTimeParseException) { null } }

class MealPlanListHandler(private val mealPlan: MealPlanRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val from = parseDate(ctx.query["from"])
            ?: return Err(400, "INVALID_INPUT", "from must be a date (YYYY-MM-DD)")
        val to = parseDate(ctx.query["to"])
            ?: return Err(400, "INVALID_INPUT", "to must be a date (YYYY-MM-DD)")
        if (to.isBefore(from)) return Err(400, "INVALID_INPUT", "to must not be before from")
        if (ChronoUnit.DAYS.between(from, to) >= MAX_RANGE_DAYS) {
            return Err(400, "INVALID_INPUT", "range must be at most $MAX_RANGE_DAYS days")
        }
        val entries = mealPlan.list(user.id, from, to).map { it.toResponse() }
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

        val planDate = parseDate(request.planDate)
            ?: return Err(400, "INVALID_INPUT", "planDate must be a date (YYYY-MM-DD)")
        if (request.mealSlot !in MEAL_SLOTS) {
            return Err(400, "INVALID_INPUT", "mealSlot must be one of ${MEAL_SLOTS.joinToString()}")
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

        val entry = mealPlan.create(user.id, planDate, request.mealSlot, recipeId, BigDecimal.valueOf(multiplier))
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
