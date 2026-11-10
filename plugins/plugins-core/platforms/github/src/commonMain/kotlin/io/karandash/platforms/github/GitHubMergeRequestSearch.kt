@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.github

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.support.KarandashJson
import io.ktor.http.encodeURLParameter
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** The `q` of `GET /search/issues` for PRs of [owner]/[repo] matching [text] and/or by [author]. */
internal fun gitHubSearchQuery(
    owner: String,
    repo: String,
    text: String?,
    author: String?,
): String =
    listOfNotNull("repo:$owner/$repo", "is:pr", text?.takeIf { it.isNotBlank() }, author?.takeIf { it.isNotBlank() }?.let { "author:$it" })
        .joinToString(" ")
        .encodeURLParameter()

/** The hits of a GitHub issue search body (`items`); a merged PR has a non-null `pull_request.merged_at`. */
internal fun parseGitHubSearchHits(body: String): List<MergeRequestSearchHit> {
    val root = try {
        KarandashJson.Lenient.parseToJsonElement(body)
    } catch (e: SerializationException) {
        throw IllegalStateException("Unreadable pull request search: ${e.message}", e)
    }
    val items = (root as? JsonObject)?.get("items") as? JsonArray ?: error("Unreadable pull request search: no items")
    return items.filterIsInstance<JsonObject>().mapNotNull { pr ->
        val number = pr.string("number") ?: return@mapNotNull null
        val merged = ((pr["pull_request"] as? JsonObject)?.get("merged_at") ?: JsonNull) != JsonNull
        val state = when {
            pr.string("state") == "open" -> MergeRequestSearchHit.OPENED
            merged -> MergeRequestSearchHit.MERGED
            else -> MergeRequestSearchHit.CLOSED
        }
        MergeRequestSearchHit(
            id = number,
            title = pr.string("title").orEmpty(),
            state = state,
            author = (pr["user"] as? JsonObject)?.string("login"),
            webUrl = pr.string("html_url"),
        )
    }
}

private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
