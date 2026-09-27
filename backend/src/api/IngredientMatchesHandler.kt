package larder.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.IngredientRepository

@Serializable
data class IngredientMatchResponse(val id: String, val name: String, val aliases: List<String>)

@Serializable
data class IngredientMatchesResponse(
    val matches: List<IngredientMatchResponse>,
    val summary: String, // ready to show: "3 ingredients have other names that match them."
)

// GET /api/ingredients/matches: the learned matches (canonical ingredient <- other spellings),
// for the unlinked /?view=matches page. Ingredients are global vocabulary, so this isn't
// owner-scoped (same exception as merge-into).
class IngredientMatchesHandler(private val ingredients: IngredientRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val matches = ingredients.listWithAliases().map { (row, aliases) ->
            IngredientMatchResponse(row.id.toString(), row.name, aliases)
        }
        val summary = when (matches.size) {
            0 -> "No matches yet. Merging shopping-list items with \u201cRemember for future lists\u201d adds them here."
            1 -> "1 ingredient has other names that match it."
            else -> "${matches.size} ingredients have other names that match them."
        }
        return Ok(Json.encodeToString(IngredientMatchesResponse(matches, summary)))
    }
}
