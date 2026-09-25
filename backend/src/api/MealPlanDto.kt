package larder.api

import kotlinx.serialization.Serializable
import larder.db.MealPlanEntryRow
import java.math.BigDecimal

@Serializable
data class MealPlanEntryRequest(
    val recipeId: String,
    val label: String? = null,
    // At most one of these. `servings` is what the user wants to make; the API converts it to a
    // multiplier against the recipe's numeric servings. Neither means the recipe as written.
    val servings: Double? = null,
    val servingsMultiplier: Double? = null,
)

@Serializable
data class MealPlanEntryResponse(
    val id: String,
    val recipeId: String,
    val recipeTitle: String,
    val label: String?,
    val servingsMultiplier: Double,
    // "6 servings" when the recipe has numeric servings (already scaled), otherwise "×1.5" when
    // scaled, otherwise the recipe's own servings text (may be null).
    val servingsDisplay: String?,
    val createdAt: String,
)

@Serializable
data class MealPlanListResponse(val entries: List<MealPlanEntryResponse>)

fun MealPlanEntryRow.toResponse() = MealPlanEntryResponse(
    id = id.toString(),
    recipeId = recipeId.toString(),
    recipeTitle = recipeTitle,
    label = label,
    servingsMultiplier = servingsMultiplier.toDouble(),
    servingsDisplay = when {
        recipeServings != null -> formatServings(recipeServings.multiply(servingsMultiplier))
        servingsMultiplier.compareTo(BigDecimal.ONE) != 0 -> "×${formatNumber(servingsMultiplier)}"
        else -> recipeServingsText
    },
    createdAt = createdAt.toString(),
)
