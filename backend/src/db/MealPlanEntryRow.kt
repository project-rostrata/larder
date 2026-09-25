package larder.db

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// recipe* fields come from a join on recipes, so a plan renders without a fetch per entry.
// recipeDeleted is only ever true for entries of archived plans: the active plan's queries
// filter soft-deleted recipes out entirely.
data class MealPlanEntryRow(
    val id: UUID,
    val ownerId: UUID,
    val mealPlanId: UUID,
    val recipeId: UUID,
    val label: String?,
    val servingsMultiplier: BigDecimal,
    val createdAt: Instant,
    val recipeTitle: String,
    val recipeServings: BigDecimal?,
    val recipeServingsText: String?,
    val recipeDeleted: Boolean,
)

data class MealPlanRow(val id: UUID, val ownerId: UUID, val createdAt: Instant, val archivedAt: Instant?)
