package larder.db

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

// recipe* fields come from a join on recipes, so the meal plan renders without a fetch per entry.
data class MealPlanEntryRow(
    val id: UUID,
    val ownerId: UUID,
    val recipeId: UUID,
    val label: String?,
    val servingsMultiplier: BigDecimal,
    val createdAt: Instant,
    val recipeTitle: String,
    val recipeServings: BigDecimal?,
    val recipeServingsText: String?,
)
