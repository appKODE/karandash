package io.karandash.platforms.gitlab

import io.karandash.api.platform.ResultStatus
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** GitLab cuts a commit status description at 255 characters. */
private const val MAX_DESCRIPTION = 255

/**
 * Publishes [status] as a commit status named [ResultStatus.name] on its head commit (shown in the MR pipeline
 * widget). A commit status has no annotations: findings stay in the comments or the `codequality` report.
 */
internal suspend fun GitLabClient.publishCommitStatus(
    projectId: String,
    status: ResultStatus,
) {
    sendJson(
        HttpMethod.Post,
        "$baseUrl/api/v4/projects/${encodeProjectId(projectId)}/statuses/${status.headSha.encodeURLPathPart()}",
        buildJsonObject {
            put("state", if (status.passed) "success" else "failed")
            put("name", status.name)
            put("description", status.title.take(MAX_DESCRIPTION))
            status.detailsUrl?.let { put("target_url", it) }
        },
    )
}
