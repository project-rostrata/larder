package larder.db

import larder.shopping.CombinedItem
import larder.shopping.ConversionRule
import larder.shopping.Rational
import larder.shopping.SourceLine
import larder.shopping.UnitInfo
import java.math.BigInteger
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class ShoppingListSummaryRow(
    val id: UUID,
    val name: String,
    val createdAt: Instant,
    val itemCount: Int,
    val checkedCount: Int,
)

data class ShoppingListItemRow(
    val id: UUID,
    val ingredientId: UUID?,
    val name: String,
    val quantity: Rational?,
    val unitId: UUID?,
    val checked: Boolean,
    val sortOrder: Int,
)

data class ShoppingListSourceRow(
    val id: UUID,
    val itemId: UUID,
    val recipeId: UUID?,
    val recipeTitle: String,
    val mealPlanEntryId: UUID?,
    val rawText: String,
    val quantity: Rational?,
    val unitId: UUID?,
)

data class ShoppingListDetail(
    val summary: ShoppingListSummaryRow,
    val items: List<ShoppingListItemRow>,
    val sources: List<ShoppingListSourceRow>,
)

// A recipe to gather ingredients from, with its scaling and optional meal-plan origin.
data class RecipeSelection(val recipeId: UUID, val multiplier: Rational, val mealPlanEntryId: UUID?)

const val MANUAL_SOURCE_TITLE = "Added manually"

class ShoppingListRepository(private val database: Database) {

    fun units(): Map<UUID, UnitInfo> =
        database.queryList("SELECT id, name, dimension, to_base_factor FROM units", mapRow = { rs ->
            UnitInfo(
                id = rs.getObject("id", UUID::class.java),
                name = rs.getString("name"),
                dimension = rs.getString("dimension"),
                toBase = rs.getBigDecimal("to_base_factor")?.let { Rational.of(it) },
            )
        }).associateBy { it.id }

    fun conversions(): List<ConversionRule> =
        database.queryList("SELECT from_unit_id, to_unit_id, factor, ingredient_id FROM unit_conversions", mapRow = { rs ->
            ConversionRule(
                fromUnit = rs.getObject("from_unit_id", UUID::class.java),
                toUnit = rs.getObject("to_unit_id", UUID::class.java),
                factor = Rational.of(rs.getBigDecimal("factor")),
                ingredientId = rs.getObject("ingredient_id", UUID::class.java),
            )
        })

