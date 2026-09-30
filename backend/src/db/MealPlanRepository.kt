package larder.db

import larder.ingredients.ResolvedIngredientLine
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

private const val ENTRY_SELECT = """
    SELECT e.id, e.owner_id, e.meal_plan_id, e.recipe_id, e.label, e.servings_multiplier, e.created_at,
           r.title AS recipe_title, r.servings AS recipe_servings, r.servings_text AS recipe_servings_text,
           (r.deleted_at IS NOT NULL) AS recipe_deleted, r.variant_of_recipe_id
    FROM meal_plan_entries e
    JOIN recipes r ON r.id = e.recipe_id
"""
private const val PLAN_COLUMNS = "id, owner_id, created_at, archived_at"
private const val UNIQUE_VIOLATION = "23505"

class MealPlanRepository(private val database: Database) {

    // The active plan's entries. Entries whose recipe was soft-deleted stay in the table but
    // are filtered here, so the current plan never shows a deleted recipe.
    fun list(ownerId: UUID): List<MealPlanEntryRow> =
        database.queryList(
            """
            $ENTRY_SELECT
            JOIN meal_plans p ON p.id = e.meal_plan_id
            WHERE p.owner_id = ? AND p.archived_at IS NULL AND r.deleted_at IS NULL
            ORDER BY e.created_at
            """.trimIndent(),
            bind = { it.setObject(1, ownerId) },
            mapRow = ::toEntryRow,
        )

    // Adds to the active plan, creating one if the user has none yet. Caller must already have
    // verified recipeId belongs to ownerId -- the FK can't.
    fun create(ownerId: UUID, recipeId: UUID, label: String?, servingsMultiplier: BigDecimal): MealPlanEntryRow =
        database.transaction { tx ->
            val planId = activePlanId(tx, ownerId)
            val id = insertEntry(tx, ownerId, planId, recipeId, label, servingsMultiplier)
            resetPlanShoppingList(tx, planId)
            tx.queryOne("$ENTRY_SELECT WHERE e.id = ?", bind = { it.setObject(1, id) }, mapRow = ::toEntryRow)
        }

