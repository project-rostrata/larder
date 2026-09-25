package larder.api

import kotlinx.serialization.Serializable
import larder.db.MealPlanEntryRow

@Serializable
data class MealPlanEntryRequest(
    val recipeId: String,
    val label: String? = null,
    val servingsMultiplier: Double = 1.0,
)

@Serializable
data class MealPlanEntryResponse(
    val id: String,
    val recipeId: String,
    val recipeTitle: String,
    val recipeDeleted: Boolean,
    val label: String?,
    val servingsMultiplier: Double,
    val createdAt: String,
)

@Serializable
data class MealPlanListResponse(val entries: List<MealPlanEntryResponse>)

fun MealPlanEntryRow.toResponse() = MealPlanEntryResponse(
    id = id.toString(),
    recipeId = recipeId.toString(),
    recipeTitle = recipeTitle,
    recipeDeleted = recipeDeleted,
    label = label,
    servingsMultiplier = servingsMultiplier.toDouble(),
    createdAt = createdAt.toString(),
)
