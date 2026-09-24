package larder.db

import java.util.UUID

data class RecipeIngredientRow(
    val id: UUID,
    val recipeId: UUID,
    val position: Int,
    val rawText: String,
    val notes: String?,
    val quantityNumerator: Int?,
    val quantityDenominator: Int?,
    val unitId: UUID?,
    val ingredientId: UUID?,
)