    // Only entries of the active plan can be removed -- history is a record, not editable. The
    // entry's variant, if it had one, goes with it: nothing else refers to it.
    fun delete(id: UUID, ownerId: UUID): Boolean = database.transaction { tx ->
        val (planId, recipeId) = tx.queryOneOrNull(
            """
            DELETE FROM meal_plan_entries e USING meal_plans p
            WHERE e.id = ? AND e.meal_plan_id = p.id AND p.owner_id = ? AND p.archived_at IS NULL
            RETURNING e.meal_plan_id, e.recipe_id
            """.trimIndent(),
            bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) },
            mapRow = { it.getObject("meal_plan_id", UUID::class.java) to it.getObject("recipe_id", UUID::class.java) },
        ) ?: return@transaction false
        deleteVariant(tx, recipeId)
        resetPlanShoppingList(tx, planId)
        true
    }

    // Saves a changed recipe for one entry of the owner's active plan. The first save copies the
    // recipe into a variant for this entry alone and points the entry at it; later saves edit
    // that variant. The original is never touched. Null when the entry isn't in the owner's
    // active plan, or its recipe was deleted.
    fun saveVariant(
        entryId: UUID,
        ownerId: UUID,
        fields: RecipeFields,
        ingredientLines: List<ResolvedIngredientLine>,
    ): PersistedRecipe? = database.transaction { tx ->
        val entry = activeEntry(tx, entryId, ownerId) ?: return@transaction null
        if (entry.recipeDeleted) return@transaction null
        val saved = if (entry.originalRecipeId != null) {
            replaceContents(tx, entry.recipeId, ownerId, fields, ingredientLines)
        } else {
            insertVariant(tx, entry.recipeId, ownerId, fields, ingredientLines).also { variant ->
                tx.update(
                    "UPDATE meal_plan_entries SET recipe_id = ? WHERE id = ?",
                    bind = { stmt -> stmt.setObject(1, variant.recipe.id); stmt.setObject(2, entryId) },
                )
            }
        }
        resetPlanShoppingList(tx, entry.mealPlanId)
        saved
    }

    // Points an active-plan entry back at its original recipe and deletes its variant. An entry
    // that was never modified is left as it is. False when the entry isn't in the owner's active
    // plan.
    fun revertVariant(entryId: UUID, ownerId: UUID): Boolean = database.transaction { tx ->
        val entry = activeEntry(tx, entryId, ownerId) ?: return@transaction false
        val originalId = entry.originalRecipeId ?: return@transaction true
        tx.update(
            "UPDATE meal_plan_entries SET recipe_id = ? WHERE id = ?",
            bind = { stmt -> stmt.setObject(1, originalId); stmt.setObject(2, entryId) },
        )
        deleteVariant(tx, entry.recipeId)
        resetPlanShoppingList(tx, entry.mealPlanId)
        true
    }

    // The active plan, if the user has one (it's created lazily on the first add).
    fun activePlan(ownerId: UUID): MealPlanRow? =
        database.queryOneOrNull(
            "SELECT $PLAN_COLUMNS FROM meal_plans WHERE owner_id = ? AND archived_at IS NULL",
            bind = { it.setObject(1, ownerId) },
            mapRow = ::toPlanRow,
        )

    // Archives the active plan if it has any entries (an empty one is simply reused, so history
    // never fills with empty plans) and leaves a fresh active plan, optionally seeded with
    // copyFrom's entries (soft-deleted recipes skipped). A modified entry is copied as its
    // original recipe: variants are one-off changes for one meal. Entries are never deleted here:
    // even ones whose recipe was soft-deleted are kept, in the archived plan.
    fun startNew(ownerId: UUID, copyFrom: UUID?) = database.transaction { tx ->
        val current = tx.queryOneOrNull(
            "SELECT $PLAN_COLUMNS FROM meal_plans WHERE owner_id = ? AND archived_at IS NULL",
            bind = { it.setObject(1, ownerId) },
            mapRow = ::toPlanRow,
        )
        // Shopping lists belong to the current plan only; whichever plan this leaves active
        // starts without one, and the outgoing plan's list isn't kept.
        if (current != null) resetPlanShoppingList(tx, current.id)
        val planId = if (current != null && entryCount(tx, current.id) == 0) {
            tx.update("UPDATE meal_plans SET created_at = now() WHERE id = ?", bind = { it.setObject(1, current.id) })
            current.id
        } else {
            if (current != null) {
                tx.update("UPDATE meal_plans SET archived_at = now() WHERE id = ?", bind = { it.setObject(1, current.id) })
            }
            activePlanId(tx, ownerId)
        }
        if (copyFrom != null) {
            tx.update(
                """
                INSERT INTO meal_plan_entries (owner_id, meal_plan_id, recipe_id, label, servings_multiplier, created_at)
                SELECT e.owner_id, ?, o.id, e.label, e.servings_multiplier,
                       now() + (row_number() OVER (ORDER BY e.created_at)) * interval '1 microsecond'
                FROM meal_plan_entries e
                JOIN recipes r ON r.id = e.recipe_id
                JOIN recipes o ON o.id = COALESCE(r.variant_of_recipe_id, r.id)
                WHERE e.meal_plan_id = ? AND e.owner_id = ? AND o.deleted_at IS NULL
                """.trimIndent(),
                bind = { stmt -> stmt.setObject(1, planId); stmt.setObject(2, copyFrom); stmt.setObject(3, ownerId) },
            )
        }
    }

    // Visible (non-deleted-recipe) entries in the active plan; 0 if there's no active plan.
    fun activeEntryCount(ownerId: UUID): Int =
        database.queryOne(
            """
            SELECT count(*) AS n FROM meal_plan_entries e
            JOIN meal_plans p ON p.id = e.meal_plan_id JOIN recipes r ON r.id = e.recipe_id
            WHERE p.owner_id = ? AND p.archived_at IS NULL AND r.deleted_at IS NULL
            """.trimIndent(),
            bind = { it.setObject(1, ownerId) },
            mapRow = { it.getInt("n") },
        )

    // Archived plans, newest first, each with all its entries (deleted recipes included --
    // history stays accurate; the API marks them).
    fun history(ownerId: UUID): List<Pair<MealPlanRow, List<MealPlanEntryRow>>> {
        val plans = database.queryList(
            "SELECT $PLAN_COLUMNS FROM meal_plans WHERE owner_id = ? AND archived_at IS NOT NULL ORDER BY archived_at DESC",
            bind = { it.setObject(1, ownerId) },
            mapRow = ::toPlanRow,
        )
        val entries = database.queryList(
            """
            $ENTRY_SELECT
            JOIN meal_plans p ON p.id = e.meal_plan_id
            WHERE p.owner_id = ? AND p.archived_at IS NOT NULL
            ORDER BY e.created_at
            """.trimIndent(),
            bind = { it.setObject(1, ownerId) },
            mapRow = ::toEntryRow,
        ).groupBy { it.mealPlanId }
        return plans.map { it to entries[it.id].orEmpty() }
    }

    // Any of the owner's plans, active or archived. Active plans hide deleted recipes (same as
    // list()); archived plans keep them.
    fun find(planId: UUID, ownerId: UUID): Pair<MealPlanRow, List<MealPlanEntryRow>>? {
        val plan = database.queryOneOrNull(
            "SELECT $PLAN_COLUMNS FROM meal_plans WHERE id = ? AND owner_id = ?",
            bind = { stmt -> stmt.setObject(1, planId); stmt.setObject(2, ownerId) },
            mapRow = ::toPlanRow,
        ) ?: return null
        val entries = database.queryList(
            "$ENTRY_SELECT WHERE e.meal_plan_id = ? ORDER BY e.created_at",
            bind = { it.setObject(1, planId) },
            mapRow = ::toEntryRow,
        ).filter { plan.archivedAt != null || !it.recipeDeleted }
        return plan to entries
    }

    private fun entryCount(tx: Transaction, planId: UUID): Int =
        tx.queryOne(
            "SELECT count(*) AS n FROM meal_plan_entries WHERE meal_plan_id = ?",
            bind = { it.setObject(1, planId) },
            mapRow = { it.getInt("n") },
        )

    // The active plan's id, creating it if needed. The partial unique index guarantees at most
    // one; a concurrent creator losing the race just reads the winner.
    private fun activePlanId(tx: Transaction, ownerId: UUID): UUID {
        val find = {
            tx.queryOneOrNull(
                "SELECT id FROM meal_plans WHERE owner_id = ? AND archived_at IS NULL",
                bind = { it.setObject(1, ownerId) },
                mapRow = { it.getObject("id", UUID::class.java) },
            )
        }
        find()?.let { return it }
        val id = UUID.randomUUID()
        return try {
            tx.update("INSERT INTO meal_plans (id, owner_id) VALUES (?, ?)", bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) })
            id
        } catch (e: SQLException) {
            if (e.sqlState != UNIQUE_VIOLATION) throw e
            find() ?: throw e
        }
    }

    private fun activeEntry(tx: Transaction, entryId: UUID, ownerId: UUID): MealPlanEntryRow? =
        tx.queryOneOrNull(
            """
            $ENTRY_SELECT
            JOIN meal_plans p ON p.id = e.meal_plan_id
            WHERE e.id = ? AND p.owner_id = ? AND p.archived_at IS NULL
            """.trimIndent(),
            bind = { stmt -> stmt.setObject(1, entryId); stmt.setObject(2, ownerId) },
            mapRow = ::toEntryRow,
        )

    // A no-op for an ordinary recipe. Call only once no entry points at the variant.
    private fun deleteVariant(tx: Transaction, recipeId: UUID) {
        tx.update(
            "DELETE FROM recipes WHERE id = ? AND variant_of_recipe_id IS NOT NULL",
            bind = { it.setObject(1, recipeId) },
        )
    }

    private fun insertEntry(tx: Transaction, ownerId: UUID, planId: UUID, recipeId: UUID, label: String?, multiplier: BigDecimal): UUID {
        val id = UUID.randomUUID()
        tx.update(
            """
            INSERT INTO meal_plan_entries (id, owner_id, meal_plan_id, recipe_id, label, servings_multiplier)
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, ownerId)
                stmt.setObject(3, planId)
                stmt.setObject(4, recipeId)
                stmt.setString(5, label)
                stmt.setBigDecimal(6, multiplier)
            },
        )
        return id
    }
}

private fun toEntryRow(rs: ResultSet) = MealPlanEntryRow(
    id = rs.getObject("id", UUID::class.java),
    ownerId = rs.getObject("owner_id", UUID::class.java),
    mealPlanId = rs.getObject("meal_plan_id", UUID::class.java),
    recipeId = rs.getObject("recipe_id", UUID::class.java),
    label = rs.getString("label"),
    servingsMultiplier = rs.getBigDecimal("servings_multiplier"),
    createdAt = rs.getTimestamp("created_at").toInstant(),
    recipeTitle = rs.getString("recipe_title"),
    recipeServings = rs.getBigDecimal("recipe_servings"),
    recipeServingsText = rs.getString("recipe_servings_text"),
    recipeDeleted = rs.getBoolean("recipe_deleted"),
    originalRecipeId = rs.getObject("variant_of_recipe_id", UUID::class.java),
)

private fun toPlanRow(rs: ResultSet) = MealPlanRow(
    id = rs.getObject("id", UUID::class.java),
    ownerId = rs.getObject("owner_id", UUID::class.java),
    createdAt = rs.getTimestamp("created_at").toInstant(),
    archivedAt = rs.getTimestamp("archived_at")?.toInstant(),
)
