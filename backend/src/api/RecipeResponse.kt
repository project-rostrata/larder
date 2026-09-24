package larder.api

import kotlinx.serialization.Serializable
import larder.db.PersistedRecipe
import larder.db.RecipeIngredientRow
import larder.db.RecipeRow

@Serializable
data class RecipeIngredientResponse(
    val id: String,
    val rawText: String,
    val notes: String?,
    val quantityNumerator: Int?,
    val quantityDenominator: Int?,
    val unitId: String?,
    val ingredientId: String?,
)

@Serializable
data class RecipeResponse(
    val id: String,
    val title: String,
    val sourceUrl: String?,
    val servings: Double?,
    val servingsText: String?,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val totalTimeMinutes: Int?,
    val tags: List<String>,
    val instructions: List<String>,
    val ingredients: List<RecipeIngredientResponse>,
    val createdAt: String,
    val updatedAt: String,
)

// Separate from RecipeIngredientResponse specifically for ingredientWasNewlyCreated: a fact
// about *this write*, not a durable property of the row worth reporting on every later read.
// See PROJECT_BRIEF.md section 5 — the deferred "is this ingredient known?" UI's hook.
@Serializable
data class RecipeIngredientWriteResult(
    val id: String,
    val rawText: String,
    val notes: String?,
    val quantityNumerator: Int?,
    val quantityDenominator: Int?,
    val unitId: String?,
    val ingredientId: String?,
    val ingredientWasNewlyCreated: Boolean,
)

@Serializable
data class RecipeWriteResponse(
    val id: String,
    val title: String,
    val sourceUrl: String?,
    val servings: Double?,
    val servingsText: String?,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val totalTimeMinutes: Int?,
    val tags: List<String>,
    val instructions: List<String>,
    val ingredients: List<RecipeIngredientWriteResult>,
    val createdAt: String,
    val updatedAt: String,
)

fun RecipeRow.toResponse(ingredients: List<RecipeIngredientRow>): RecipeResponse = RecipeResponse(
    id = id.toString(),
    title = title,
    sourceUrl = sourceUrl,
    servings = servings?.toDouble(),
    servingsText = servingsText,
    prepTimeMinutes = prepTimeMinutes,
    cookTimeMinutes = cookTimeMinutes,
    totalTimeMinutes = totalTimeMinutes,
    tags = tags,
    instructions = instructions,
    ingredients = ingredients.map {
        RecipeIngredientResponse(
            id = it.id.toString(),
            rawText = it.rawText,
            notes = it.notes,
            quantityNumerator = it.quantityNumerator,
            quantityDenominator = it.quantityDenominator,
            unitId = it.unitId?.toString(),
            ingredientId = it.ingredientId?.toString(),
        )
    },
    createdAt = createdAt.toString(),
    updatedAt = updatedAt.toString(),
)

// newlyCreatedFlags must be in the same order as persisted.ingredients — both ultimately trace
// back to the same input ingredient-line list, positionally.
fun PersistedRecipe.toWriteResponse(newlyCreatedFlags: List<Boolean>): RecipeWriteResponse = RecipeWriteResponse(
    id = recipe.id.toString(),
    title = recipe.title,
    sourceUrl = recipe.sourceUrl,
    servings = recipe.servings?.toDouble(),
    servingsText = recipe.servingsText,
    prepTimeMinutes = recipe.prepTimeMinutes,
    cookTimeMinutes = recipe.cookTimeMinutes,
    totalTimeMinutes = recipe.totalTimeMinutes,
    tags = recipe.tags,
    instructions = recipe.instructions,
    ingredients = ingredients.zip(newlyCreatedFlags).map { (row, wasNew) ->
        RecipeIngredientWriteResult(
            id = row.id.toString(),
            rawText = row.rawText,
            notes = row.notes,
            quantityNumerator = row.quantityNumerator,
            quantityDenominator = row.quantityDenominator,
            unitId = row.unitId?.toString(),
            ingredientId = row.ingredientId?.toString(),
            ingredientWasNewlyCreated = wasNew,
        )
    },
    createdAt = recipe.createdAt.toString(),
    updatedAt = recipe.updatedAt.toString(),
)
