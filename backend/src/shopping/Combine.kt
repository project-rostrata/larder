package larder.shopping

import java.util.UUID

data class UnitInfo(val id: UUID, val name: String, val dimension: String, val toBase: Rational?)

// "1 fromUnit = factor toUnit", optionally scoped to one ingredient. Applied in either direction.
data class ConversionRule(val fromUnit: UUID, val toUnit: UUID, val factor: Rational, val ingredientId: UUID?)

// One contributing ingredient line, already scaled by its meal-plan multiplier.
data class SourceLine(
    val recipeId: UUID?,
    val recipeTitle: String,
    val mealPlanEntryId: UUID?,
    val rawText: String,
    val ingredientId: UUID?,
    val ingredientName: String?,
    val quantity: Rational?,
    val unitId: UUID?,
)

data class CombinedItem(
    val ingredientId: UUID?,
    val name: String,
    val quantity: Rational?,
    val unitId: UUID?,
    val sources: List<SourceLine>,
)

// One amount within a group: total quantity in unitId (null = bare count, e.g. "3 eggs").
data class Amount(val quantity: Rational, val unitId: UUID?)

private sealed class Space {
    data class Dimension(val dimension: String) : Space() // total held in base units (ml / g)
    data class Unit(val unitId: UUID?) : Space()          // total held in this unit (count / bare)
}

private class Bucket(val space: Space, var total: Rational, val sources: MutableList<SourceLine>) {
    val usedUnits = mutableSetOf<UUID>()
}

class Combiner(private val units: Map<UUID, UnitInfo>, private val conversions: List<ConversionRule>) {

    // Groups by ingredient_id (resolved lines) or normalized raw text (unresolved lines), then
    // combines each group's quantities where a unit path exists. Unresolved lines are never
    // quantity-combined: without a parsed ingredient their raw text carries the quantity itself.
    fun combine(lines: List<SourceLine>): List<CombinedItem> {
        val groups = linkedMapOf<String, MutableList<SourceLine>>()
        for (line in lines) {
            val key = line.ingredientId?.let { "i:$it" } ?: "t:${normalize(line.rawText)}"
            groups.getOrPut(key) { mutableListOf() }.add(line)
        }
        return groups.values.flatMap { group ->
            val first = group.first()
            val name = if (first.ingredientId != null) first.ingredientName ?: first.rawText else first.rawText
            val usable = if (first.ingredientId != null) group else group.map { it.copy(quantity = null) }
            buckets(usable, first.ingredientId).map { (amount, sources) ->
                CombinedItem(first.ingredientId, name, amount?.quantity, amount?.unitId, sources)
            }
        }.sortedBy { it.name.lowercase() }
    }

    // Every line treated as one group (a manual merge). Returns one Amount per incompatible unit
    // path -- a single element when everything combined -- plus all sources.
    fun amounts(lines: List<SourceLine>, ingredientId: UUID?): List<Amount> =
        buckets(lines, ingredientId).mapNotNull { it.first }

    private fun buckets(lines: List<SourceLine>, ingredientId: UUID?): List<Pair<Amount?, List<SourceLine>>> {
        val buckets = mutableListOf<Bucket>()
        val unquantified = mutableListOf<SourceLine>()
        for (line in lines) {
            val q = line.quantity ?: run { unquantified += line; null } ?: continue
            val space = spaceOf(line.unitId)
            val amount = if (space is Space.Dimension) q * units.getValue(line.unitId!!).toBase!! else q
            val bucket = buckets.firstOrNull { it.space == space }
                ?: Bucket(space, Rational.ZERO, mutableListOf()).also { buckets += it }
            bucket.total += amount
            bucket.sources += line
            line.unitId?.let { bucket.usedUnits += it }
        }
        mergeViaConversions(buckets, ingredientId)
        if (buckets.isEmpty()) return if (unquantified.isEmpty()) emptyList() else listOf(null to unquantified)
        buckets.first().sources += unquantified
        return buckets.map { amountOf(it) to it.sources.toList() }
    }

    private fun spaceOf(unitId: UUID?): Space {
        val unit = unitId?.let { units[it] }
        return if (unit?.toBase != null) Space.Dimension(unit.dimension) else Space.Unit(unitId)
    }

    private fun belongs(unitId: UUID, bucket: Bucket): Boolean = when (val s = bucket.space) {
        is Space.Dimension -> units[unitId]?.let { it.dimension == s.dimension && it.toBase != null } ?: false
        is Space.Unit -> s.unitId == unitId
    }

    // Folds buckets together through a unit_conversions row -- ingredient-scoped rows first, then
    // global ones. One row per merge; no multi-hop chaining through several rows.
    private fun mergeViaConversions(buckets: MutableList<Bucket>, ingredientId: UUID?) {
        val rules = conversions.filter { it.ingredientId != null && it.ingredientId == ingredientId } +
            conversions.filter { it.ingredientId == null }
        var merged = true
        while (merged) {
            merged = false
            loop@ for (i in buckets.indices) for (j in buckets.indices) {
                if (i == j) continue
                for (rule in rules) {
                    val converted = when {
                        belongs(rule.fromUnit, buckets[j]) && belongs(rule.toUnit, buckets[i]) ->
                            inUnit(buckets[j], rule.fromUnit) * rule.factor to rule.toUnit
                        belongs(rule.toUnit, buckets[j]) && belongs(rule.fromUnit, buckets[i]) ->
                            inUnit(buckets[j], rule.toUnit) / rule.factor to rule.fromUnit
                        else -> null
                    } ?: continue
                    val (qty, unitId) = converted
                    val target = buckets[i]
                    target.total += if (target.space is Space.Dimension) qty * units.getValue(unitId).toBase!! else qty
                    target.sources += buckets[j].sources
                    target.usedUnits += unitId
                    buckets.removeAt(j)
                    merged = true
                    break@loop
                }
            }
        }
    }

    private fun inUnit(bucket: Bucket, unitId: UUID): Rational =
        if (bucket.space is Space.Dimension) bucket.total / units.getValue(unitId).toBase!! else bucket.total

    // Dimension totals display in the largest unit the recipes actually used where the total is
    // at least 1 (4 cups + 3 tbsp -> cups), falling back to the smallest used unit.
    private fun amountOf(bucket: Bucket): Amount = when (val s = bucket.space) {
        is Space.Unit -> Amount(bucket.total.niceRound(), s.unitId)
        is Space.Dimension -> {
            val used = bucket.usedUnits.mapNotNull { units[it] }.filter { it.toBase != null }.sortedByDescending { it.toBase }
            val unit = used.firstOrNull { bucket.total / it.toBase!! >= Rational.ONE } ?: used.last()
            Amount((bucket.total / unit.toBase!!).niceRound(), unit.id)
        }
    }
}

fun normalize(text: String): String = text.trim().lowercase().replace(Regex("\\s+"), " ")
