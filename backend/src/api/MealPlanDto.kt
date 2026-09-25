package larder.api

import kotlinx.serialization.Serializable
import larder.db.MealPlanEntryRow

// Fixed set rather than free text: the meal-planner UI is a date x slot grid, and a free-text
// slot would scatter "Dinner"/"dinner"/"supper" across separate rows of it.
val MEAL_SLOTS = listOf("breakfast", "lunch", "dinner", "snack")

@Serializable
data class MealPlanEntryRequest(
    val planDate: String,
    val mealSlot: String,
    val recipeId: String,
    val servingsMultiplier: Double = 1.0,
)

@Serializable
data class MealPlanEntryResponse(
    val id: String,
    val planDate: String,
    val mealSlot: String,
    val recipeId: String,
    val recipeTitle: String,
    val recipeDeleted: Boolean,
    val servingsMultiplier: Double,
    val createdAt: String,
)

@Serializable
data class MealPlanListResponse(val entries: List<MealPlanEntryResponse>)

fun MealPlanEntryRow.toResponse() = MealPlanEntryResponse(
    id = id.toString(),
    planDate = planDate.toString(),
    mealSlot = mealSlot,
    recipeId = recipeId.toString(),
    recipeTitle = recipeTitle,
    recipeDeleted = recipeDeleted,
    servingsMultiplier = servingsMultiplier.toDouble(),
    createdAt = createdAt.toString(),
)
