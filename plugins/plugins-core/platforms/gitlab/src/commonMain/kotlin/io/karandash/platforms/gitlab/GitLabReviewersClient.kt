package io.karandash.platforms.gitlab

import io.ktor.client.request.get
import io.ktor.client.request.parameter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * MR reviewer management on top of [api]'s connection and auth. GitLab replaces the whole reviewer list on
 * `PUT /merge_requests/:iid` with `reviewer_ids`, so each change reads the current reviewers fresh (uncached)
 * and writes the merged list. Stateless; build one per call. Usernames match case-insensitively, as GitLab's do.
 *
 * The read-then-write is not atomic: a reviewer change made by someone else between the two calls is overwritten.
 */
class GitLabReviewersClient(
    private val api: GitLabClient,
) {
    /** Add [usernames] to the MR's reviewers; fails without updating when a username is unknown. */
    suspend fun addReviewers(
        projectId: String,
        mrIid: Int,
        usernames: List<String>,
    ) {
        val added = usernames.map { userId(it) }
        setReviewers(projectId, mrIid, (currentReviewers(projectId, mrIid).map { it.id } + added).distinct())
    }

    /** Remove [usernames] from the MR's reviewers; names that are not reviewers are ignored. */
    suspend fun removeReviewers(
        projectId: String,
        mrIid: Int,
        usernames: List<String>,
    ) {
        val kept = currentReviewers(projectId, mrIid)
            .filterNot { reviewer -> usernames.any { it.equals(reviewer.username, ignoreCase = true) } }
            .map { it.id }
        setReviewers(projectId, mrIid, kept)
    }

    private suspend fun currentReviewers(
        projectId: String,
        mrIid: Int,
    ): List<GitLabUser> {
        val label = "GET /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid"
        val url = "${api.baseUrl}/api/v4/projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid"
        val response = api.send { get(url) { api.authorize(this) } }
        return api.decodeResponse<GitLabMergeRequest>(response, label).reviewers
    }

    private suspend fun userId(username: String): Long {
        val response = api.send {
            get("${api.baseUrl}/api/v4/users") {
                api.authorize(this)
                parameter("username", username)
            }
        }
        val users = api.decodeResponse<List<GitLabUser>>(response, "GET /users?username=$username")
        return requireNotNull(
            users.firstOrNull { it.username.equals(username, ignoreCase = true) }
        ) { "GitLab user '$username' not found" }.id
    }

    private suspend fun setReviewers(
        projectId: String,
        mrIid: Int,
        ids: List<Long>,
    ) = api.updateMergeRequest(projectId, mrIid, JsonObject(mapOf("reviewer_ids" to JsonArray(ids.map { JsonPrimitive(it) }))))
}
