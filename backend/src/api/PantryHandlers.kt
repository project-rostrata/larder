package larder.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.IngredientRepository
import larder.db.PantryRepository
import larder.ingredients.normalizeIngredientName
import java.util.UUID

private const val MAX_PANTRY_NAME_LENGTH = 100

@Serializable
data class PantryItemResponse(val ingredientId: String, val name: String)

@Serializable
data class PantryResponse(val items: List<PantryItemResponse>, val summary: String)

// Exactly one: an ingredient already on the shopping list (ingredientId), or a typed name.
@Serializable
data class PantryAddRequest(val ingredientId: String? = null, val name: String? = null)

private fun pantryResponse(pantry: PantryRepository, ownerId: UUID): String {
    val items = pantry.list(ownerId).map { PantryItemResponse(it.ingredientId.toString(), it.name) }
    val summary = when (items.size) {
        0 -> "Nothing in your pantry yet. Add things you always keep on hand, like salt or olive oil. They'll still appear on your shopping list, without an amount."
        1 -> "1 item. It stays on your shopping list without an amount."
        else -> "${items.size} items. They stay on your shopping list without an amount."
    }
    return Json.encodeToString(PantryResponse(items, summary))
}

// GET/POST /api/pantry, DELETE /api/pantry/{ingredientId}. Every call returns the whole pantry.
class PantryHandlers(private val pantry: PantryRepository, private val ingredients: IngredientRepository) {
    fun list(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> = Ok(pantryResponse(pantry, user.id))

    fun add(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val request = try {
            Json.decodeFromString<PantryAddRequest>(ctx.readBody())
        } catch (e: Exception) {
            return Err(400, "INVALID_BODY", "Malformed request body")
        }
        if ((request.ingredientId == null) == (request.name == null)) {
            return Err(400, "INVALID_INPUT", "send either ingredientId or name")
        }
        val ingredientId = if (request.ingredientId != null) {
            val id = runCatching { UUID.fromString(request.ingredientId) }.getOrNull()
                ?: return Err(400, "INVALID_INPUT", "invalid ingredientId")
            ingredients.findById(id)?.id ?: return Err(404, "NOT_FOUND", "Ingredient not found")
        } else {
            val name = normalizeIngredientName(request.name!!)
                ?: return Err(400, "INVALID_INPUT", "name must not be empty")
            if (name.length > MAX_PANTRY_NAME_LENGTH) {
                return Err(400, "INVALID_INPUT", "name must be at most $MAX_PANTRY_NAME_LENGTH characters")
            }
            // Same resolution a recipe line gets, aliases included, so "white rice" finds "rice"
            // after a remembered merge.
            ingredients.findOrCreate(name).first
        }
        pantry.add(user.id, ingredientId)
        return Ok(pantryResponse(pantry, user.id))
    }

    fun remove(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = ctx.pathParams["ingredientId"]?.let { runCatching { UUID.fromString(it) }.getOrNull() }
            ?: return Err(400, "INVALID_INPUT", "invalid ingredient id")
        if (!pantry.remove(user.id, id)) return Err(404, "NOT_FOUND", "Not in your pantry")
        return Ok(pantryResponse(pantry, user.id))
    }
}
