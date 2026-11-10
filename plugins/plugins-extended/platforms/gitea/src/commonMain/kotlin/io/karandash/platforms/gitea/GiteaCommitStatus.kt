package io.karandash.platforms.gitea

import io.karandash.api.platform.ResultStatus
import io.karandash.providers.common.PathSegmentEncoder
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Publishes [status] as a commit status with context [ResultStatus.name] on its head commit
 * (`POST /repos/{owner}/{repo}/statuses/{sha}`, shown in the pull request checks). A commit status has no
 * annotations: findings stay in the comments.
 */
internal suspend fun GiteaClient.publishCommitStatus(
    owner: String,
    repo: String,
    status: ResultStatus,
) {
    val path = "${PathSegmentEncoder.encode(owner)}/${PathSegmentEncoder.encode(repo)}/statuses/${status.headSha.encodeURLPathPart()}"
    sendJson(
        HttpMethod.Post,
        "$baseUrl/api/v1/repos/$path",
        buildJsonObject {
            put("state", if (status.passed) "success" else "failure")
            put("context", status.name)
            put("description", status.title)
            status.detailsUrl?.let { put("target_url", it) }
        },
    )
}
