package io.karandash.platforms.github

import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull

/**
 * Reads the PR number from the GitHub Actions event payload at `GITHUB_EVENT_PATH`: `pull_request.number`,
 * else the top-level `number`. Covers events whose `GITHUB_REF` is not `refs/pull/N/merge`
 * (`pull_request_target`, `pull_request_review`, ...).
 */
internal object GitHubEventPayload {
    const val ENV_VAR = "GITHUB_EVENT_PATH"

    suspend fun pullRequestNumber(
        path: String?,
        logger: Logger,
    ): String? {
        if (path.isNullOrBlank()) return null
        return try {
            val text = withContext(Dispatchers.IO) { SystemFileSystem.source(Path(path)).buffered().use { it.readString() } }
            val root = KarandashJson.Default.parseToJsonElement(text).jsonObject
            val number = (root["pull_request"] as? JsonObject)?.get("number") ?: root["number"]
            (number as? JsonPrimitive)?.longOrNull?.toString()
        } catch (e: IOException) {
            logger.warn("GitHub: cannot read $ENV_VAR '$path': ${e.message}")
            null
        } catch (e: IllegalArgumentException) {
            // SerializationException is an IllegalArgumentException; so is a payload that is not an object.
            logger.warn("GitHub: $ENV_VAR '$path' is not a JSON event payload: ${e.message}")
            null
        }
    }
}
