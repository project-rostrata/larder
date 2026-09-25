import kotlin.test.assertEquals
import kotlin.test.assertNull
import larder.ingredients.normalizeIngredientName
import larder.ingredients.parseSidecarResponse

// Every JSON body below is a real response captured from the actual ingredient-parser/ sidecar
// (see PROJECT_BRIEF.md section 4), not synthetic -- these are the exact shapes
// parseSidecarResponse() has to handle correctly, including the two hard cases: multiple
// `amount` entries for one line, and RANGE amounts using quantity_max instead of quantity.

fun testBasicFractionQuantityAndPreparation() {
    val body = """
        {"name": [{"text": "all-purpose flour", "confidence": 0.997852, "starting_index": 2}], "size": null,
         "amount": [{"quantity": {"numerator": 5, "denominator": 2}, "quantity_max": {"numerator": 5, "denominator": 2},
                      "unit": "cups", "text": "2 1/2 cups", "confidence": 0.999969, "starting_index": 0,
                      "unit_system": "other", "APPROXIMATE": false, "SINGULAR": false, "RANGE": false,
                      "MULTIPLIER": false, "PREPARED_INGREDIENT": false}],
         "preparation": {"text": "sifted", "confidence": 0.999808, "starting_index": 5},
         "comment": null, "purpose": null, "foundation_foods": [], "sentence": "2 1/2 cups all-purpose flour, sifted"}
    """.trimIndent()

    val result = parseSidecarResponse(body, "2 1/2 cups all-purpose flour, sifted")

    assertEquals(5, result.quantityNumerator)
    assertEquals(2, result.quantityDenominator)
    assertEquals("cups", result.unitWord)
    assertEquals("all-purpose flour", result.ingredientName)
    assertEquals("sifted", result.notes)
}

fun testBareCountWithEmptyUnitStringBecomesNullUnit() {
    val body = """
        {"name": [{"text": "eggs", "confidence": 0.998843, "starting_index": 2}],
         "size": {"text": "large", "confidence": 0.997004, "starting_index": 1},
         "amount": [{"quantity": {"numerator": 2, "denominator": 1}, "quantity_max": {"numerator": 2, "denominator": 1},
                      "unit": "", "text": "2", "confidence": 0.999501, "starting_index": 0,
                      "unit_system": "none", "APPROXIMATE": false, "SINGULAR": false, "RANGE": false,
                      "MULTIPLIER": false, "PREPARED_INGREDIENT": false}],
         "preparation": null, "comment": null, "purpose": null, "foundation_foods": [], "sentence": "2 large eggs"}
    """.trimIndent()

    val result = parseSidecarResponse(body, "2 large eggs")

    assertEquals(2, result.quantityNumerator)
    assertEquals(1, result.quantityDenominator)
    assertNull(result.unitWord)
    // "large" is deliberately not part of the name -- this is the specific property that made
    // the sidecar worth building at all, see PROJECT_BRIEF.md section 4.
    assertEquals("eggs", result.ingredientName)
}

fun testFirstOfMultipleAmountsWinsOverPackageSize() {
    // "1 (14.5 oz) can diced tomatoes" -- two amounts, "1 can" and "14.5 oz". The first
    // (lowest starting_index) wins; the second is discarded structurally, not lost (raw_text
    // still has the full line).
    val body = """
        {"name": [{"text": "diced tomatoes", "confidence": 0.993941, "starting_index": 6}], "size": null,
         "amount": [
           {"quantity": {"numerator": 1, "denominator": 1}, "quantity_max": {"numerator": 1, "denominator": 1},
            "unit": "can", "text": "1 can", "confidence": 0.995592, "starting_index": 0,
            "unit_system": "other", "APPROXIMATE": false, "SINGULAR": false, "RANGE": false,
            "MULTIPLIER": false, "PREPARED_INGREDIENT": false},
           {"quantity": {"numerator": 29, "denominator": 2}, "quantity_max": {"numerator": 29, "denominator": 2},
            "unit": "oz", "text": "14.5 oz", "confidence": 0.999784, "starting_index": 2,
            "unit_system": "us_customary", "APPROXIMATE": false, "SINGULAR": true, "RANGE": false,
            "MULTIPLIER": false, "PREPARED_INGREDIENT": false}
         ],
         "preparation": null, "comment": null, "purpose": null, "foundation_foods": [],
         "sentence": "1 (14.5 oz) can diced tomatoes"}
    """.trimIndent()

    val result = parseSidecarResponse(body, "1 (14.5 oz) can diced tomatoes")

    assertEquals(1, result.quantityNumerator)
    assertEquals(1, result.quantityDenominator)
    assertEquals("can", result.unitWord)
    assertEquals("diced tomatoes", result.ingredientName)
}

