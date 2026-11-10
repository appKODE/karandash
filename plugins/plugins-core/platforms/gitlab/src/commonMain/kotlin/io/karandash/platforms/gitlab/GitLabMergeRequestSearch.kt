@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gitlab

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.support.KarandashJson
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** Query string of `GET /projects/:id/merge_requests` for a search in any state, most recently updated first. */
internal fun gitLabSearchQuery(
    text: String?,
    author: String?,
    limit: Int,
): String =
    buildString {
        append("state=all&order_by=updated_at&per_page=").append(limit)
        text?.takeIf { it.isNotBlank() }?.let { append("&search=").append(it.encodeURLParameter()) }
        author?.takeIf { it.isNotBlank() }?.let { append("&author_username=").append(it.encodeURLParameter()) }
    }

/** The hits of a GitLab merge request list body; an unreadable body is an [IllegalStateException]. */
internal fun parseGitLabSearchHits(body: String): List<MergeRequestSearchHit> {
    val root = try {
        KarandashJson.Lenient.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw IllegalStateException("Unreadable merge request list: ${e.message}", e)
    }
    val list = root as? JsonArray ?: error("Unreadable merge request list: not a JSON list")
    return list.filterIsInstance<JsonObject>().mapNotNull { mr ->
        val iid = mr.string("iid") ?: return@mapNotNull null
        MergeRequestSearchHit(
            id = iid,
            title = mr.string("title").orEmpty(),
            state = mr.string("state").orEmpty(),
            author = (mr["author"] as? JsonObject)?.string("username"),
            webUrl = mr.string("web_url"),
        )
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
