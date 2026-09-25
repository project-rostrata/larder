package larder.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeRepository

@Serializable
private data class RecipesListResponse(val recipes: List<RecipeResponse>)

class RecipesListHandler(private val recipes: RecipeRepository) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val tag = ctx.query["tag"]?.takeIf { it.isNotBlank() }
        val rows = recipes.list(user.id, tag)
        val response = rows.map { it.toResponse(recipes.findIngredients(it.id, user.id)) }
        return Ok(Json.encodeToString(RecipesListResponse(response)))
    }
}