fun testCompositeAmountUsesItsFirstSubAmount() {
    // "1 cup plus 2 tablespoons flour" -- one CompositeIngredientAmount wrapping two
    // sub-amounts (an "amounts" key, not "quantity"/"unit" directly). Summing composite
    // amounts would need Phase 8's unit-conversion machinery -- out of scope here, see
    // docs/decisions.md -- so this takes the first sub-amount, same rule as any other list.
    val body = """
        {"name": [{"text": "flour", "confidence": 0.999, "starting_index": 5}], "size": null,
         "amount": [{"amounts": [
             {"quantity": {"numerator": 1, "denominator": 1}, "quantity_max": {"numerator": 1, "denominator": 1},
              "unit": "cup", "text": "1 cup", "confidence": 0.99985, "starting_index": 0,
              "unit_system": "us_customary", "APPROXIMATE": false, "SINGULAR": false, "RANGE": false,
              "MULTIPLIER": false, "PREPARED_INGREDIENT": false},
             {"quantity": {"numerator": 2, "denominator": 1}, "quantity_max": {"numerator": 2, "denominator": 1},
              "unit": "tablespoons", "text": "2 tablespoons", "confidence": 0.996163, "starting_index": 3,
              "unit_system": "other", "APPROXIMATE": false, "SINGULAR": false, "RANGE": false,
              "MULTIPLIER": false, "PREPARED_INGREDIENT": false}
           ], "join": " plus ", "subtractive": false, "text": "1 cup plus 2 tablespoons",
           "confidence": 0.998007, "starting_index": 0, "unit_system": "other"}],
         "preparation": null, "comment": null, "purpose": null, "foundation_foods": [],
         "sentence": "1 cup plus 2 tablespoons flour"}
    """.trimIndent()

    val result = parseSidecarResponse(body, "1 cup plus 2 tablespoons flour")

    assertEquals(1, result.quantityNumerator)
    assertEquals(1, result.quantityDenominator)
    assertEquals("cup", result.unitWord)
}

fun testRangeAmountUsesQuantityMaxNotQuantity() {
    // "2-3 cloves garlic, minced" -- RANGE=true, quantity=2, quantity_max=3. quantity_max
    // wins: for a shopping list, better to slightly over-buy than under-buy.
    val body = """
        {"name": [{"text": "garlic", "confidence": 0.999, "starting_index": 2}], "size": null,
         "amount": [{"quantity": {"numerator": 2, "denominator": 1}, "quantity_max": {"numerator": 3, "denominator": 1},
                      "unit": "clove", "text": "2-3 clove", "confidence": 0.999853, "starting_index": 0,
                      "unit_system": "other", "APPROXIMATE": false, "SINGULAR": false, "RANGE": true,
                      "MULTIPLIER": false, "PREPARED_INGREDIENT": false}],
         "preparation": {"text": "minced", "confidence": 0.999234, "starting_index": 4},
         "comment": null, "purpose": null, "foundation_foods": [], "sentence": "2-3 cloves garlic, minced"}
    """.trimIndent()

    val result = parseSidecarResponse(body, "2-3 cloves garlic, minced")

    assertEquals(3, result.quantityNumerator)
    assertEquals(1, result.quantityDenominator)
}

