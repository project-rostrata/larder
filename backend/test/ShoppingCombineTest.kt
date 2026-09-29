import kotlin.test.assertEquals
import larder.shopping.Combiner
import larder.shopping.ConversionRule
import larder.shopping.Rational
import larder.shopping.SourceLine
import larder.shopping.UnitInfo
import larder.shopping.formatAmount
import larder.shopping.formatQuantity
import java.math.BigDecimal
import java.util.UUID

// Unit factors copied from db/migrations/0002_seed_units.sql, so conversions here behave the
// same as against the real seeded table.
private fun unit(name: String, dimension: String, toBase: String?) =
    UnitInfo(UUID.nameUUIDFromBytes(name.toByteArray()), name, dimension, toBase?.let { Rational.of(BigDecimal(it)) })

private val tsp = unit("teaspoon", "volume", "4.92892")
private val tbsp = unit("tablespoon", "volume", "14.7868")
private val cup = unit("cup", "volume", "236.588")
private val gram = unit("gram", "mass", "1")
private val clove = unit("clove", "count", null)
private val units = listOf(tsp, tbsp, cup, gram, clove).associateBy { it.id }

private val flour = UUID.randomUUID()
private val garlic = UUID.randomUUID()
private val egg = UUID.randomUUID()
private val salt = UUID.randomUUID()

private fun line(title: String, ingredient: UUID?, name: String?, qty: Rational?, unit: UnitInfo?, raw: String = "raw") =
    SourceLine(null, title, null, raw, ingredient, name, qty, unit?.id)

private fun r(n: Long, d: Long = 1) = Rational.of(n, d)

fun testSameUnitSumsStayExact() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", flour, "flour", r(1, 3), cup), line("B", flour, "flour", r(1, 3), cup),
    ))
    assertEquals(1, items.size)
    assertEquals(r(2, 3), items[0].quantity)
    assertEquals(cup.id, items[0].unitId)
    assertEquals(2, items[0].sources.size)
}

fun testMixedVolumeConvertsToLargestUnitAtLeastOne() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", flour, "flour", r(4), cup), line("B", flour, "flour", r(2), tbsp),
    ))
    assertEquals(1, items.size)
    assertEquals(cup.id, items[0].unitId)
    assertEquals(r(33, 8), items[0].quantity) // 4 cups + 2 tbsp = 4 1/8 cups
}

fun testSmallMixedTotalFallsBackToSmallerUnit() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", salt, "salt", r(1, 2), tsp), line("B", salt, "salt", r(1, 4), tbsp),
    ))
    assertEquals(tsp.id, items[0].unitId)
    assertEquals(r(5, 4), items[0].quantity) // 0.42 tbsp is < 1, so teaspoons: 1 1/4
}

fun testVolumeAndMassDoNotCombineWithoutARule() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", flour, "flour", r(2), cup), line("B", flour, "flour", r(100), gram),
    ))
    assertEquals(2, items.size)
}

fun testIngredientScopedConversionMergesCountIntoMass() {
    val rule = ConversionRule(clove.id, gram.id, r(3), garlic)
    val items = Combiner(units, listOf(rule)).combine(listOf(
        line("A", garlic, "garlic", r(3), clove), line("B", garlic, "garlic", r(10), gram),
    ))
    assertEquals(1, items.size)
    assertEquals(clove.id, items[0].unitId)
    assertEquals(r(19, 3), items[0].quantity) // 3 cloves + 10 g / 3 g-per-clove
    assertEquals(2, items[0].sources.size)
}

fun testScopedConversionDoesNotApplyToOtherIngredients() {
    val rule = ConversionRule(clove.id, gram.id, r(3), UUID.randomUUID())
    val items = Combiner(units, listOf(rule)).combine(listOf(
        line("A", garlic, "garlic", r(3), clove), line("B", garlic, "garlic", r(10), gram),
    ))
    assertEquals(2, items.size)
}

fun testBareCountsCombine() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", egg, "egg", r(2), null), line("B", egg, "egg", r(3), null),
    ))
    assertEquals(r(5), items.single().quantity)
    assertEquals(null, items.single().unitId)
}

