package larder.api

import larder.ingredients.IngredientLineParser
import larder.ingredients.IngredientResolver
import larder.ingredients.ResolvedIngredientLine

// Shared by RecipeCreateHandler and RecipeUpdateHandler. Order matters and is preserved:
// RecipeRepository persists recipe_ingredients.position by list index, so this must not
// reorder, and the caller must zip the result back against the same input list (by index) to
// know which one is which.
fun resolveIngredientLines(
    parser: IngredientLineParser,
    resolver: IngredientResolver,
    lines: List<RecipeIngredientInput>,
): List<ResolvedIngredientLine> = lines.map { resolver.resolve(parser.parse(it.rawText)) }