    // Every ingredient line of the selected (owned, non-deleted) recipes, each quantity scaled
    // by its selection's multiplier. A recipe selected twice contributes twice.
    fun gatherLines(ownerId: UUID, selections: List<RecipeSelection>): List<SourceLine> {
        if (selections.isEmpty()) return emptyList()
        val ids = selections.map { it.recipeId }.distinct()
        data class Raw(val recipeId: UUID, val title: String, val rawText: String, val ingredientId: UUID?,
                       val ingredientName: String?, val quantity: Rational?, val unitId: UUID?)
        val rows = database.queryList(
            """
            SELECT r.id AS recipe_id, r.title, ri.raw_text, ri.ingredient_id, i.name AS ingredient_name,
                   ri.quantity_numerator, ri.quantity_denominator, ri.unit_id
            FROM recipes r
            JOIN recipe_ingredients ri ON ri.recipe_id = r.id
            LEFT JOIN ingredients i ON i.id = ri.ingredient_id
            WHERE r.owner_id = ? AND r.deleted_at IS NULL AND r.id = ANY(?)
            ORDER BY r.id, ri.position
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setArray(2, stmt.connection.createArrayOf("uuid", ids.toTypedArray()))
            },
            mapRow = { rs ->
                Raw(
                    recipeId = rs.getObject("recipe_id", UUID::class.java),
                    title = rs.getString("title"),
                    rawText = rs.getString("raw_text"),
                    ingredientId = rs.getObject("ingredient_id", UUID::class.java),
                    ingredientName = rs.getString("ingredient_name"),
                    quantity = rational(rs, "quantity_numerator", "quantity_denominator"),
                    unitId = rs.getObject("unit_id", UUID::class.java),
                )
            },
        ).groupBy { it.recipeId }
        return selections.flatMap { sel ->
            rows[sel.recipeId].orEmpty().map { raw ->
                SourceLine(
                    recipeId = raw.recipeId,
                    recipeTitle = raw.title,
                    mealPlanEntryId = sel.mealPlanEntryId,
                    rawText = raw.rawText,
                    ingredientId = raw.ingredientId,
                    ingredientName = raw.ingredientName,
                    quantity = raw.quantity?.let { storable(it * sel.multiplier) },
                    unitId = raw.unitId,
                )
            }
        }
    }

    fun create(ownerId: UUID, name: String, items: List<CombinedItem>): UUID = database.transaction { tx ->
        val listId = UUID.randomUUID()
        tx.update(
            "INSERT INTO shopping_lists (id, owner_id, name) VALUES (?, ?, ?)",
            bind = { stmt -> stmt.setObject(1, listId); stmt.setObject(2, ownerId); stmt.setString(3, name) },
        )
        items.forEachIndexed { index, item ->
            val itemId = insertItem(tx, listId, item.ingredientId, item.name, item.quantity?.let(::storable), item.unitId, index)
            item.sources.forEach { insertSource(tx, itemId, it) }
        }
        listId
    }

    fun listSummaries(ownerId: UUID): List<ShoppingListSummaryRow> =
        database.queryList(
            "$SUMMARY_SELECT WHERE l.owner_id = ? GROUP BY l.id ORDER BY l.created_at DESC",
            bind = { it.setObject(1, ownerId) },
            mapRow = ::toSummary,
        )

    fun find(listId: UUID, ownerId: UUID): ShoppingListDetail? {
        val summary = database.queryOneOrNull(
            "$SUMMARY_SELECT WHERE l.id = ? AND l.owner_id = ? GROUP BY l.id",
            bind = { stmt -> stmt.setObject(1, listId); stmt.setObject(2, ownerId) },
            mapRow = ::toSummary,
        ) ?: return null
        val items = database.queryList(
            "SELECT $ITEM_COLUMNS FROM shopping_list_items WHERE shopping_list_id = ? ORDER BY checked, sort_order, id",
            bind = { it.setObject(1, listId) },
            mapRow = ::toItem,
        )
        val sources = database.queryList(
            """
            SELECT $SOURCE_COLUMNS FROM shopping_list_item_sources s
            JOIN shopping_list_items it ON it.id = s.shopping_list_item_id
            WHERE it.shopping_list_id = ?
            ORDER BY s.recipe_title, s.id
            """.trimIndent(),
            bind = { it.setObject(1, listId) },
            mapRow = ::toSource,
        )
        return ShoppingListDetail(summary, items, sources)
    }

    fun delete(listId: UUID, ownerId: UUID): Boolean =
        database.update(
            "DELETE FROM shopping_lists WHERE id = ? AND owner_id = ?",
            bind = { stmt -> stmt.setObject(1, listId); stmt.setObject(2, ownerId) },
        ) > 0

    // Manual items go to the end of the unchecked section.
    fun addManualItem(listId: UUID, text: String): UUID = database.transaction { tx ->
        val next = tx.queryOne(
            "SELECT COALESCE(MAX(sort_order), -1) + 1 AS n FROM shopping_list_items WHERE shopping_list_id = ?",
            bind = { it.setObject(1, listId) },
            mapRow = { it.getInt("n") },
        )
        insertItem(tx, listId, null, text, null, null, next)
    }

    fun updateItem(listId: UUID, itemId: UUID, checked: Boolean?, text: String?): Boolean =
        database.update(
            """
            UPDATE shopping_list_items SET checked = COALESCE(?, checked), raw_text = COALESCE(?, raw_text)
            WHERE id = ? AND shopping_list_id = ?
            """.trimIndent(),
            bind = { stmt ->
                if (checked == null) stmt.setNull(1, java.sql.Types.BOOLEAN) else stmt.setBoolean(1, checked)
                stmt.setString(2, text)
                stmt.setObject(3, itemId)
                stmt.setObject(4, listId)
            },
        ) > 0

    fun deleteItem(listId: UUID, itemId: UUID): Boolean =
        database.update(
            "DELETE FROM shopping_list_items WHERE id = ? AND shopping_list_id = ?",
            bind = { stmt -> stmt.setObject(1, itemId); stmt.setObject(2, listId) },
        ) > 0

    // Folds `others` into `survivor`: their sources move over (a manual item, which has none,
    // becomes an "Added manually" source so its text isn't lost), the survivor takes the
    // recomputed quantity (null when the units couldn't all be combined), and the others are
    // deleted. The survivor stays checked only if every merged item was.
    fun merge(listId: UUID, survivor: ShoppingListItemRow, others: List<ShoppingListItemRow>,
              quantity: Rational?, unitId: UUID?, allChecked: Boolean) = database.transaction { tx ->
        val otherIds = others.map { it.id }.toTypedArray()
        for (other in others) {
            val hasSources = tx.queryOne(
                "SELECT EXISTS (SELECT 1 FROM shopping_list_item_sources WHERE shopping_list_item_id = ?) AS e",
                bind = { it.setObject(1, other.id) },
                mapRow = { it.getBoolean("e") },
            )
            if (!hasSources) {
                insertSource(tx, survivor.id, SourceLine(null, MANUAL_SOURCE_TITLE, null, other.name, null, null, other.quantity, other.unitId))
            }
        }
        tx.update(
            "UPDATE shopping_list_item_sources SET shopping_list_item_id = ? WHERE shopping_list_item_id = ANY(?)",
            bind = { stmt -> stmt.setObject(1, survivor.id); stmt.setArray(2, stmt.connection.createArrayOf("uuid", otherIds)) },
        )
        tx.update(
            """
            UPDATE shopping_list_items SET quantity_numerator = ?, quantity_denominator = ?, unit_id = ?, checked = ?
            WHERE id = ? AND shopping_list_id = ?
            """.trimIndent(),
            bind = { stmt ->
                val q = quantity?.let(::storable)
                if (q == null) { stmt.setNull(1, java.sql.Types.INTEGER); stmt.setNull(2, java.sql.Types.INTEGER) }
                else { stmt.setInt(1, q.num.toInt()); stmt.setInt(2, q.den.toInt()) }
                stmt.setObject(3, if (q == null) null else unitId)
                stmt.setBoolean(4, allChecked)
                stmt.setObject(5, survivor.id)
                stmt.setObject(6, listId)
            },
        )
        tx.update(
            "DELETE FROM shopping_list_items WHERE id = ANY(?) AND shopping_list_id = ?",
            bind = { stmt -> stmt.setArray(1, stmt.connection.createArrayOf("uuid", otherIds)); stmt.setObject(2, listId) },
        )
    }

    private fun insertItem(tx: Transaction, listId: UUID, ingredientId: UUID?, name: String,
                           quantity: Rational?, unitId: UUID?, sortOrder: Int): UUID {
        val id = UUID.randomUUID()
        tx.update(
            """
            INSERT INTO shopping_list_items (id, shopping_list_id, ingredient_id, raw_text,
                quantity_numerator, quantity_denominator, unit_id, sort_order)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, listId)
                stmt.setObject(3, ingredientId)
                stmt.setString(4, name)
                if (quantity == null) { stmt.setNull(5, java.sql.Types.INTEGER); stmt.setNull(6, java.sql.Types.INTEGER) }
                else { stmt.setInt(5, quantity.num.toInt()); stmt.setInt(6, quantity.den.toInt()) }
                stmt.setObject(7, if (quantity == null) null else unitId)
                stmt.setInt(8, sortOrder)
            },
        )
        return id
    }

    private fun insertSource(tx: Transaction, itemId: UUID, source: SourceLine) {
        val q = source.quantity?.let(::storable)
        tx.update(
            """
            INSERT INTO shopping_list_item_sources (shopping_list_item_id, recipe_id, recipe_title,
                meal_plan_entry_id, raw_text, quantity_numerator, quantity_denominator, unit_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, itemId)
                stmt.setObject(2, source.recipeId)
                stmt.setString(3, source.recipeTitle)
                stmt.setObject(4, source.mealPlanEntryId)
                stmt.setString(5, source.rawText)
                if (q == null) { stmt.setNull(6, java.sql.Types.INTEGER); stmt.setNull(7, java.sql.Types.INTEGER) }
                else { stmt.setInt(6, q.num.toInt()); stmt.setInt(7, q.den.toInt()) }
                stmt.setObject(8, if (q == null) null else source.unitId)
            },
        )
    }
}

// The quantity columns are INTEGER pairs; a scaled fraction too large for them (not expected
// in practice) is rounded to eighths, and dropped only if even that doesn't fit.
private fun storable(q: Rational): Rational? = when {
    q.fitsInt() -> q
    q.niceRound().fitsInt() -> q.niceRound()
    else -> null
}

private fun rational(rs: ResultSet, numCol: String, denCol: String): Rational? {
    val n = rs.getInt(numCol).takeUnless { rs.wasNull() } ?: return null
    val d = rs.getInt(denCol)
    return Rational.of(BigInteger.valueOf(n.toLong()), BigInteger.valueOf(d.toLong()))
}

private const val SUMMARY_SELECT = """
    SELECT l.id, l.name, l.created_at, COUNT(it.id) AS item_count,
           COUNT(it.id) FILTER (WHERE it.checked) AS checked_count
    FROM shopping_lists l
    LEFT JOIN shopping_list_items it ON it.shopping_list_id = l.id
"""
private const val ITEM_COLUMNS =
    "id, ingredient_id, raw_text, quantity_numerator, quantity_denominator, unit_id, checked, sort_order"
private const val SOURCE_COLUMNS =
    "s.id, s.shopping_list_item_id, s.recipe_id, s.recipe_title, s.meal_plan_entry_id, s.raw_text, " +
        "s.quantity_numerator, s.quantity_denominator, s.unit_id"

private fun toSummary(rs: ResultSet) = ShoppingListSummaryRow(
    id = rs.getObject("id", UUID::class.java),
    name = rs.getString("name"),
    createdAt = rs.getTimestamp("created_at").toInstant(),
    itemCount = rs.getInt("item_count"),
    checkedCount = rs.getInt("checked_count"),
)

private fun toItem(rs: ResultSet) = ShoppingListItemRow(
    id = rs.getObject("id", UUID::class.java),
    ingredientId = rs.getObject("ingredient_id", UUID::class.java),
    name = rs.getString("raw_text"),
    quantity = rational(rs, "quantity_numerator", "quantity_denominator"),
    unitId = rs.getObject("unit_id", UUID::class.java),
    checked = rs.getBoolean("checked"),
    sortOrder = rs.getInt("sort_order"),
)

private fun toSource(rs: ResultSet) = ShoppingListSourceRow(
    id = rs.getObject("id", UUID::class.java),
    itemId = rs.getObject("shopping_list_item_id", UUID::class.java),
    recipeId = rs.getObject("recipe_id", UUID::class.java),
    recipeTitle = rs.getString("recipe_title"),
    mealPlanEntryId = rs.getObject("meal_plan_entry_id", UUID::class.java),
    rawText = rs.getString("raw_text"),
    quantity = rational(rs, "quantity_numerator", "quantity_denominator"),
    unitId = rs.getObject("unit_id", UUID::class.java),
)
