package larder.api

import kotlinx.serialization.Serializable
import larder.db.RecipeRow
import java.math.BigDecimal
import java.math.RoundingMode

// Display formatting lives in the API, not the frontend -- the UI renders these strings as-is.

fun formatMinutes(minutes: Int?): String? {
    if (minutes == null) return null
    val h = minutes / 60
    val m = minutes % 60
    return when {
        h == 0 -> "$m min"
        m == 0 -> "$h hr"
        else -> "$h hr $m min"
    }
}

// At most 2 decimal places, no trailing zeros: 4.0 -> "4", 1.5 -> "1.5", 20/3 -> "6.67".
fun formatNumber(value: BigDecimal): String =
    value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()

fun formatServings(count: BigDecimal): String {
    val n = formatNumber(count)
    return if (n == "1") "1 serving" else "$n servings"
}

@Serializable
data class RecipeDisplay(
    val servings: String?,
    val totalTime: String?,
    // Every present fact, labeled, in display order -- the recipe detail's meta line.
    val details: List<String>,
    // The notes as paragraphs (split on blank lines), ready to render one <p> each.
    val notes: List<String>,
)

fun RecipeRow.toDisplay(): RecipeDisplay {
    val servingsText = servings?.let(::formatServings) ?: servingsText
    val prep = formatMinutes(prepTimeMinutes)
    val cook = formatMinutes(cookTimeMinutes)
    val total = formatMinutes(totalTimeMinutes)
    return RecipeDisplay(
        servings = servingsText,
        totalTime = total,
        details = listOfNotNull(servingsText, prep?.let { "prep $it" }, cook?.let { "cook $it" }, total?.let { "total $it" }),
        notes = notes.orEmpty().split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() },
    )
}
