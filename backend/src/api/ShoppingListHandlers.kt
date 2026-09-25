package larder.api

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.IngredientRepository
import larder.db.MealPlanRepository
import larder.db.RecipeSelection
import larder.db.ShoppingListItemRow
import larder.db.ShoppingListRepository
import larder.shopping.Combiner
import larder.shopping.Rational
import java.util.UUID

private const val MAX_ITEM_TEXT_LENGTH = 200

private fun uuidOrNull(raw: String?) = raw?.let { runCatching { UUID.fromString(it) }.getOrNull() }

// Unit/conversion data is small and read per request rather than cached, so edits to the
// global vocabulary (e.g. a new unit_conversions row) take effect immediately.
private fun combinerFor(lists: ShoppingListRepository) = Combiner(lists.units(), lists.conversions())

private fun respondWithList(
    lists: ShoppingListRepository,
    listId: UUID,
    ownerId: UUID,
    notice: String? = null,
): ApiResult<String> {
    val detail = lists.find(listId, ownerId) ?: return Err(404, "NOT_FOUND", "Shopping list not found")
    val units = lists.units()
    return Ok(Json.encodeToString(detail.toResponse(Combiner(units, lists.conversions()), units).copy(notice = notice)))
}

private fun quoted(names: List<String>) = names.joinToString(", ") { "“$it”" }

// The Shopping tab: the current meal plan's list, built on first view after any plan change
// (plan edits reset it -- see resetPlanShoppingList). No active plan or an empty one gives
// `list: null` and a message to show instead.
class CurrentShoppingListHandler(
    private val lists: ShoppingListRepository,
    private val mealPlan: MealPlanRepository,
) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val plan = mealPlan.activePlan(user.id)
        val entries = if (plan == null) emptyList() else mealPlan.list(user.id)
        if (plan == null || entries.isEmpty()) {
            return Ok(Json.encodeToString(CurrentShoppingListResponse(null, "Your meal plan is empty. Add recipes to it from the Recipes tab.")))
        }
        val listId = lists.findIdForPlan(plan.id, user.id) ?: run {
            val selections = entries.map { RecipeSelection(it.recipeId, Rational.approximate(it.servingsMultiplier), it.id) }
            val items = combinerFor(lists).combine(lists.gatherLines(user.id, selections))
            lists.createForPlan(user.id, plan.id, "Shopping list", items)
        }
        val detail = lists.find(listId, user.id) ?: return Err(404, "NOT_FOUND", "Shopping list not found")
        val units = lists.units()
        val list = detail.toResponse(Combiner(units, lists.conversions()), units)
        return Ok(Json.encodeToString(CurrentShoppingListResponse(list, null)))
    }
}