fun testUnresolvedLinesCombineOnlyOnIdenticalTextAndKeepNoQuantity() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", null, null, null, null, raw = "2 Cups  Mystery Mix"),
        line("B", null, null, null, null, raw = "2 cups mystery mix"),
        line("C", null, null, null, null, raw = "a pinch of something"),
    ))
    assertEquals(2, items.size)
    val mix = items.first { it.name.startsWith("2") }
    assertEquals(2, mix.sources.size)
    assertEquals(null, mix.quantity)
}

fun testQuantityLessLineAttachesToQuantifiedItem() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", salt, "salt", null, null, raw = "salt to taste"), line("B", salt, "salt", r(1), tsp),
    ))
    assertEquals(1, items.size)
    assertEquals(r(1), items[0].quantity)
    assertEquals(2, items[0].sources.size)
}

// "2 eggs" + "egg": the bare noun means one egg.
fun testQuantityLessLineCountsAsOneNextToBareCount() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", egg, "egg", null, null, raw = "egg"), line("B", egg, "egg", r(2), null),
    ))
    assertEquals(1, items.size)
    assertEquals(r(3), items[0].quantity)
    assertEquals(null, items[0].unitId)
    assertEquals(2, items[0].sources.size)
}

// A manual merge of "eggs - 2" with a hand-added "egg" (no ingredient, no amount) -- the
// reported bug, which gave 2.
fun testManualMergeCountsAmountlessItemAsOne() {
    val amounts = Combiner(units, emptyList()).amounts(listOf(
        line("Added manually", null, null, null, null, raw = "egg"), line("B", egg, "eggs", r(2), null),
    ), null)
    assertEquals(1, amounts.size)
    assertEquals(r(3), amounts[0].quantity)
    assertEquals(null, amounts[0].unitId)
}

// Only a bare count takes the one: "3 cloves garlic" + "garlic" stays 3 cloves.
fun testQuantityLessLineNotCountedNextToCountUnit() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", garlic, "garlic", r(3), clove), line("B", garlic, "garlic", null, null),
    ))
    assertEquals(1, items.size)
    assertEquals(r(3), items[0].quantity)
    assertEquals(clove.id, items[0].unitId)
    assertEquals(2, items[0].sources.size)
}

fun testOnlyQuantityLessLinesGiveOneUnquantifiedItem() {
    val items = Combiner(units, emptyList()).combine(listOf(
        line("A", salt, "salt", null, null), line("B", salt, "salt", null, null),
    ))
    assertEquals(1, items.size)
    assertEquals(null, items[0].quantity)
}

fun testAmountsReportsEachIncompatiblePart() {
    val amounts = Combiner(units, emptyList()).amounts(listOf(
        line("A", garlic, "garlic", r(2), cup), line("B", garlic, "garlic", r(3), clove),
    ), garlic)
    assertEquals(2, amounts.size)
}

fun testApproximateRecoversSimpleFractionFromDouble() {
    assertEquals(r(5, 3), Rational.approximate(BigDecimal("1.6666666666666667")))
    assertEquals(r(3, 2), Rational.approximate(BigDecimal("1.5")))
}

fun testNiceRoundKeepsKitchenFractions() {
    assertEquals(r(1, 3), r(1, 3).niceRound())
    assertEquals(r(3, 8), r(3, 8).niceRound())
    assertEquals(r(1, 8), r(1, 100).niceRound())
}

fun testFormatting() {
    assertEquals("4 1/8", formatQuantity(r(33, 8)))
    assertEquals("3/8", formatQuantity(r(3, 8)))
    assertEquals("2", formatQuantity(r(2)))
    assertEquals("2 cups", formatAmount(r(2), "cup"))
    assertEquals("1 cup", formatAmount(r(1), "cup"))
    assertEquals("1/2 cup", formatAmount(r(1, 2), "cup"))
    assertEquals("3 pinches", formatAmount(r(3), "pinch"))
    assertEquals("5", formatAmount(r(5), null))
}
