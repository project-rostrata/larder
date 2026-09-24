package larder.db

import java.sql.ResultSet
import java.util.UUID

// Global, not owner_id-scoped -- see PROJECT_BRIEF.md section 4 and AGENTS.md's ownership rule.
// No create/findOrCreate here, unlike IngredientRepository: units are a small, fixed,
// developer-seeded vocabulary (db/migrations/0002_seed_units.sql), never user-grown, so an
// unmatched unit word just resolves to null rather than auto-creating a new unit.
class UnitRepository(private val database: Database) {
    fun findByNameOrAlias(text: String): UnitRow? =
        database.queryOneOrNull(
            """
            SELECT id, name, abbreviation, dimension, to_base_factor, aliases FROM units
            WHERE lower(name) = lower(?)
               OR lower(abbreviation) = lower(?)
               OR lower(?) = ANY(SELECT lower(a) FROM unnest(aliases) AS a)
            LIMIT 1
            """.trimIndent(),
            bind = { stmt ->
                stmt.setString(1, text)
                stmt.setString(2, text)
                stmt.setString(3, text)
            },
            mapRow = ::toRow,
        )

    @Suppress("UNCHECKED_CAST")
    private fun toRow(rs: ResultSet): UnitRow = UnitRow(
        id = rs.getObject("id", UUID::class.java),
        name = rs.getString("name"),
        abbreviation = rs.getString("abbreviation"),
        dimension = rs.getString("dimension"),
        toBaseFactor = rs.getBigDecimal("to_base_factor"),
        aliases = (rs.getArray("aliases")?.array as? Array<String>)?.toList() ?: emptyList(),
    )
}
