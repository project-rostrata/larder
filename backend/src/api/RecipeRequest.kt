package larder.api

import kotlinx.serialization.Serializable

@Serializable
data class RecipeIngredientInput(val rawText: String)

// Shared by create (POST) and update (PUT) — one request shape describes the desired final
// state of the recipe either way; update replaces recipe_ingredients wholesale rather than
// patching it. Defaults let a minimal request (just a title) through — nothing here is
// required beyond that.
@Serializable
data class RecipeRequest(
    val title: String,
    val sourceUrl: String? = null,
    val servings: Double? = null,
    val servingsText: String? = null,
    val prepTimeMinutes: Int? = null,
    val cookTimeMinutes: Int? = null,
    val totalTimeMinutes: Int? = null,
    val tags: List<String> = emptyList(),
    val instructions: List<String> = emptyList(),
    val ingredients: List<RecipeIngredientInput> = emptyList(),
)
