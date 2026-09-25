package larder.db

import java.math.BigDecimal
import java.sql.ResultSet
import java.time.LocalDate
import java.util.UUID

private const val ENTRY_SELECT = """
    SELECT e.id, e.owner_id, e.plan_date, e.meal_slot, e.recipe_id, e.servings_multiplier,
           e.created_at, r.title AS recipe_title, (r.deleted_at IS NOT NULL) AS recipe_deleted
    FROM meal_plan_entries e
    JOIN recipes r ON r.id = e.recipe_id
"""

class MealPlanRepository(private val database: Database) {
    // Inclusive on both ends. Ordered by date, then slot in MEAL_SLOTS order (not
    // alphabetical), then creation order within a slot.
    fun list(ownerId: UUID, from: LocalDate, to: LocalDate): List<MealPlanEntryRow> =
        database.queryList(
            """
            $ENTRY_SELECT
            WHERE e.owner_id = ? AND e.plan_date BETWEEN ? AND ?
            ORDER BY e.plan_date,
                     array_position(ARRAY['breakfast','lunch','dinner','snack'], e.meal_slot),
                     e.created_at
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setObject(2, from)
                stmt.setObject(3, to)
            },
            mapRow = ::toEntryRow,
        )

    // Caller must already have verified recipeId belongs to ownerId -- the FK can't.
    fun create(
        ownerId: UUID,
        planDate: LocalDate,
        mealSlot: String,
        recipeId: UUID,
        servingsMultiplier: BigDecimal,
    ): MealPlanEntryRow = database.transaction { tx ->
        val id = UUID.randomUUID()
        tx.update(
            """
            INSERT INTO meal_plan_entries (id, owner_id, plan_date, meal_slot, recipe_id, servings_multiplier)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, ownerId)
                stmt.setObject(3, planDate)
                stmt.setString(4, mealSlot)
                stmt.setObject(5, recipeId)
                stmt.setBigDecimal(6, servingsMultiplier)
            },
        )
        tx.queryOne("$ENTRY_SELECT WHERE e.id = ?", bind = { it.setObject(1, id) }, mapRow = ::toEntryRow)
    }

    // Hard delete: soft delete exists to keep meal-plan history pointing at recipes, but an
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
    planDate = rs.getObject("plan_date", LocalDate::class.java),
    mealSlot = rs.getString("meal_slot"),
    recipeId = rs.getObject("recipe_id", UUID::class.java),
    servingsMultiplier = rs.getBigDecimal("servings_multiplier"),
    createdAt = rs.getTimestamp("created_at").toInstant(),
    recipeTitle = rs.getString("recipe_title"),
    recipeDeleted = rs.getBoolean("recipe_deleted"),
)
