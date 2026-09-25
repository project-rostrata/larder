package larder.db

import java.math.BigDecimal
import java.sql.ResultSet
import java.util.UUID

private const val ENTRY_SELECT = """
    SELECT e.id, e.owner_id, e.recipe_id, e.label, e.servings_multiplier, e.created_at,
           r.title AS recipe_title, (r.deleted_at IS NOT NULL) AS recipe_deleted
    FROM meal_plan_entries e
    JOIN recipes r ON r.id = e.recipe_id
"""

class MealPlanRepository(private val database: Database) {
    fun list(ownerId: UUID): List<MealPlanEntryRow> =
        database.queryList(
            "$ENTRY_SELECT WHERE e.owner_id = ? ORDER BY e.created_at",
            bind = { it.setObject(1, ownerId) },
            mapRow = ::toEntryRow,
        )

    // Caller must already have verified recipeId belongs to ownerId -- the FK can't.
    fun create(ownerId: UUID, recipeId: UUID, label: String?, servingsMultiplier: BigDecimal): MealPlanEntryRow =
        database.transaction { tx ->
            val id = UUID.randomUUID()
            tx.update(
                """
                INSERT INTO meal_plan_entries (id, owner_id, recipe_id, label, servings_multiplier)
                VALUES (?, ?, ?, ?, ?)
                """.trimIndent(),
                bind = { stmt ->
                    stmt.setObject(1, id)
                    stmt.setObject(2, ownerId)
                    stmt.setObject(3, recipeId)
                    stmt.setString(4, label)
                    stmt.setBigDecimal(5, servingsMultiplier)
                },
            )
            tx.queryOne("$ENTRY_SELECT WHERE e.id = ?", bind = { it.setObject(1, id) }, mapRow = ::toEntryRow)
        }

    // Hard delete: soft delete exists to keep meal-plan entries pointing at recipes, but an
    // entry itself has nothing depending on it.
    fun delete(id: UUID, ownerId: UUID): Boolean =
        database.update(
            "DELETE FROM meal_plan_entries WHERE id = ? AND owner_id = ?",
            bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) },
        ) > 0
}

private fun toEntryRow(rs: ResultSet) = MealPlanEntryRow(
    id = rs.getObject("id", UUID::class.java),
    ownerId = rs.getObject("owner_id", UUID::class.java),
    recipeId = rs.getObject("recipe_id", UUID::class.java),
    label = rs.getString("label"),
    servingsMultiplier = rs.getBigDecimal("servings_multiplier"),
    createdAt = rs.getTimestamp("created_at").toInstant(),
    recipeTitle = rs.getString("recipe_title"),
    recipeDeleted = rs.getBoolean("recipe_deleted"),
)
