package larder.db

import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

private const val UNIQUE_VIOLATION_SQLSTATE = "23505"

// Global, not owner_id-scoped -- see PROJECT_BRIEF.md section 4 and AGENTS.md's ownership rule.
// Exact match only, after normalization (lowercase, trim) -- against ingredients.name directly
// or via ingredient_aliases -- no fuzzy/semantic matching. A name with no match auto-creates a
// new canonical row rather than blocking the caller; docs/decisions.md has the full reasoning.
class IngredientRepository(private val database: Database) {
    // Returns the resolved id and whether this call is what created it -- callers must not
    // drop that flag; it's the hook a future "is this ingredient known?" UI depends on.
    fun findOrCreate(name: String): Pair<UUID, Boolean> {
        val normalized = name.trim()
        findByNameOrAlias(normalized)?.let { return it.id to false }

        val id = UUID.randomUUID()
        return try {
            database.update(
                "INSERT INTO ingredients (id, name) VALUES (?, ?)",
                bind = { stmt ->
                    stmt.setObject(1, id)
                    stmt.setString(2, normalized)
                },
            )
            id to true
        } catch (e: SQLException) {
            if (e.sqlState != UNIQUE_VIOLATION_SQLSTATE) throw e
            // Lost a race with a concurrent insert of the same normalized name -- the unique
            // index (ingredients_name_lower_idx) is what actually prevents the duplicate; just
            // look up whichever row won.
            val winner = findByNameOrAlias(normalized)
                ?: error("unique violation inserting ingredient '$normalized' but no matching row found")
            winner.id to false
        }
    }

    fun findById(id: UUID): IngredientRow? =
        database.queryOneOrNull(
            "SELECT id, name, plural_name FROM ingredients WHERE id = ?",
            bind = { it.setObject(1, id) },
            mapRow = ::toRow,
        )

    // Folds a duplicate ingredient into another: reassigns every recipe_ingredients/
    // shopping_list_items/ingredient_aliases row from `id` to `targetId`, reassigns
    // unit_conversions rows too (dropping one as a duplicate if `targetId` already has a
    // conversion for the same unit pair, rather than erroring), then deletes `id`. Mirrors
    // Tandoor's merge_into pattern -- see PROJECT_BRIEF.md section 4. One transaction: a
    // partial merge would leave the data in a genuinely broken state.
    fun mergeInto(id: UUID, targetId: UUID) {
        database.transaction { tx ->
            tx.update(
                "UPDATE recipe_ingredients SET ingredient_id = ? WHERE ingredient_id = ?",
                bind = { it.setObject(1, targetId); it.setObject(2, id) },
            )
            tx.update(
                "UPDATE shopping_list_items SET ingredient_id = ? WHERE ingredient_id = ?",
                bind = { it.setObject(1, targetId); it.setObject(2, id) },
            )
            tx.update(
                "UPDATE ingredient_aliases SET ingredient_id = ? WHERE ingredient_id = ?",
                bind = { it.setObject(1, targetId); it.setObject(2, id) },
            )
            // Keep the merged-away name (and plural) as aliases of the target, so a future recipe
            // line naming it resolves to the target instead of auto-creating the duplicate again.
            // ON CONFLICT covers a name that's already an alias.
            tx.update(
                """
                INSERT INTO ingredient_aliases (ingredient_id, alias)
                SELECT ?, n FROM ingredients i, LATERAL (VALUES (i.name), (i.plural_name)) AS v(n)
                WHERE i.id = ? AND n IS NOT NULL
                ON CONFLICT DO NOTHING
                """.trimIndent(),
                bind = { it.setObject(1, targetId); it.setObject(2, id) },
            )
            // Drop id's unit_conversions rows that would duplicate one targetId already has for
            // the same (from_unit_id, to_unit_id) pair, before reassigning the rest.
            tx.update(
                """
                DELETE FROM unit_conversions uc
                WHERE uc.ingredient_id = ?
                  AND EXISTS (
                    SELECT 1 FROM unit_conversions uc2
                    WHERE uc2.ingredient_id = ?
                      AND uc2.from_unit_id = uc.from_unit_id
                      AND uc2.to_unit_id = uc.to_unit_id
                  )
                """.trimIndent(),
                bind = { it.setObject(1, id); it.setObject(2, targetId) },
            )
            tx.update(
                "UPDATE unit_conversions SET ingredient_id = ? WHERE ingredient_id = ?",
                bind = { it.setObject(1, targetId); it.setObject(2, id) },
            )
            tx.update("DELETE FROM ingredients WHERE id = ?", bind = { it.setObject(1, id) })
        }
    }

    private fun findByNameOrAlias(name: String): IngredientRow? =
        database.queryOneOrNull(
            """
            SELECT i.id, i.name, i.plural_name FROM ingredients i
            WHERE lower(trim(i.name)) = lower(trim(?))
            UNION
            SELECT i.id, i.name, i.plural_name FROM ingredients i
            JOIN ingredient_aliases a ON a.ingredient_id = i.id
            WHERE lower(trim(a.alias)) = lower(trim(?))
            LIMIT 1
            """.trimIndent(),
            bind = { stmt ->
                stmt.setString(1, name)
                stmt.setString(2, name)
            },
            mapRow = ::toRow,
        )

    private fun toRow(rs: ResultSet): IngredientRow = IngredientRow(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        pluralName = rs.getString("plural_name"),
    )
}
