package larder.api

import kotlinx.serialization.Serializable
import larder.db.MealPlanEntryRow
import larder.db.MealPlanRow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.math.BigDecimal

@Serializable
data class MealPlanEntryRequest(
    val recipeId: String,
    val label: String? = null,
    // At most one of these. `servings` is what the user wants to make; the API converts it to a
    // multiplier against the recipe's numeric servings. Neither means the recipe as written.
    val servings: Double? = null,
    val servingsMultiplier: Double? = null,
)

@Serializable
data class MealPlanEntryResponse(
    val id: String,
    val recipeId: String?,
    val recipeTitle: String,
    val label: String?,
    val servingsMultiplier: Double,
    // "6 servings" when the recipe has numeric servings (already scaled), otherwise "×1.5" when
    // scaled, otherwise the recipe's own servings text (may be null).
    val servingsDisplay: String?,
    val createdAt: String,
)

@Serializable
data class MealPlanListResponse(val entries: List<MealPlanEntryResponse>)

// Starts a fresh active plan. `replace` confirms archiving a non-empty current plan (without it
// the API answers 409); `fromPlanId` seeds the new plan with a past plan's entries ("Use again").
@Serializable
data class MealPlanStartRequest(val replace: Boolean = false, val fromPlanId: String? = null)

@Serializable
data class MealPlanSummaryResponse(
    val id: String,
    val datesDisplay: String,   // "Sep 18 – Sep 25, 2026"
    val summaryDisplay: String, // "Chili, Pancakes + 2 more"
    val entryCount: Int,
)

@Serializable
data class MealPlanHistoryResponse(val plans: List<MealPlanSummaryResponse>)

@Serializable
data class MealPlanDetailResponse(
    val id: String,
    val active: Boolean,
    val datesDisplay: String,
    val entries: List<MealPlanEntryResponse>,
)

private val DAY = DateTimeFormatter.ofPattern("MMM d", Locale.US)
private val DAY_YEAR = DateTimeFormatter.ofPattern("MMM d, yyyy", Locale.US)

// Server's zone (TZ, see docker-compose.yml). Year shown once when both ends share it.
fun formatPlanDates(start: Instant, end: Instant?): String {
    val zone = ZoneId.systemDefault()
    val s = start.atZone(zone).toLocalDate()
    if (end == null) return "Started ${DAY_YEAR.format(s)}"
    val e = end.atZone(zone).toLocalDate()
    return when {
        s == e -> DAY_YEAR.format(s)
        s.year == e.year -> "${DAY.format(s)} – ${DAY_YEAR.format(e)}"
        else -> "${DAY_YEAR.format(s)} – ${DAY_YEAR.format(e)}"
    }
}

fun MealPlanRow.toSummary(entries: List<MealPlanEntryRow>): MealPlanSummaryResponse {
    val titles = entries.map { it.toResponse().recipeTitle }
    val summary = when {
        titles.isEmpty() -> "No recipes"
        titles.size <= 3 -> titles.joinToString(", ")
        else -> "${titles.take(3).joinToString(", ")} + ${titles.size - 3} more"
    }
    return MealPlanSummaryResponse(id.toString(), formatPlanDates(createdAt, archivedAt), summary, entries.size)
}

fun MealPlanRow.toDetail(entries: List<MealPlanEntryRow>) =
    MealPlanDetailResponse(id.toString(), archivedAt == null, formatPlanDates(createdAt, archivedAt), entries.map { it.toResponse() })

// A soft-deleted recipe (only possible in an archived plan) keeps its place in history but
// comes back as "Chili (deleted)" with no recipeId, so there's nothing to link to.
fun MealPlanEntryRow.toResponse() = MealPlanEntryResponse(
    id = id.toString(),
    recipeId = if (recipeDeleted) null else recipeId.toString(),
    recipeTitle = if (recipeDeleted) "$recipeTitle (deleted)" else recipeTitle,
    label = label,
    servingsMultiplier = servingsMultiplier.toDouble(),
    servingsDisplay = when {
        recipeServings != null -> formatServings(recipeServings.multiply(servingsMultiplier))
        servingsMultiplier.compareTo(BigDecimal.ONE) != 0 -> "×${formatNumber(servingsMultiplier)}"
        else -> recipeServingsText
    },
    createdAt = createdAt.toString(),
)
