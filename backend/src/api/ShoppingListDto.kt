package larder.api

import kotlinx.serialization.Serializable
import larder.db.ShoppingListDetail
import larder.db.ShoppingListSourceRow
import larder.shopping.Combiner
import larder.shopping.SourceLine
import larder.shopping.UnitInfo
import larder.shopping.formatAmount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class ShoppingListItemCreateRequest(val text: String)

@Serializable
data class ShoppingListItemUpdateRequest(val checked: Boolean? = null, val text: String? = null)

// Move the item to just before beforeItemId; null moves it to the end.
@Serializable
data class ShoppingListItemMoveRequest(val beforeItemId: String? = null)

@Serializable
data class ShoppingListMergeRequest(val itemIds: List<String>, val remember: Boolean = false)

@Serializable
data class ShoppingListSourceResponse(
    val recipeId: String?,
    val recipeTitle: String,
    val rawText: String,
    // This source's own scaled amount in its own unit ("6 cups"), or null when it had none.
    val amount: String?,
)

@Serializable
data class ShoppingListItemResponse(
    val id: String,
    val name: String,
    // Ready to render as one string: "4 1/8 cups flour", "2 cups + 3 cloves garlic", "salt".
    val display: String,
    // The same, in two parts for styling (amount emphasized): "4 1/8 cups" / "flour". The
    // amount is null for an item with no quantity. display == amount + " " + name.
    val amountDisplay: String?,
    // amountDisplay's pieces -- one normally, several for a merge whose units don't combine
    // ("3 cloves", "20 grams"). The UI keeps each piece on one line and only breaks between.
    val amountParts: List<String>,
    val nameDisplay: String,
    val checked: Boolean,
    // Null for a hand-added or unparsed item -- only parsed ingredients can go in the pantry.
    val ingredientId: String?,
    // In the owner's pantry: listed in the Pantry group, with no amount to buy.
    val inPantry: Boolean,
    // One ready-to-show line per contributing recipe, amount first: ["1 cup — Chili",
    // "2 tablespoons — Pancakes"].
    // Empty for a manual item.
    val sourceLines: List<String>,
    val sources: List<ShoppingListSourceResponse>,
)

@Serializable
data class ShoppingListGroupResponse(
    val title: String?,
    val note: String?,
    val items: List<ShoppingListItemResponse>,
)

@Serializable
data class ShoppingListResponse(
    val id: String,
    val name: String,
    val createdAt: String,
    val itemCount: Int,
    val checkedCount: Int,
    val createdDisplay: String,
    val progressDisplay: String,
    // Ready-made groups in display order: the main list (title null), then "Pantry". Empty
    // groups are omitted; checked items sit at the bottom of their own group.
    val groups: List<ShoppingListGroupResponse>,
    // Set only by a merge: what was (or couldn't be) remembered for future lists. Show as-is.
    val notice: String? = null,
)

// `list` is null when the current meal plan is empty; `message` then says what to do.
@Serializable
data class CurrentShoppingListResponse(val list: ShoppingListResponse?, val message: String?)

private val CREATED_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

// Server's zone -- set TZ on the container for local dates (see docker-compose.yml).
private fun formatCreated(at: Instant) = CREATED_FORMAT.format(at.atZone(ZoneId.systemDefault()))

private fun formatProgress(itemCount: Int, checkedCount: Int) = when {
    itemCount == 0 -> "No items"
    checkedCount == itemCount -> "All $itemCount checked"
    else -> "$checkedCount of $itemCount checked"
}


fun ShoppingListDetail.toResponse(combiner: Combiner, units: Map<java.util.UUID, UnitInfo>): ShoppingListResponse {
    val sourcesByItem = sources.groupBy { it.itemId }
    fun unitName(id: java.util.UUID?) = id?.let { units[it]?.name }
    return ShoppingListResponse(
        id = summary.id.toString(),
        name = summary.name,
        createdAt = summary.createdAt.toString(),
        itemCount = summary.itemCount,
        checkedCount = summary.checkedCount,
        createdDisplay = formatCreated(summary.createdAt),
        progressDisplay = formatProgress(summary.itemCount, summary.checkedCount),
        groups = items.map { item ->
            val itemSources = sourcesByItem[item.id].orEmpty()
            val inPantry = item.ingredientId != null && item.ingredientId in pantryIngredientIds
            val amountParts = when {
                // Pantry staples are on the list to check you have them, not to buy an amount.
                inPantry -> emptyList()
                item.quantity != null -> listOf(formatAmount(item.quantity, unitName(item.unitId)))
                // No single total -- e.g. a manual merge of cups and cloves. Show each part.
                item.ingredientId != null -> combiner.amounts(itemSources.map { it.toLine() }, item.ingredientId)
                    .map { formatAmount(it.quantity, unitName(it.unitId)) }
                else -> emptyList()
            }
            val amount = amountParts.takeIf { it.isNotEmpty() }?.joinToString(" + ")
            val display = listOfNotNull(amount, item.name).joinToString(" ")
            val sourceResponses = itemSources.map { src ->
                ShoppingListSourceResponse(
                    recipeId = src.recipeId?.toString(),
                    recipeTitle = src.recipeTitle,
                    rawText = src.rawText,
                    // A bare count ("3") reads better with the item's name: "3 eggs".
                    amount = src.quantity?.let { q ->
                        formatAmount(q, unitName(src.unitId)) + if (src.unitId == null) " ${item.name}" else ""
                    },
                )
            }
            ShoppingListItemResponse(
                id = item.id.toString(),
                name = item.name,
                display = display,
                amountDisplay = amount,
                amountParts = amountParts,
                nameDisplay = item.name,
                checked = item.checked,
                ingredientId = item.ingredientId?.toString(),
                inPantry = inPantry,
                sourceLines = sourceResponses.map { "${it.amount ?: it.rawText} — ${it.recipeTitle}" },
                sources = sourceResponses,
            )
        }.let(::groupForDisplay),
    )
}

// Items arrive in display order (unchecked first, then by sort order); splitting keeps that
// order within each group.
private fun groupForDisplay(items: List<ShoppingListItemResponse>): List<ShoppingListGroupResponse> {
    val (pantry, main) = items.partition { it.inPantry }
    return listOfNotNull(
        main.takeIf { it.isNotEmpty() }?.let { ShoppingListGroupResponse(null, null, it) },
        pantry.takeIf { it.isNotEmpty() }?.let {
            ShoppingListGroupResponse("Pantry", "Already in your pantry — check you have enough.", it)
        },
    )
}

fun ShoppingListSourceRow.toLine() =
    SourceLine(recipeId, recipeTitle, mealPlanEntryId, rawText, null, null, quantity, unitId)
