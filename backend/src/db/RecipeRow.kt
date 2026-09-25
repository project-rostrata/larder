package larder.db

import java.math.BigDecimal
import java.time.Instant
import java.util.UUID

data class RecipeRow(
    val id: UUID,
    val ownerId: UUID,
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
    val createdAt: Instant,
    val updatedAt: Instant,
    val deletedAt: Instant?,
)
