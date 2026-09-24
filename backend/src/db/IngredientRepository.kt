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
