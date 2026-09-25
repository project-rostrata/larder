import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import larder.recipeimport.extractRecipeFromHtml
import larder.recipeimport.extractRecipeFromJson

// FOOD_COM_FIXTURE is real JSON-LD, fetched live from a real food.com recipe page during this
// phase's design (trimmed to a representative subset of ingredients/instructions) -- not
// synthetic. It confirms the real-world shape this parser has to handle: a top-level Recipe
// object (no @graph wrapper), HowToStep instruction objects, a recipeYield string that isn't a
// clean number ("1 pound per serving"), and ingredient strings with stray leading/trailing
// whitespace. A pile of unrelated real-world fields (image, nutrition, review, publisher, ...)
// are deliberately left in, to prove the extractor ignores what it doesn't need rather than
// tripping over it.
private val FOOD_COM_FIXTURE = """
    <html><head><script type="application/ld+json">
    {
      "@context": "http://schema.org",
      "@type": "Recipe",
      "mainEntityOfPage": "true",
      "name": "Salmon on the Grill",
      "author": "Xtal220",
      "cookTime": "PT25M",
      "prepTime": "PT5M",
      "totalTime": "PT30M",
      "datePublished": "2002-03-01T10:26Z",
      "description": "Make and share this Salmon on the Grill recipe from Food.com.",
      "image": "https://img.sndimg.com/food/image/upload/q_92/v1/img/recipes/21/06/1/piciKZlSi.jpg",
      "recipeCategory": "Very Low Carbs",
      "keywords": "Low Protein,< 30 Mins,Easy",
      "recipeIngredient": [
        " fresh atlantic salmon (1 lb per person)",
        "2   teaspoons    unsalted butter",
        "  dried parsley (or use fresh)"
      ],
      "aggregateRating": {"@type": "AggregateRating", "ratingValue": "5.0", "reviewCount": "1"},
      "nutrition": {"@type": "NutritionInformation", "calories": "67.9"},
      "recipeInstructions": [
        {"@type": "HowToStep", "text": "Take a piece of tin foil, 2 times the size of the fish, and flatten it out on the table."},
        {"@type": "HowToStep", "text": "Put the Salmon on it, skin side down."},
        {"@type": "HowToStep", "text": "Dot with butter."}
      ],
      "recipeYield": "1 pound per serving",
      "review": [{"@type": "Review", "description": "This was superb!"}],
      "publisher": {"@type": "Organization", "name": "Food.com"}
    }
    </script></head><body></body></html>
""".trimIndent()

fun testRealFoodComFixtureExtractsCorrectly() {
    val result = extractRecipeFromHtml(FOOD_COM_FIXTURE, "https://www.food.com/recipe/example")

    assertTrue(result != null)
    assertEquals("Salmon on the Grill", result.title)
    assertEquals(5, result.prepTimeMinutes)
    assertEquals(25, result.cookTimeMinutes)
    assertEquals(30, result.totalTimeMinutes)
    // recipeCategory ("Very Low Carbs") first, then keywords.
    assertEquals(listOf("Very Low Carbs", "Low Protein", "< 30 Mins", "Easy"), result.tags)
    assertEquals(3, result.instructions.size)
    assertEquals("Dot with butter.", result.instructions[2])
    // Stray leading/trailing whitespace trimmed, real ingredient text preserved otherwise.
    assertEquals("fresh atlantic salmon (1 lb per person)", result.ingredientRawTexts[0])
    assertEquals("2   teaspoons    unsalted butter", result.ingredientRawTexts[1])
}

fun testMessyRecipeYieldStillPreservesFullTextEvenWhenNumberExtractionIsImprecise() {
    // "1 pound per serving" isn't really "1 serving" -- the extracted number is a best-effort
    // heuristic (see JsonLdRecipeParser.extractYield), not guaranteed correct. What must never
    // be lost is the original text, exactly like raw_text everywhere else in this app.
    val result = extractRecipeFromHtml(FOOD_COM_FIXTURE, "https://www.food.com/recipe/example")

    assertTrue(result != null)
    assertEquals("1 pound per serving", result.servingsText)
    assertEquals(java.math.BigDecimal(1), result.servings)
}

fun testGraphWrappedRecipeIsFound() {
    // Representative of the @graph pattern real sites also use (not this specific fixture's
    // source, but a well-documented, standard schema.org embedding shape) -- a Recipe object
    // alongside unrelated types in one @graph array.
    val html = """
        <script type="application/ld+json">
        {"@context": "https://schema.org", "@graph": [
          {"@type": "WebPage", "name": "Some Page"},
          {"@type": "Recipe", "name": "Graph Wrapped Recipe", "recipeIngredient": ["1 egg"], "recipeInstructions": ["Crack it."]}
        ]}
        </script>
    """.trimIndent()

    val result = extractRecipeFromHtml(html, "https://example.com/recipe")

    assertTrue(result != null)
    assertEquals("Graph Wrapped Recipe", result.title)
    assertEquals(listOf("1 egg"), result.ingredientRawTexts)
}

fun testTypeAsArrayIsRecognized() {
    // "@type": ["Recipe", "NewsArticle"] occurs on real sites that tag content with multiple
    // schema.org types simultaneously.
    val html = """
        <script type="application/ld+json">
        {"@type": ["Recipe", "NewsArticle"], "name": "Multi-typed Recipe", "recipeIngredient": ["1 cup rice"]}
        </script>
    """.trimIndent()

    val result = extractRecipeFromHtml(html, "https://example.com/recipe")

    assertTrue(result != null)
    assertEquals("Multi-typed Recipe", result.title)
}

