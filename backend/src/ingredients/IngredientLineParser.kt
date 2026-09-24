package larder.ingredients

// The result of splitting a raw ingredient line into its structural pieces -- NOT yet resolved
// against larder's own units/ingredients tables (that's IngredientResolver's job, kept
// separate so this interface needs no database access and stays easily testable/swappable, per
// AGENTS.md's DI philosophy). unitWord/ingredientName are the parser's raw guesses as plain
// strings; quantity is already an exact fraction, never a float.
data class ParsedIngredientLine(
    val quantityNumerator: Int?,
    val quantityDenominator: Int?,
    val unitWord: String?,
    val ingredientName: String?,
    val notes: String?,
    val rawText: String,
)

// One implementation today (SidecarIngredientLineParser, calling the ingredient-parser/
// Python service) -- kept as an interface so Phase 5/6 depend on this contract, not on how
// parsing actually happens underneath.
interface IngredientLineParser {
    fun parse(rawText: String): ParsedIngredientLine
}
