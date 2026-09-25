package larder.db

import larder.ingredients.ResolvedIngredientLine
import java.math.BigDecimal
import java.sql.ResultSet
import java.sql.Types
import java.util.UUID

// The fields a caller supplies for a recipe, shared between create and update — one request
// shape describes the desired final state either way. Ingredient lines are handled separately
// (see create/update below) since they need to be resolved (larder.ingredients) before they're
// anything a repository can persist.
data class RecipeFields(
    val title: String,
    val sourceUrl: String?,
    val servings: BigDecimal?,
    val servingsText: String?,
    val prepTimeMinutes: Int?,
    val cookTimeMinutes: Int?,
    val totalTimeMinutes: Int?,
    val tags: List<String>,
    val instructions: List<String>,
)

data class PersistedRecipe(val recipe: RecipeRow, val ingredients: List<RecipeIngredientRow>)

private const val RECIPE_COLUMNS =
    "id, owner_id, title, source_url, servings, servings_text, prep_time_minutes, " +
        "cook_time_minutes, total_time_minutes, tags, instructions, created_at, updated_at, deleted_at"
private const val RECIPE_INGREDIENT_COLUMNS =
    "id, recipe_id, position, raw_text, notes, quantity_numerator, quantity_denominator, unit_id, ingredient_id"

class RecipeRepository(private val database: Database) {
    // recipe_ingredients commits atomically with its parent recipe — see Database.transaction().
    fun create(ownerId: UUID, fields: RecipeFields, ingredientLines: List<ResolvedIngredientLine>): PersistedRecipe =
        database.transaction { tx ->
            val id = UUID.randomUUID()
            tx.update(
                """
                INSERT INTO recipes (id, owner_id, title, source_url, servings, servings_text,
                    prep_time_minutes, cook_time_minutes, total_time_minutes, tags, instructions)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                bind = { stmt -> bindRecipeFields(stmt, id, ownerId, fields, startIndex = 1) },
            )
            val insertedIngredients = insertIngredients(tx, id, ingredientLines)
            val recipe = tx.queryOne(
                "SELECT $RECIPE_COLUMNS FROM recipes WHERE id = ?",
                bind = { it.setObject(1, id) },
                mapRow = ::toRecipeRow,
            )
            PersistedRecipe(recipe, insertedIngredients)
        }

    // Full replace, not a patch: recipe_ingredients is deleted and re-inserted wholesale, same
    // transaction as the recipe row's own UPDATE. Returns null if id doesn't resolve to a
    // non-deleted recipe owned by ownerId — the caller (RecipeUpdateHandler) turns that into a
    // 404, same as an already-deleted recipe or one that never existed.
    fun update(
        id: UUID,
        ownerId: UUID,
        fields: RecipeFields,
        ingredientLines: List<ResolvedIngredientLine>,
    ): PersistedRecipe? = database.transaction { tx ->
        val existing = tx.queryOneOrNull(
            "SELECT $RECIPE_COLUMNS FROM recipes WHERE id = ? AND owner_id = ? AND deleted_at IS NULL",
            bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) },
            mapRow = ::toRecipeRow,
        ) ?: return@transaction null

        tx.update(
            """
            UPDATE recipes SET title = ?, source_url = ?, servings = ?, servings_text = ?,
                prep_time_minutes = ?, cook_time_minutes = ?, total_time_minutes = ?,
                tags = ?, instructions = ?, updated_at = now()
            WHERE id = ?
            """.trimIndent(),
            bind = { stmt -> bindRecipeFields(stmt, existing.id, ownerId, fields, startIndex = 1, includeId = true) },
        )
        tx.update("DELETE FROM recipe_ingredients WHERE recipe_id = ?", bind = { it.setObject(1, id) })
        val insertedIngredients = insertIngredients(tx, id, ingredientLines)
        val recipe = tx.queryOne(
            "SELECT $RECIPE_COLUMNS FROM recipes WHERE id = ?",
            bind = { it.setObject(1, id) },
            mapRow = ::toRecipeRow,
        )
        PersistedRecipe(recipe, insertedIngredients)
    }

    // Soft-deleted recipes are filtered here, in the API layer, like everywhere else: to every
    // client a deleted recipe simply doesn't exist. The row stays in the database so historical
    // meal_plan_entries keep a valid recipe_id.
    fun findById(id: UUID, ownerId: UUID): RecipeRow? =
        database.queryOneOrNull(
            "SELECT $RECIPE_COLUMNS FROM recipes WHERE id = ? AND owner_id = ? AND deleted_at IS NULL",
            bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) },
            mapRow = ::toRecipeRow,
        )

    // Titles of the given ids that are owned by ownerId and not soft-deleted -- a missing key
    // means "unknown, deleted, or not yours", which callers report as one 403.
    fun findActiveTitles(ownerId: UUID, ids: Collection<UUID>): Map<UUID, String> =
        database.queryList(
            "SELECT id, title FROM recipes WHERE owner_id = ? AND deleted_at IS NULL AND id = ANY(?)",
            bind = { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setArray(2, stmt.connection.createArrayOf("uuid", ids.toTypedArray()))
            },
            mapRow = { rs -> rs.getObject("id", UUID::class.java) to rs.getString("title") },
        ).toMap()

    fun findIngredients(recipeId: UUID): List<RecipeIngredientRow> =
        database.queryList(
            "SELECT $RECIPE_INGREDIENT_COLUMNS FROM recipe_ingredients WHERE recipe_id = ? ORDER BY position",
            bind = { it.setObject(1, recipeId) },
            mapRow = ::toIngredientRow,
        )

    // tag = null lists everything; otherwise only recipes with that tag. One query handles
    // both via the ?::text IS NULL branch, rather than building SQL conditionally.
    fun list(ownerId: UUID, tag: String?): List<RecipeRow> =
        database.queryList(
            """
            SELECT $RECIPE_COLUMNS FROM recipes
            WHERE owner_id = ? AND deleted_at IS NULL
              AND (?::text IS NULL OR ? = ANY(tags))
            ORDER BY created_at DESC
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setString(2, tag)
                stmt.setString(3, tag)
            },
            mapRow = ::toRecipeRow,
        )

