package larder.api

import larder.recipeimport.ImportedRecipe

// Shared by URL import and file import: both turn an ImportedRecipe into the same RecipeRequest
// that manual create uses, so every recipe validates and persists through one path.
fun ImportedRecipe.toRecipeRequest() = RecipeRequest(
    title = title,
    sourceUrl = sourceUrl,
    servings = servings?.toDouble(),
    servingsText = servingsText,
    prepTimeMinutes = prepTimeMinutes,
    cookTimeMinutes = cookTimeMinutes,
    totalTimeMinutes = totalTimeMinutes,
    tags = tags,
    instructions = instructions,
    ingredients = ingredientRawTexts.map { RecipeIngredientInput(it) },
    notes = notes,
)
