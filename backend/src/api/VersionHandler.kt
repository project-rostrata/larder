package larder.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import larder.db.Database

@Serializable
private data class VersionResponse(val version: String)

class VersionHandler(private val database: Database) {
    fun handle(ctx: RouteContext): ApiResult<String> {
        val version = database.queryOne("SELECT version()") { it.getString(1) }
        return Ok(Json.encodeToString(VersionResponse(version)))
    }
}
