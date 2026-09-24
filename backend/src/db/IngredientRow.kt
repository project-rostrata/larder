package larder.db

import java.util.UUID

data class IngredientRow(val id: UUID, val name: String, val pluralName: String?)
