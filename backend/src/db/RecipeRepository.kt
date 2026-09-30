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
    val notes: String?,
)

data class PersistedRecipe(val recipe: RecipeRow, val ingredients: List<RecipeIngredientRow>)

private const val RECIPE_COLUMNS =
    "id, owner_id, title, source_url, servings, servings_text, prep_time_minutes, " +
        "cook_time_minutes, total_time_minutes, tags, instructions, notes, created_at, updated_at, deleted_at, " +
        "variant_of_recipe_id"
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
                    prep_time_minutes, cook_time_minutes, total_time_minutes, tags, instructions, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                bind = { stmt -> bindRecipeFields(stmt, id, ownerId, fields, startIndex = 1) },
            )
            val insertedIngredients = insertIngredients(tx, id, ingredientLines)
            PersistedRecipe(findInTx(tx, id), insertedIngredients)
        }

    // Full replace, not a patch: recipe_ingredients is deleted and re-inserted wholesale, same
    // transaction as the recipe row's own UPDATE. Returns null if id doesn't resolve to a
    // non-deleted recipe owned by ownerId — the caller (RecipeUpdateHandler) turns that into a
    // 404, same as an already-deleted recipe or one that never existed. A meal-plan variant is
    // also a 404 here: it's only edited through its entry (MealPlanRepository.saveVariant).
    fun update(
        id: UUID,
        ownerId: UUID,
        fields: RecipeFields,
        ingredientLines: List<ResolvedIngredientLine>,
    ): PersistedRecipe? = database.transaction { tx ->
        val existing = tx.queryOneOrNull(
            """
            SELECT $RECIPE_COLUMNS FROM recipes
            WHERE id = ? AND owner_id = ? AND deleted_at IS NULL AND variant_of_recipe_id IS NULL
            """.trimIndent(),
            bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) },
            mapRow = ::toRecipeRow,
        ) ?: return@transaction null
        resetShoppingListsForRecipe(tx, existing.id)
        replaceContents(tx, existing.id, ownerId, fields, ingredientLines)
    }

    // Soft-deleted recipes are filtered here, in the API layer, like everywhere else: to every
    // client a deleted recipe simply doesn't exist. The row stays in the database so historical
    // meal_plan_entries keep a valid recipe_id. Meal-plan variants are found too, so a planned
    // meal's recipe page shows the version that will be cooked.
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
            """
            SELECT id, title FROM recipes
            WHERE owner_id = ? AND deleted_at IS NULL AND variant_of_recipe_id IS NULL AND id = ANY(?)
            """.trimIndent(),
            bind = { stmt ->
                stmt.setObject(1, ownerId)
                stmt.setArray(2, stmt.connection.createArrayOf("uuid", ids.toTypedArray()))
            },
            mapRow = { rs -> rs.getObject("id", UUID::class.java) to rs.getString("title") },
        ).toMap()

    // Owner-scoped through recipes, like every recipe query (AGENTS.md ownership rule).
    fun findIngredients(recipeId: UUID, ownerId: UUID): List<RecipeIngredientRow> =
        database.queryList(
            """
            SELECT $RECIPE_INGREDIENT_COLUMNS FROM recipe_ingredients
            WHERE recipe_id = ? AND recipe_id IN (SELECT id FROM recipes WHERE owner_id = ?)
            ORDER BY position
            """.trimIndent(),
            bind = { stmt -> stmt.setObject(1, recipeId); stmt.setObject(2, ownerId) },
            mapRow = ::toIngredientRow,
        )

    // tag = null lists everything; otherwise only recipes with that tag. One query handles
    // both via the ?::text IS NULL branch, rather than building SQL conditionally.
    fun list(ownerId: UUID, tag: String?): List<RecipeRow> =
        database.queryList(
            """
            SELECT $RECIPE_COLUMNS FROM recipes
            WHERE owner_id = ? AND deleted_at IS NULL AND variant_of_recipe_id IS NULL
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
    // leaking which one applies. A recipe's meal-plan variants go with it, so a modified entry
    // leaves the active plan (and shows as deleted in past ones) just like an unmodified one.
    fun softDelete(id: UUID, ownerId: UUID): Boolean = database.transaction { tx ->
        val deleted = tx.update(
            """
            UPDATE recipes SET deleted_at = now()
            WHERE id = ? AND owner_id = ? AND deleted_at IS NULL AND variant_of_recipe_id IS NULL
            """.trimIndent(),
            bind = { stmt -> stmt.setObject(1, id); stmt.setObject(2, ownerId) },
        ) > 0
        if (deleted) {
            resetShoppingListsForRecipe(tx, id)
            tx.update(
                "UPDATE recipes SET deleted_at = now() WHERE variant_of_recipe_id = ? AND deleted_at IS NULL",
                bind = { it.setObject(1, id) },
            )
        }
        deleted
    }
}

// Shared with MealPlanRepository, which creates and edits meal-plan variants in its own
// transactions.

internal fun findInTx(tx: Transaction, id: UUID): RecipeRow =
    tx.queryOne("SELECT $RECIPE_COLUMNS FROM recipes WHERE id = ?", bind = { it.setObject(1, id) }, mapRow = ::toRecipeRow)

// Overwrites a recipe's fields and replaces its ingredient lines wholesale. The caller has
// already checked ownership and resets whatever shopping lists the change affects.
internal fun replaceContents(
    tx: Transaction,
    id: UUID,
    ownerId: UUID,
    fields: RecipeFields,
    ingredientLines: List<ResolvedIngredientLine>,
): PersistedRecipe {
    tx.update(
        """
        UPDATE recipes SET title = ?, source_url = ?, servings = ?, servings_text = ?,
            prep_time_minutes = ?, cook_time_minutes = ?, total_time_minutes = ?,
            tags = ?, instructions = ?, notes = ?, updated_at = now()
        WHERE id = ?
        """.trimIndent(),
        bind = { stmt -> bindRecipeFields(stmt, id, ownerId, fields, startIndex = 1, includeId = true) },
    )
    tx.update("DELETE FROM recipe_ingredients WHERE recipe_id = ?", bind = { it.setObject(1, id) })
    val insertedIngredients = insertIngredients(tx, id, ingredientLines)
    return PersistedRecipe(findInTx(tx, id), insertedIngredients)
}

// Inserts a meal-plan variant of originalId with the given contents, owned by ownerId (the
// original's owner; the caller has checked).
internal fun insertVariant(
    tx: Transaction,
    originalId: UUID,
    ownerId: UUID,
    fields: RecipeFields,
    ingredientLines: List<ResolvedIngredientLine>,
): PersistedRecipe {
    val id = UUID.randomUUID()
    tx.update(
        """
        INSERT INTO recipes (id, owner_id, title, source_url, servings, servings_text,
            prep_time_minutes, cook_time_minutes, total_time_minutes, tags, instructions, notes,
            variant_of_recipe_id)
        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
        """.trimIndent(),
        bind = { stmt ->
            bindRecipeFields(stmt, id, ownerId, fields, startIndex = 1)
            stmt.setObject(13, originalId)
        },
    )
    val insertedIngredients = insertIngredients(tx, id, ingredientLines)
    return PersistedRecipe(findInTx(tx, id), insertedIngredients)
}

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
    stmt.setString(i++, fields.notes)
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
    notes = rs.getString("notes"),
    createdAt = rs.getTimestamp("created_at").toInstant(),
    updatedAt = rs.getTimestamp("updated_at").toInstant(),
    deletedAt = rs.getTimestamp("deleted_at")?.toInstant(),
    variantOfRecipeId = rs.getObject("variant_of_recipe_id", UUID::class.java),
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
