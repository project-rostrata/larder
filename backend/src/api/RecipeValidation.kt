package larder.api

// Shared by RecipeCreateHandler and RecipeUpdateHandler. Returns an error message, or null if
// the request is valid.
fun validateRecipeRequest(request: RecipeRequest): String? = when {
    request.title.isBlank() -> "title is required"
    request.servings != null && request.servings <= 0 -> "servings must be positive"
    request.prepTimeMinutes != null && request.prepTimeMinutes < 0 -> "prepTimeMinutes cannot be negative"
    request.cookTimeMinutes != null && request.cookTimeMinutes < 0 -> "cookTimeMinutes cannot be negative"
    request.totalTimeMinutes != null && request.totalTimeMinutes < 0 -> "totalTimeMinutes cannot be negative"
    request.ingredients.any { it.rawText.isBlank() } -> "ingredient raw text cannot be blank"
    else -> null
}
