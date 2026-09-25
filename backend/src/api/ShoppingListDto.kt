package larder.api

import kotlinx.serialization.Serializable
import larder.db.ShoppingListDetail
import larder.db.ShoppingListSourceRow
import larder.db.ShoppingListSummaryRow
import larder.shopping.Combiner
import larder.shopping.SourceLine
import larder.shopping.UnitInfo
import larder.shopping.formatAmount
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Serializable
data class ShoppingListCreateRequest(
    val name: String? = null,
    val recipeIds: List<String>? = null,
    val fromMealPlan: Boolean = false,
)

@Serializable
data class ShoppingListItemCreateRequest(val text: String)

@Serializable
data class ShoppingListItemUpdateRequest(val checked: Boolean? = null, val text: String? = null)

@Serializable
data class ShoppingListMergeRequest(val itemIds: List<String>, val remember: Boolean = false)

@Serializable
data class ShoppingListSummaryResponse(
    val id: String,
    val name: String,
    val createdAt: String,
    val itemCount: Int,
    val checkedCount: Int,
    val createdDisplay: String,  // "Sep 24, 2026"
    val progressDisplay: String, // "3 of 7 checked"
)

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
    // Ready to render: "4 1/8 cups flour", "garlic (2 cups + 3 cloves)", "salt to taste".
    val display: String,
    val checked: Boolean,
    // "Chili: 1 1/2 cups · Pancakes: 2 tablespoons", or null for a manual item.
    val sourcesDisplay: String?,
    val sources: List<ShoppingListSourceResponse>,
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
    val items: List<ShoppingListItemResponse>,
    // Set only by a merge: what was (or couldn't be) remembered for future lists. Show as-is.
    val notice: String? = null,
)

@Serializable
data class ShoppingListsResponse(val shoppingLists: List<ShoppingListSummaryResponse>)

private val CREATED_FORMAT = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

// Server's zone -- set TZ on the container for local dates (see docker-compose.yml).
private fun formatCreated(at: Instant) = CREATED_FORMAT.format(at.atZone(ZoneId.systemDefault()))

private fun formatProgress(itemCount: Int, checkedCount: Int) = when {
    itemCount == 0 -> "No items"
    checkedCount == itemCount -> "All $itemCount checked"
    else -> "$checkedCount of $itemCount checked"
}

fun ShoppingListSummaryRow.toResponse() = ShoppingListSummaryResponse(
    id = id.toString(),
    name = name,
    createdAt = createdAt.toString(),
    itemCount = itemCount,
    checkedCount = checkedCount,
    createdDisplay = formatCreated(createdAt),
    progressDisplay = formatProgress(itemCount, checkedCount),
)

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
        items = items.map { item ->
            val itemSources = sourcesByItem[item.id].orEmpty()
            val display = when {
                item.quantity != null -> "${formatAmount(item.quantity, unitName(item.unitId))} ${item.name}"
                // No single total -- e.g. a manual merge of cups and cloves. Show each part.
                item.ingredientId != null -> {
                    val parts = combiner.amounts(itemSources.map { it.toLine() }, item.ingredientId)
                    if (parts.isEmpty()) item.name
                    else "${item.name} (${parts.joinToString(" + ") { formatAmount(it.quantity, unitName(it.unitId)) }})"
                }
                else -> item.name
            }
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
                checked = item.checked,
                sourcesDisplay = sourceResponses.takeIf { it.isNotEmpty() }
                    ?.joinToString(" · ") { "${it.recipeTitle}: ${it.amount ?: it.rawText}" },
                sources = sourceResponses,
            )
        },
    )
}

fun ShoppingListSourceRow.toLine() =
    SourceLine(recipeId, recipeTitle, mealPlanEntryId, rawText, null, null, quantity, unitId)