// Item endpoints all return the whole updated list, so a client never has to re-derive
// ordering, counts, or display text after a change.
class ShoppingListItemHandlers(
    private val lists: ShoppingListRepository,
    private val ingredients: IngredientRepository,
) {
    private fun ownedList(ctx: RouteContext, user: AuthenticatedUser): Pair<UUID?, Err?> {
        val id = uuidOrNull(ctx.pathParams["id"]) ?: return null to Err(400, "INVALID_INPUT", "invalid shopping list id")
        lists.find(id, user.id) ?: return null to Err(404, "NOT_FOUND", "Shopping list not found")
        return id to null
    }

    private fun validText(raw: String?): Pair<String?, Err?> {
        val text = raw?.trim()
        if (text.isNullOrEmpty()) return null to Err(400, "INVALID_INPUT", "text must not be empty")
        if (text.length > MAX_ITEM_TEXT_LENGTH) return null to Err(400, "INVALID_INPUT", "text must be at most $MAX_ITEM_TEXT_LENGTH characters")
        return text to null
    }

    fun add(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val (listId, err) = ownedList(ctx, user); if (err != null) return err
        val request = try { Json.decodeFromString<ShoppingListItemCreateRequest>(ctx.readBody()) }
            catch (e: Exception) { return Err(400, "INVALID_BODY", "Malformed request body") }
        val (text, textErr) = validText(request.text); if (textErr != null) return textErr
        lists.addManualItem(listId!!, text!!)
        return respondWithList(lists, listId, user.id)
    }

    fun update(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val (listId, err) = ownedList(ctx, user); if (err != null) return err
        val itemId = uuidOrNull(ctx.pathParams["itemId"]) ?: return Err(400, "INVALID_INPUT", "invalid item id")
        val request = try { Json.decodeFromString<ShoppingListItemUpdateRequest>(ctx.readBody()) }
            catch (e: Exception) { return Err(400, "INVALID_BODY", "Malformed request body") }
        if (request.checked == null && request.text == null) return Err(400, "INVALID_INPUT", "send checked and/or text")
        val text = if (request.text != null) validText(request.text).let { (t, e) -> if (e != null) return e; t } else null
        if (!lists.updateItem(listId!!, itemId, request.checked, text)) return Err(404, "NOT_FOUND", "Item not found")
        return respondWithList(lists, listId, user.id)
    }

    fun move(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = uuidOrNull(ctx.pathParams["id"]) ?: return Err(400, "INVALID_INPUT", "invalid shopping list id")
        val detail = lists.find(id, user.id) ?: return Err(404, "NOT_FOUND", "Shopping list not found")
        val itemId = uuidOrNull(ctx.pathParams["itemId"]) ?: return Err(400, "INVALID_INPUT", "invalid item id")
        val request = try { Json.decodeFromString<ShoppingListItemMoveRequest>(ctx.readBody()) }
            catch (e: Exception) { return Err(400, "INVALID_BODY", "Malformed request body") }
        val before = request.beforeItemId?.let { uuidOrNull(it) ?: return Err(400, "INVALID_INPUT", "invalid beforeItemId") }
        val itemIds = detail.items.map { it.id }.toSet()
        if (itemId !in itemIds) return Err(404, "NOT_FOUND", "Item not found")
        if (before == itemId) return Err(400, "INVALID_INPUT", "cannot move an item before itself")
        if (before != null && before !in itemIds) return Err(404, "NOT_FOUND", "Item not found: $before")
        lists.moveItem(id, itemId, before)
        return respondWithList(lists, id, user.id)
    }

    fun delete(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val (listId, err) = ownedList(ctx, user); if (err != null) return err
        val itemId = uuidOrNull(ctx.pathParams["itemId"]) ?: return Err(400, "INVALID_INPUT", "invalid item id")
        if (!lists.deleteItem(listId!!, itemId)) return Err(404, "NOT_FOUND", "Item not found")
        return respondWithList(lists, listId, user.id)
    }

    // The first id survives. Its quantity is recomputed from every merged source; if the units
    // can't all be combined it's left without a single total and displays each part instead.
    // With remember=true, the other items' ingredients are also folded into the survivor's
    // (IngredientRepository.mergeInto), so future lists combine them automatically. Ingredients
    // are global, so this applies to every user's recipes.
    fun merge(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val id = uuidOrNull(ctx.pathParams["id"]) ?: return Err(400, "INVALID_INPUT", "invalid shopping list id")
        val detail = lists.find(id, user.id) ?: return Err(404, "NOT_FOUND", "Shopping list not found")
        val request = try { Json.decodeFromString<ShoppingListMergeRequest>(ctx.readBody()) }
            catch (e: Exception) { return Err(400, "INVALID_BODY", "Malformed request body") }
        val ids = request.itemIds.map { uuidOrNull(it) ?: return Err(400, "INVALID_INPUT", "invalid item id: $it") }.distinct()
        if (ids.size < 2) return Err(400, "INVALID_INPUT", "itemIds must contain at least 2 distinct ids")
        val byId = detail.items.associateBy { it.id }
        val items = ids.map { byId[it] ?: return Err(404, "NOT_FOUND", "Item not found: $it") }

        val survivor = items.first()
        val allSources = detail.sources.filter { src -> items.any { it.id == src.itemId } }.map { it.toLine() }
        val parts = combinerFor(lists).amounts(allSources, survivor.ingredientId)
        val single = parts.singleOrNull()
        lists.merge(id, survivor, items.drop(1), single?.quantity, single?.unitId, items.all { it.checked })
        val notice = if (request.remember) remember(survivor, items.drop(1)) else null
        return respondWithList(lists, id, user.id, notice)
    }

    // Returns the notice describing what was (or couldn't be) remembered.
    private fun remember(survivor: ShoppingListItemRow, others: List<ShoppingListItemRow>): String {
        val targetId = survivor.ingredientId
            ?: return "Merged. “${survivor.name}” isn't a recognized ingredient, so there's nothing to remember."
        val target = ingredients.findById(targetId)?.name ?: survivor.name
        val unrecognized = others.filter { it.ingredientId == null }.map { it.name }
        val learned = others.mapNotNull { it.ingredientId }.distinct().filter { it != targetId }
            .mapNotNull { otherId -> ingredients.findById(otherId)?.also { ingredients.mergeInto(otherId, targetId) }?.name }
        val sameIngredient = others.any { it.ingredientId == targetId }
        val parts = buildList {
            if (learned.isNotEmpty()) add("Future lists will combine ${quoted(learned)} into “$target”.")
            if (unrecognized.isNotEmpty()) add("${quoted(unrecognized)} isn't a recognized ingredient, so it wasn't remembered.")
            if (learned.isEmpty() && unrecognized.isEmpty() && sameIngredient) {
                add("These are already the same ingredient (“$target”); they were listed separately because their units don't convert.")
            }
        }
        return parts.joinToString(" ").ifEmpty { "Merged." }
    }
}
