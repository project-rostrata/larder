package larder.ingredients

import larder.db.IngredientRepository
import larder.db.UnitRepository
import java.util.UUID

// What a recipe_ingredients row actually needs: resolved ids, not raw guessed strings. Whether
// ingredientId was newly created by this call is preserved through to here rather than
// discarded -- see PROJECT_BRIEF.md section 5 (the deferred "is this ingredient known?" UI).
data class ResolvedIngredientLine(
    val quantityNumerator: Int?,
    val quantityDenominator: Int?,
    val unitId: UUID?,
    val ingredientId: UUID?,
    val ingredientWasNewlyCreated: Boolean,
    val notes: String?,
    val rawText: String,
)

// Takes an IngredientLineParser's raw split and resolves it against larder's own units/
// ingredients tables. Kept separate from IngredientLineParser itself specifically so the
// parser doesn't need database access -- this class is what does.
class IngredientResolver(
    private val units: UnitRepository,
    private val ingredients: IngredientRepository,
) {
    fun resolve(parsed: ParsedIngredientLine): ResolvedIngredientLine {
        val unitId = parsed.unitWord?.let { units.findByNameOrAlias(it) }?.id

        val (ingredientId, wasNew) = parsed.ingredientName
            ?.let(::normalizeIngredientName)
            ?.let { ingredients.findOrCreate(it) }
            ?: (null to false)

        return ResolvedIngredientLine(
            quantityNumerator = parsed.quantityNumerator,
            quantityDenominator = parsed.quantityDenominator,
            unitId = unitId,
            ingredientId = ingredientId,
            ingredientWasNewlyCreated = wasNew,
            notes = parsed.notes,
            rawText = parsed.rawText,
        )
    }
}

// The parser occasionally carries sentence punctuation into a name -- found in real data:
// "1.5 - 2 cups milk. 1.5 is kinda cakey" names the ingredient "milk.", which then never
// matched "milk" on a shopping list. Leading/trailing punctuation and repeated whitespace are
// stripped before lookup; null if nothing is left.
fun normalizeIngredientName(name: String): String? =
    name.replace(Regex("\\s+"), " ")
        .trim { it.isWhitespace() || it in ".,;:!?*\"'()[]" }
        .takeIf { it.isNotEmpty() }
