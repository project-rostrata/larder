package larder.db

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

// recipeTitle/recipeDeleted come from a join on recipes, so a meal-plan view can render every
// entry -- including ones whose recipe was later soft-deleted -- without a fetch per entry.
data class MealPlanEntryRow(
    val id: UUID,
    val ownerId: UUID,
    val planDate: LocalDate,
    val mealSlot: String,
    val recipeId: UUID,
    val servingsMultiplier: BigDecimal,
    val createdAt: Instant,
    val recipeTitle: String,
    val recipeDeleted: Boolean,
)