    // True only if this call is what deleted it — false for "doesn't exist", "not yours", and
    // "already deleted" alike, so the caller can turn all three into the same 404 rather than
    // leaking which one applies.
    fun softDelete(id: UUID, ownerId: UUID): Boolean =
        database.update(
            "UPDATE recipes SET deleted_at = now() WHERE id = ? AND owner_id = ? AND deleted_at IS NULL",
            bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) },
        ) > 0

    private fun insertIngredients(
        tx: Transaction,
        recipeId: UUID,
        lines: List<ResolvedIngredientLine>,
    ): List<RecipeIngredientRow> = lines.mapIndexed { position, line ->
        val id = UUID.randomUUID()
        tx.update(
            """
            INSERT INTO recipe_ingredients (id, recipe_id, position, raw_text, notes,
                quantity_numerator, quantity_denominator, unit_id, ingredient_id)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, id)
                stmt.setObject(2, recipeId)
                stmt.setInt(3, position)
                stmt.setString(4, line.rawText)
                stmt.setString(5, line.notes)
                setNullableInt(stmt, 6, line.quantityNumerator)
                setNullableInt(stmt, 7, line.quantityDenominator)
                stmt.setObject(8, line.unitId)
                stmt.setObject(9, line.ingredientId)
            },
        )
        RecipeIngredientRow(
            id = id,
            recipeId = recipeId,
            position = position,
            rawText = line.rawText,
            notes = line.notes,
            quantityNumerator = line.quantityNumerator,
            quantityDenominator = line.quantityDenominator,
            unitId = line.unitId,
            ingredientId = line.ingredientId,
        )
    }

    private fun bindRecipeFields(
        stmt: java.sql.PreparedStatement,
        id: UUID,
        ownerId: UUID,
        fields: RecipeFields,
        startIndex: Int,
        includeId: Boolean = false,
    ) {
        var i = startIndex
        if (!includeId) {
            stmt.setObject(i++, id)
            stmt.setObject(i++, ownerId)
        }
        stmt.setString(i++, fields.title)
        stmt.setString(i++, fields.sourceUrl)
        if (fields.servings != null) stmt.setBigDecimal(i++, fields.servings) else stmt.setNull(i++, Types.NUMERIC)
        stmt.setString(i++, fields.servingsText)
        setNullableInt(stmt, i++, fields.prepTimeMinutes)
        setNullableInt(stmt, i++, fields.cookTimeMinutes)
        setNullableInt(stmt, i++, fields.totalTimeMinutes)
        stmt.setArray(i++, stmt.connection.createArrayOf("text", fields.tags.toTypedArray()))
        stmt.setArray(i++, stmt.connection.createArrayOf("text", fields.instructions.toTypedArray()))
        if (includeId) stmt.setObject(i, id)
    }

    private fun setNullableInt(stmt: java.sql.PreparedStatement, index: Int, value: Int?) {
        if (value != null) stmt.setInt(index, value) else stmt.setNull(index, Types.INTEGER)
    }

    @Suppress("UNCHECKED_CAST")
    private fun toRecipeRow(rs: ResultSet): RecipeRow = RecipeRow(
        id = rs.getObject("id", UUID::class.java),
        ownerId = rs.getObject("owner_id", UUID::class.java),
        title = rs.getString("title"),
        sourceUrl = rs.getString("source_url"),
        servings = rs.getBigDecimal("servings"),
        servingsText = rs.getString("servings_text"),
        prepTimeMinutes = rs.getInt("prep_time_minutes").takeUnless { rs.wasNull() },
        cookTimeMinutes = rs.getInt("cook_time_minutes").takeUnless { rs.wasNull() },
        totalTimeMinutes = rs.getInt("total_time_minutes").takeUnless { rs.wasNull() },
        tags = (rs.getArray("tags")?.array as? Array<String>)?.toList() ?: emptyList(),
        instructions = (rs.getArray("instructions")?.array as? Array<String>)?.toList() ?: emptyList(),
        createdAt = rs.getTimestamp("created_at").toInstant(),
        updatedAt = rs.getTimestamp("updated_at").toInstant(),
        deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
    )

    private fun toIngredientRow(rs: ResultSet): RecipeIngredientRow = RecipeIngredientRow(
        id = rs.getObject("id", UUID::class.java),
        recipeId = rs.getObject("recipe_id", UUID::class.java),
        position = rs.getInt("position"),
        rawText = rs.getString("raw_text"),
        notes = rs.getString("notes"),
        quantityNumerator = rs.getInt("quantity_numerator").takeUnless { rs.wasNull() },
        quantityDenominator = rs.getInt("quantity_denominator").takeUnless { rs.wasNull() },
        unitId = rs.getObject("unit_id", UUID::class.java),
        ingredientId = rs.getObject("ingredient_id", UUID::class.java),
    )
}