fun testHowToSectionNestedStepsAreFlattened() {
    // Some sites group instructions into HowToSection objects instead of a flat step array.
    val html = """
        <script type="application/ld+json">
        {"@type": "Recipe", "name": "Sectioned Recipe", "recipeInstructions": [
          {"@type": "HowToSection", "name": "For the sauce", "itemListElement": [
            {"@type": "HowToStep", "text": "Mix the sauce."}
          ]},
          {"@type": "HowToStep", "text": "Combine everything."}
        ]}
        </script>
    """.trimIndent()

    val result = extractRecipeFromHtml(html, "https://example.com/recipe")

    assertTrue(result != null)
    assertEquals(listOf("Mix the sauce.", "Combine everything."), result.instructions)
}

fun testNoJsonLdOnPageReturnsNull() {
    val result = extractRecipeFromHtml("<html><body>no structured data here</body></html>", "https://example.com")

    assertNull(result)
}

fun testJsonLdPresentButNoRecipeTypeReturnsNull() {
    val html = """<script type="application/ld+json">{"@type": "Article", "name": "Not a recipe"}</script>"""

    assertNull(extractRecipeFromHtml(html, "https://example.com"))
}

fun testMalformedJsonInsideScriptTagIsSkippedGracefully() {
    // A page with one broken JSON-LD block and one good one -- never throw, just move on to
    // the next script block.
    val html = """
        <script type="application/ld+json">{not valid json at all</script>
        <script type="application/ld+json">{"@type": "Recipe", "name": "The Good One"}</script>
    """.trimIndent()

    val result = extractRecipeFromHtml(html, "https://example.com")

    assertTrue(result != null)
    assertEquals("The Good One", result.title)
}

// Trimmed from a REAL Nextcloud Cookbook recipe.json the human exported from their own instance
// (Lemon Blueberry Ricotta Pancakes): same shape and quirks -- numeric recipeYield, null times,
// empty keywords, tips kept in "tool", unicode fraction characters, a relative image path --
// with fewer ingredients/steps/tips.
private val NEXTCLOUD_RECIPE_JSON = """
{"id":"186465","name":"Lemon Blueberry Ricotta Pancakes","description":"","url":"https:\/\/www.jocooks.com\/wprm_print\/13753","image":"\/Recipes\/images\/lemon-blueberry-ricotta-pancakes.jpg","prepTime":null,"cookTime":null,"totalTime":null,"recipeCategory":"Breakfast","keywords":"","recipeYield":6,"tool":["You can use any berries you like for this recipe. Blackberries, raspberries, strawberries, etc, or any combination.","Don't overmix the batter. It's okay if it's a little lumpy."],"recipeIngredient":["2 cups all-purpose flour","½ teaspoon baking soda","⅛ teaspoon salt","1.5 - 2 cups milk. 1.5 is kinda cakey","maple syrup and butter for serving"],"recipeInstructions":["In a large bowl whisk together the flour, baking soda and a pinch of salt. Set aside.","Serve hot with maple syrup and butter."],"nutrition":{"@type":"NutritionInformation"},"@context":"http:\/\/schema.org","@type":"Recipe","dateModified":"2024-03-08T16:18:42+0000","dateCreated":"2023-07-25T00:52:53+0000"}
"""

fun testNextcloudRecipeJsonMapsAllFields() {
    val r = extractRecipeFromJson(NEXTCLOUD_RECIPE_JSON)!!
    assertEquals("Lemon Blueberry Ricotta Pancakes", r.title)
    assertEquals("https://www.jocooks.com/wprm_print/13753", r.sourceUrl)
    assertEquals(java.math.BigDecimal("6"), r.servings)
    assertEquals(listOf("Breakfast"), r.tags)
    assertEquals(null, r.prepTimeMinutes)
    assertEquals(5, r.ingredientRawTexts.size)
    assertEquals("½ teaspoon baking soda", r.ingredientRawTexts[1])
    assertEquals(2, r.instructions.size)
    // Empty description dropped; each full-sentence tip is its own paragraph.
    val paragraphs = r.notes!!.split("\n\n")
    assertEquals(2, paragraphs.size)
    assertTrue(paragraphs[0].startsWith("You can use any berries"))
}

fun testShortToolsBecomeOneToolsLine() {
    val json = """{"@type":"Recipe","name":"Soup","description":"Warming.","tool":["large pot","ladle"],"recipeIngredient":["1 onion"]}"""
    assertEquals("Warming.\n\nTools: large pot, ladle", extractRecipeFromJson(json)!!.notes)
}

fun testRecipeJsonWithoutTypeStillRecognized() {
    val json = """{"name":"Toast","recipeIngredient":["2 slices bread"],"url":"/relative/path"}"""
    val r = extractRecipeFromJson(json)!!
    assertEquals("Toast", r.title)
    assertEquals(null, r.sourceUrl) // not an absolute http(s) URL
}

fun testNonRecipeJsonIsRejected() {
    assertEquals(null, extractRecipeFromJson("not json at all"))
    assertEquals(null, extractRecipeFromJson("""{"hello":"world"}"""))
    assertEquals(null, extractRecipeFromJson("""[1, 2, 3]"""))
}
