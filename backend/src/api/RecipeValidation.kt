package larder.api

private const val MAX_NOTES_LENGTH = 20_000

// Shared by RecipeCreateHandler and RecipeUpdateHandler. Returns an error message, or null if
// the request is valid.
fun validateRecipeRequest(request: RecipeRequest): String? = when {
    request.title.isBlank() -> "title is required"
    request.servings != null && request.servings <= 0 -> "servings must be positive"
    request.prepTimeMinutes != null && request.prepTimeMinutes < 0 -> "prepTimeMinutes cannot be negative"
    request.cookTimeMinutes != null && request.cookTimeMinutes < 0 -> "cookTimeMinutes cannot be negative"
    request.totalTimeMinutes != null && request.totalTimeMinutes < 0 -> "totalTimeMinutes cannot be negative"
    request.ingredients.any { it.rawText.isBlank() } -> "ingredient raw text cannot be blank"
    (request.notes?.length ?: 0) > MAX_NOTES_LENGTH -> "notes must be at most $MAX_NOTES_LENGTH characters"
    else -> null
}
