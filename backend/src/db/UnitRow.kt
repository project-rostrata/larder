package larder.db

import java.math.BigDecimal
import java.util.UUID

data class UnitRow(
    val id: UUID,
    val name: String,
    val abbreviation: String?,
    val dimension: String,
    val toBaseFactor: BigDecimal?,
    val aliases: List<String>,
)
