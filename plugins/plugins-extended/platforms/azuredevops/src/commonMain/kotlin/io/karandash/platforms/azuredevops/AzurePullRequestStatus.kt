package io.karandash.platforms.azuredevops

import io.karandash.api.platform.ResultStatus
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

private const val MAX_DESCRIPTION = 255

/**
 * Publishes [status] as a pull request status with context `karandash/<name>` (a branch policy can require it). A
 * status has no annotations: findings stay in the comment threads.
 */
internal suspend fun AzureDevOpsClient.publishPullRequestStatus(
    organization: String,
    project: String,
    repositoryId: String,
    pullRequestId: Int,
    status: ResultStatus,
) {
    val url = "$baseUrl/${organization.encodeURLPathPart()}/${project.encodeURLPathPart()}/_apis/git/repositories/" +
        "${repositoryId.encodeURLPathPart()}/pullRequests/$pullRequestId/statuses?api-version=7.1"
    sendJson(
        HttpMethod.Post,
        url,
        buildJsonObject {
            put("state", if (status.passed) "succeeded" else "failed")
            put("description", status.title.take(MAX_DESCRIPTION))
            putJsonObject("context") {
                put("name", status.name)
                put("genre", "karandash")
            }
            status.detailsUrl?.let { put("targetUrl", it) }
        },
    )
}
