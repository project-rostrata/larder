package larder.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.RecipeRepository
import larder.ingredients.IngredientLineParser
import larder.ingredients.IngredientResolver
import larder.recipeimport.extractRecipeFromJson

private const val MAX_FILES = 200
private const val MAX_FILE_CHARS = 1_000_000

@Serializable
data class ImportFile(val name: String, val content: String)

@Serializable
data class RecipeFileImportRequest(val files: List<ImportFile>)

@Serializable
data class ImportedFileResult(val file: String, val recipeId: String, val title: String)

@Serializable
data class FailedFileResult(val file: String, val message: String)

@Serializable
data class RecipeFileImportResponse(
    val imported: List<ImportedFileResult>,
    val failed: List<FailedFileResult>,
    val summary: String, // ready to show: "Imported 3 recipes." / "Imported 2 of 3 files; 1 couldn't be imported."
)

// POST /api/recipes/import-files: recipe JSON files (e.g. Nextcloud Cookbook's recipe.json),
// sent as text by the browser. Each file is imported independently -- one bad file doesn't
// stop the rest -- and every import creates a new recipe (no de-duplication, by the human's
// choice). Same validate/resolve/persist path as manual create and URL import.
class RecipeFileImportHandler(
    private val recipes: RecipeRepository,
    private val parser: IngredientLineParser,
    private val resolver: IngredientResolver,
) {
    fun handle(ctx: RouteContext, user: AuthenticatedUser): ApiResult<String> {
        val request = try {
            Json.decodeFromString<RecipeFileImportRequest>(ctx.readBody())
        } catch (e: Exception) {
            return Err(400, "INVALID_BODY", "Malformed request body")
        }
        if (request.files.isEmpty() || request.files.size > MAX_FILES) {
            return Err(400, "INVALID_INPUT", "send 1 to $MAX_FILES files")
        }

        val imported = mutableListOf<ImportedFileResult>()
        val failed = mutableListOf<FailedFileResult>()
        for (file in request.files) {
            val name = file.name.take(200)
            if (file.content.length > MAX_FILE_CHARS) {
                failed += FailedFileResult(name, "File is too large")
                continue
            }
            val recipe = extractRecipeFromJson(file.content)
            if (recipe == null) {
                failed += FailedFileResult(name, "Not a recipe JSON file")
                continue
            }
            val recipeRequest = recipe.toRecipeRequest()
            validateRecipeRequest(recipeRequest)?.let {
                failed += FailedFileResult(name, it)
                continue
            }
            val lines = resolveIngredientLines(parser, resolver, recipeRequest.ingredients)
            val persisted = recipes.create(user.id, recipeRequest.toFields(), lines)
            imported += ImportedFileResult(name, persisted.recipe.id.toString(), persisted.recipe.title)
        }
        val summary = when {
            failed.isEmpty() -> if (imported.size == 1) "Imported 1 recipe." else "Imported ${imported.size} recipes."
            imported.isEmpty() -> if (failed.size == 1) "The file couldn't be imported." else "None of the ${failed.size} files could be imported."
            else -> "Imported ${imported.size} of ${request.files.size} files; ${failed.size} couldn't be imported."
        }
        return Ok(Json.encodeToString(RecipeFileImportResponse(imported, failed, summary)))
    }
}
