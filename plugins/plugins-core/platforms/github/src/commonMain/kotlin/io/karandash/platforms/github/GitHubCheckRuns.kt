package io.karandash.platforms.github

import io.karandash.api.platform.ResultAnnotation
import io.karandash.api.platform.ResultAnnotationLevel
import io.karandash.api.platform.ResultStatus
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.ProjectIdParser
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** GitHub accepts at most 50 annotations per check run request; the rest go in follow-up PATCHes, which append. */
internal const val CHECK_RUN_ANNOTATIONS_PER_REQUEST = 50
private const val MAX_SUMMARY = 65_535
private const val MAX_TITLE = 255

/**
 * Publishes [status] as a completed check run on its head commit: created with the first 50 annotations, the rest
 * appended 50 at a time. Needs a GitHub App installation token or the Actions `GITHUB_TOKEN` with `checks: write`;
 * GitHub refuses check runs from a personal access token.
 */
internal suspend fun GitHubClient.publishCheckRun(
    projectId: String,
    status: ResultStatus,
) {
    val (owner, repo) = ProjectIdParser.parsePair(projectId, GitHubPlatformService.PROJECT_ID_FORMAT)
    val runs = "$baseUrl/repos/${owner.encodeURLPathPart()}/${repo.encodeURLPathPart()}/check-runs"
    val batches = status.annotations.chunked(CHECK_RUN_ANNOTATIONS_PER_REQUEST).ifEmpty { listOf(emptyList()) }
    val created = sendJson(
        HttpMethod.Post,
        runs,
        buildJsonObject {
            put("name", status.name)
            put("head_sha", status.headSha)
            put("status", "completed")
            put("conclusion", if (status.passed) "success" else "failure")
            status.detailsUrl?.let { put("details_url", it) }
            put("output", checkRunOutput(status, batches.first()))
        },
    )
    if (batches.size == 1) return
    val id = (KarandashJson.Lenient.parseToJsonElement(created) as? JsonObject)?.get("id")?.let { (it as? JsonPrimitive)?.longOrNull }
        ?: error("GitHub check run created without an id")
    batches.drop(1).forEach { batch ->
        sendJson(HttpMethod.Patch, "$runs/$id", buildJsonObject { put("output", checkRunOutput(status, batch)) })
    }
}

private fun checkRunOutput(
    status: ResultStatus,
    annotations: List<ResultAnnotation>,
): JsonObject =
    buildJsonObject {
        put("title", status.title.take(MAX_TITLE))
        put("summary", status.summary.take(MAX_SUMMARY))
        putJsonArray("annotations") {
            annotations.forEach { annotation ->
                add(
                    buildJsonObject {
                        put("path", annotation.path)
                        put("start_line", annotation.line)
                        put("end_line", maxOf(annotation.endLine ?: annotation.line, annotation.line))
                        put("annotation_level", annotation.level.gitHubLevel())
                        put("title", annotation.title.take(MAX_TITLE))
                        put("message", annotation.message.take(MAX_SUMMARY))
                    },
                )
            }
        }
    }

private fun ResultAnnotationLevel.gitHubLevel(): String =
    when (this) {
        ResultAnnotationLevel.NOTICE -> "notice"
        ResultAnnotationLevel.WARNING -> "warning"
        ResultAnnotationLevel.FAILURE -> "failure"
    }
