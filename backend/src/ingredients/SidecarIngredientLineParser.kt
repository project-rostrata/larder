package larder.ingredients

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.logging.Level
import java.util.logging.Logger

// Calls the ingredient-parser/ sidecar (PROJECT_BRIEF.md section 4) over HTTP. Any failure --
// connection refused, timeout, non-200, unparseable body -- degrades to an all-null parse
// rather than propagating: the caller (Phase 5/6) always has raw_text to fall back to, exactly
// like a bad regex parse would have under the original plan. Never throws.
class SidecarIngredientLineParser(
    baseUrl: String,
    private val httpClient: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(2))
        .build(),
) : IngredientLineParser {
    private val logger = Logger.getLogger(SidecarIngredientLineParser::class.qualifiedName)
    private val parseUri = URI.create("${baseUrl.trimEnd('/')}/parse")

    override fun parse(rawText: String): ParsedIngredientLine =
        try {
            callSidecar(rawText)
        } catch (e: Exception) {
            logger.log(Level.WARNING, "ingredient-parser sidecar call failed for: $rawText", e)
            emptyResult(rawText)
        }

    private fun callSidecar(rawText: String): ParsedIngredientLine {
        val requestBody = buildJsonObject { put("text", rawText) }.toString()
        val request = HttpRequest.newBuilder()
            .uri(parseUri)
            .timeout(Duration.ofSeconds(5))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(requestBody))
            .build()

        val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            logger.warning("ingredient-parser sidecar returned ${response.statusCode()} for: $rawText")
            return emptyResult(rawText)
        }

        return parseSidecarResponse(response.body(), rawText)
    }

    private fun emptyResult(rawText: String) = ParsedIngredientLine(null, null, null, null, null, rawText)
}

// kotlinx.serialization's own .jsonObject/.jsonArray extension properties throw when the
// element is JsonNull -- a JSON `null` is a real, distinct JsonElement, not Kotlin null, and
// null is exactly what preparation/comment/purpose/size are on most real ingredient lines. Safe
// casts here instead, so a present-but-null field degrades to Kotlin null like any other
// missing field, rather than throwing. Nullable receiver so callers can chain straight off a
// map lookup without an extra `?.` for the "key absent" case.
private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject
private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

// Pure (no I/O) -- kept as a plain top-level function specifically so it's directly
// unit-testable against canned response bodies, same pattern as shelf's
// parseMigrationFilename()/discoverMigrations(). See backend/test/SidecarIngredientLineParserTest.kt.
fun parseSidecarResponse(body: String, rawText: String): ParsedIngredientLine {
    val root = Json.parseToJsonElement(body).jsonObject

    val ingredientName = root["name"].asArrayOrNull()
        ?.firstOrNull().asObjectOrNull()?.get("text")?.jsonPrimitive?.contentOrNull

    val amounts = root["amount"].asArrayOrNull() ?: JsonArray(emptyList())
    val (numerator, denominator, unitWord) = extractPrimaryAmount(amounts)

    val notes = listOfNotNull(
        root["preparation"].asObjectOrNull()?.get("text")?.jsonPrimitive?.contentOrNull,
        root["comment"].asObjectOrNull()?.get("text")?.jsonPrimitive?.contentOrNull,
        root["purpose"].asObjectOrNull()?.get("text")?.jsonPrimitive?.contentOrNull,
    ).takeIf { it.isNotEmpty() }?.joinToString("; ")

    return ParsedIngredientLine(
        quantityNumerator = numerator,
        quantityDenominator = denominator,
        unitWord = unitWord?.takeIf { it.isNotBlank() },
        ingredientName = ingredientName,
        notes = notes,
        rawText = rawText,
    )
}

// The sidecar's `amount` list can hold more than one entry for a single line -- e.g.
// "1 (14.5 oz) can diced tomatoes" returns both "1 can" and "14.5 oz" -- and its first entry
// can itself be a CompositeIngredientAmount ("1 cup plus 2 tablespoons" -> one entry wrapping
// two sub-amounts, distinguishable by having an "amounts" key instead of "quantity"/"unit"
// directly) rather than a plain amount. larder's schema stores exactly one quantity/unit per
// recipe_ingredients row, so this always takes the FIRST (lowest starting_index -- the list is
// already in that order) amount as primary and discards the rest structurally. Nothing is
// actually lost: raw_text still shows the full original line regardless. Summing composite
// amounts, or picking between a package count and its per-unit size more cleverly, would need
// the same unit-family conversion machinery Phase 8 builds for shopping-list combination --
// duplicating that here wasn't judged worth it for v1. See docs/decisions.md.
private fun extractPrimaryAmount(amounts: JsonArray): Triple<Int?, Int?, String?> {
    if (amounts.isEmpty()) return Triple(null, null, null)
    val first = amounts[0].asObjectOrNull() ?: return Triple(null, null, null)

    first["amounts"].asArrayOrNull()?.let { return extractPrimaryAmount(it) }

    // A RANGE amount ("2-3 cloves garlic") reports quantity (the low end) and quantity_max
    // (the high end) separately -- quantity_max is used here, not quantity: for a shopping
    // list, better to slightly over-buy than under-buy.
    val isRange = first["RANGE"]?.jsonPrimitive?.booleanOrNull == true
    val chosenQuantity = if (isRange) first["quantity_max"] else first["quantity"]
    val (numerator, denominator) = quantityFraction(chosenQuantity) ?: (null to null)
    val unit = first["unit"]?.jsonPrimitive?.contentOrNull

    return Triple(numerator, denominator, unit)
}

private val SPACED_RANGE = Regex("""^\s*(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s*$""")

// Normally a {numerator, denominator} object. But when the upstream library can't turn the
// quantity into a number it passes the text through as a string -- confirmed on a real
// Nextcloud Cookbook line, "1.5 - 2 cups milk", which comes back as "1.5-2" with RANGE false.
// A plain decimal string, or such a range (taking the high end, the same over-buy rule as
// real RANGE amounts), is still read; anything else stays unquantified.
private fun quantityFraction(element: JsonElement?): Pair<Int, Int>? {
    element.asObjectOrNull()?.let { obj ->
        val n = obj["numerator"]?.jsonPrimitive?.intOrNull ?: return null
        val d = obj["denominator"]?.jsonPrimitive?.intOrNull ?: return null
        return n to d
    }
    val text = (element as? JsonPrimitive)?.contentOrNull ?: return null
    val value = (SPACED_RANGE.find(text)?.groupValues?.get(2) ?: text.trim()).toBigDecimalOrNull() ?: return null
    if (value.signum() <= 0) return null
    val scaled = value.stripTrailingZeros().let { if (it.scale() < 0) it.setScale(0) else it }
    val num = scaled.unscaledValue()
    val den = java.math.BigInteger.TEN.pow(scaled.scale())
    val g = num.gcd(den)
    val n = num.divide(g)
    val d = den.divide(g)
    if (n.bitLength() >= 32 || d.bitLength() >= 32) return null
    return n.toInt() to d.toInt()
}
