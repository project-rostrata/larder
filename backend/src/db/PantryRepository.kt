package larder.db

import java.util.UUID

data class PantryItemRow(val ingredientId: UUID, val name: String)

// A user's pantry: ingredients they always keep. Owner-scoped in every query (AGENTS.md).
class PantryRepository(private val database: Database) {
    fun list(ownerId: UUID): List<PantryItemRow> =
        database.queryList(
            """
            SELECT p.ingredient_id, i.name FROM pantry_items p JOIN ingredients i ON i.id = p.ingredient_id
            WHERE p.owner_id = ? ORDER BY lower(i.name)
            """.trimIndent(),
            bind = { it.setObject(1, ownerId) },
            mapRow = { rs -> PantryItemRow(rs.getObject("ingredient_id", UUID::class.java), rs.getString("name")) },
        )

    // Idempotent: adding something already in the pantry is a no-op.
    fun add(ownerId: UUID, ingredientId: UUID) {
        database.update(
            "INSERT INTO pantry_items (owner_id, ingredient_id) VALUES (?, ?) ON CONFLICT DO NOTHING",
            bind = { stmt -> stmt.setObject(1, ownerId); stmt.setObject(2, ingredientId) },
        )
    }

    fun remove(ownerId: UUID, ingredientId: UUID): Boolean =
        database.update(
            "DELETE FROM pantry_items WHERE owner_id = ? AND ingredient_id = ?",
            bind = { stmt -> stmt.setObject(1, ownerId); stmt.setObject(2, ingredientId) },
        ) > 0
}
