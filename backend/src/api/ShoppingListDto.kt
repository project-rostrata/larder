package larder.api

import kotlinx.serialization.Serializable
import larder.db.ShoppingListDetail
import larder.db.ShoppingListSourceRow
import larder.db.ShoppingListSummaryRow
import larder.shopping.Combiner
import larder.shopping.SourceLine
import larder.shopping.UnitInfo
import larder.shopping.formatAmount

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
data class ShoppingListMergeRequest(val itemIds: List<String>)

@Serializable
data class ShoppingListSummaryResponse(
    val id: String,
    val name: String,
    val createdAt: String,
    val itemCount: Int,
    val checkedCount: Int,
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
    val sources: List<ShoppingListSourceResponse>,
)

@Serializable
data class ShoppingListResponse(
    val id: String,
    val name: String,
    val createdAt: String,
    val itemCount: Int,
    val checkedCount: Int,
    val items: List<ShoppingListItemResponse>,
)

@Serializable
data class ShoppingListsResponse(val shoppingLists: List<ShoppingListSummaryResponse>)

fun ShoppingListSummaryRow.toResponse() =
    ShoppingListSummaryResponse(id.toString(), name, createdAt.toString(), itemCount, checkedCount)

fun ShoppingListDetail.toResponse(combiner: Combiner, units: Map<java.util.UUID, UnitInfo>): ShoppingListResponse {
    val sourcesByItem = sources.groupBy { it.itemId }
    fun unitName(id: java.util.UUID?) = id?.let { units[it]?.name }
    return ShoppingListResponse(
        id = summary.id.toString(),
        name = summary.name,
        createdAt = summary.createdAt.toString(),
        itemCount = summary.itemCount,
        checkedCount = summary.checkedCount,
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
            ShoppingListItemResponse(
                id = item.id.toString(),
                name = item.name,
                display = display,
                checked = item.checked,
                sources = itemSources.map { src ->
                    ShoppingListSourceResponse(
                        recipeId = src.recipeId?.toString(),
                        recipeTitle = src.recipeTitle,
                        rawText = src.rawText,
                        amount = src.quantity?.let { formatAmount(it, unitName(src.unitId)) },
                    )
                },
            )
        },
    )
}

fun ShoppingListSourceRow.toLine() =
    SourceLine(recipeId, recipeTitle, mealPlanEntryId, rawText, null, null, quantity, unitId)