fun testNoAmountFallsBackToNullQuantityAndUnit() {
    val body = """
        {"name": [{"text": "Salt", "confidence": 0.999, "starting_index": 0}], "size": null,
         "amount": [], "preparation": null,
         "comment": {"text": "to taste", "confidence": 0.997298, "starting_index": 2},
         "purpose": null, "foundation_foods": [], "sentence": "Salt, to taste"}
    """.trimIndent()

    val result = parseSidecarResponse(body, "Salt, to taste")

    assertNull(result.quantityNumerator)
    assertNull(result.quantityDenominator)
    assertNull(result.unitWord)
    assertEquals("Salt", result.ingredientName)
    assertEquals("to taste", result.notes)
}

fun testPreparationCommentAndPurposeAreJoinedIntoOneNotesField() {
    val body = """
        {"name": [{"text": "chicken", "confidence": 0.999, "starting_index": 0}], "size": null, "amount": [],
         "preparation": {"text": "diced", "confidence": 0.99, "starting_index": 1},
         "comment": {"text": "skin removed", "confidence": 0.98, "starting_index": 2},
         "purpose": {"text": "for the filling", "confidence": 0.97, "starting_index": 3},
         "foundation_foods": [], "sentence": "chicken, diced, skin removed, for the filling"}
    """.trimIndent()

    val result = parseSidecarResponse(body, "chicken, diced, skin removed, for the filling")

    assertEquals("diced; skin removed; for the filling", result.notes)
}

fun testRawTextIsAlwaysPreservedRegardlessOfParseQuality() {
    val body = """{"name": [], "size": null, "amount": [], "preparation": null, "comment": null,
                    "purpose": null, "foundation_foods": [], "sentence": ""}"""

    val result = parseSidecarResponse(body, "a completely unparseable line")

    assertEquals("a completely unparseable line", result.rawText)
    assertNull(result.ingredientName)
}

// Real response for a line from the human's own Nextcloud Cookbook export: the upstream library
// didn't recognize the spaced range and returned quantity as the string "1.5-2" (RANGE false).
fun testSpacedRangeReturnedAsStringUsesHighEnd() {
    val body = """
        {"name": [{"text": "milk.", "confidence": 0.99405, "starting_index": 2}], "size": null, "amount": [{"quantity": "1.5-2", "quantity_max": "1.5-2", "unit": "cups", "text": "1.5-2 cups", "confidence": 0.999912, "starting_index": 0, "unit_system": "other", "APPROXIMATE": false, "SINGULAR": false, "RANGE": false, "MULTIPLIER": false, "PREPARED_INGREDIENT": false}], "preparation": null, "comment": {"text": "1.5 is kinda cakey", "confidence": 0.606893, "starting_index": 4}, "purpose": null, "foundation_foods": [], "sentence": "1.5 - 2 cups milk. 1.5 is kinda cakey"}
    """.trimIndent()
    val result = parseSidecarResponse(body, "1.5 - 2 cups milk. 1.5 is kinda cakey")
    assertEquals(2, result.quantityNumerator)
    assertEquals(1, result.quantityDenominator)
    assertEquals("cups", result.unitWord)
}

fun testIngredientNameNormalization() {
    assertEquals("milk", normalizeIngredientName("milk."))
    assertEquals("milk", normalizeIngredientName("  milk,  "))
    assertEquals("ricotta cheese", normalizeIngredientName("ricotta   cheese"))
    assertEquals("half-and-half", normalizeIngredientName("\"half-and-half\""))
    assertEquals("salt & pepper", normalizeIngredientName("(salt & pepper)"))
    assertNull(normalizeIngredientName(" .. "))
}
