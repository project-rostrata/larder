package larder.recipeimport

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.math.BigDecimal

data class ImportedRecipe(
    val title: String,
    val sourceUrl: String,
    val servings: BigDecimal?,
    val servingsText: String?,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val totalTimeMinutes: Int?,
    val tags: List<String>,
    val instructions: List<String>,
    val ingredientRawTexts: List<String>,
)

private val JSON_LD_SCRIPT = Regex(
    """<script[^>]+type\s*=\s*["']application/ld\+json["'][^>]*>(.*?)</script>""",
    setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
)
private val LEADING_NUMBER = Regex("""\d+(\.\d+)?""")

// Pure (no I/O) -- kept as a plain top-level function specifically so it's directly
// unit-testable against real captured HTML, same pattern as
// larder.ingredients.parseSidecarResponse(). See backend/test/JsonLdRecipeParserTest.kt.
//
// Scans every <script type="application/ld+json"> block on the page and returns the first
// schema.org Recipe object found (top-level, inside a top-level array, or inside an @graph
// array -- all three occur on real sites, confirmed by fetching real pages during this
// phase's design, not assumed). Returns null if no block contains a parseable Recipe --
// RecipeImportHandler turns that into a 422, per AGENTS.md's error-handling table.
fun extractRecipeFromHtml(html: String, sourceUrl: String): ImportedRecipe? {
    for (match in JSON_LD_SCRIPT.findAll(html)) {
        val root = try {
            Json.parseToJsonElement(match.groupValues[1])
        } catch (e: Exception) {
            continue
        }
        val recipeObject = findRecipeObject(root) ?: continue
        return mapRecipeObject(recipeObject, sourceUrl)
    }
    return null
}

// Safe casts (as?) throughout, not the throwing .jsonObject/.jsonArray convenience properties
// -- a schema.org object found in the wild is never as clean as the spec, and a field being
// present-but-null (JsonNull, not Kotlin null) or an unexpected shape must degrade gracefully,
// never throw. See larder.ingredients.SidecarIngredientLineParser's own JsonNull lesson.
private fun findRecipeObject(element: JsonElement): JsonObject? = when (element) {
    is JsonObject -> if (isRecipeType(element)) {
        element
    } else {
        (element["@graph"] as? JsonArray)?.firstNotNullOfOrNull { item ->
            (item as? JsonObject)?.takeIf { isRecipeType(it) }
        }
    }
    is JsonArray -> element.firstNotNullOfOrNull { findRecipeObject(it) }
    else -> null
}

private fun isRecipeType(obj: JsonObject): Boolean = when (val type = obj["@type"]) {
    is JsonPrimitive -> type.contentOrNull == "Recipe"
    is JsonArray -> type.any { (it as? JsonPrimitive)?.contentOrNull == "Recipe" }
    else -> false
}

private fun mapRecipeObject(obj: JsonObject, sourceUrl: String): ImportedRecipe {
    val (servings, servingsText) = extractYield(obj["recipeYield"])
    return ImportedRecipe(
        title = (obj["name"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
        sourceUrl = sourceUrl,
        servings = servings,
        servingsText = servingsText,
        prepTimeMinutes = extractDurationMinutes(obj["prepTime"]),
        cookTimeMinutes = extractDurationMinutes(obj["cookTime"]),
        totalTimeMinutes = extractDurationMinutes(obj["totalTime"]),
        tags = extractKeywords(obj["keywords"]),
        instructions = extractInstructions(obj["recipeInstructions"]),
        ingredientRawTexts = extractStringList(obj["recipeIngredient"]),
    )
}

// recipeIngredient is confirmed (PROJECT_BRIEF.md section 4, via recipe-scrapers' own source)
// to always be raw unparsed strings -- but real pages aren't always spec-compliant about
// wrapping it in an array, and real ingredient strings carry stray leading/trailing whitespace
// often enough that trimming here is worth doing rather than passing it through.
private fun extractStringList(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    else -> emptyList()
}

// recipeYield in the wild is a string ("4 servings", "1 pound per serving"), an array of
// strings, or a bare number -- never guaranteed to be a clean serving count. Extracts the
// first number found for the structured `servings` field on a best-effort basis; the full
// original text is always kept as `servingsText` regardless, so a bad extraction here is
// never a data-loss failure, same principle as raw_text throughout this app.
private fun extractYield(element: JsonElement?): Pair<BigDecimal?, String?> {
    val text = when (element) {
        is JsonArray -> element.firstOrNull()?.let { (it as? JsonPrimitive)?.contentOrNull }
        is JsonPrimitive -> element.contentOrNull
        else -> null
    }?.trim()?.takeIf { it.isNotEmpty() } ?: return null to null
    val servings = LEADING_NUMBER.find(text)?.value?.toBigDecimalOrNull()
    return servings to text
}

// prepTime/cookTime/totalTime are ISO 8601 durations ("PT15M", "PT1H30M") -- java.time.Duration
// parses this format directly, zero new dependency. Malformed values (a site that doesn't
// actually comply with the spec) degrade to null rather than failing the whole import.
private fun extractDurationMinutes(element: JsonElement?): Int? {
    val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
    return try {
        java.time.Duration.parse(text).toMinutes().toInt()
    } catch (e: Exception) {
        null
    }
}

private fun extractKeywords(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
    is JsonPrimitive -> element.contentOrNull
        ?.split(",")
        ?.map { it.trim() }
        ?.filter { it.isNotEmpty() }
        ?: emptyList()
    else -> emptyList()
}

// recipeInstructions in the wild is an array of plain strings, an array of HowToStep objects
// (each with a "text" field -- the food.com fixture below is real captured data confirming
// this shape), or occasionally HowToSection objects grouping steps under itemListElement --
// handled by recursing into itemListElement rather than requiring a flat list.
private fun extractInstructions(element: JsonElement?): List<String> = when (element) {
    is JsonArray -> element.flatMap { extractInstructionStep(it) }
    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    else -> emptyList()
}

private fun extractInstructionStep(element: JsonElement): List<String> = when (element) {
    is JsonPrimitive -> element.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: emptyList()
    is JsonObject -> {
        val text = (element["text"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
        text?.let { listOf(it) }
            ?: (element["itemListElement"] as? JsonArray)?.flatMap { extractInstructionStep(it) }
            ?: emptyList()
    }
    else -> emptyList()
}
