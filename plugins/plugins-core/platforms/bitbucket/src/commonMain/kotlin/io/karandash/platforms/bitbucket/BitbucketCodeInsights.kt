package io.karandash.platforms.bitbucket

import io.karandash.api.platform.ResultAnnotationLevel
import io.karandash.api.platform.ResultStatus
import io.ktor.http.HttpMethod
import io.ktor.http.encodeURLPathPart
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Code Insights takes at most 100 annotations per request and 1000 per report. */
internal const val INSIGHTS_ANNOTATIONS_PER_REQUEST = 100
private const val MAX_ANNOTATIONS = 1000
private const val MAX_DETAILS = 2000
private const val MAX_SUMMARY = 450

/**
 * Publishes [status] as a Code Insights report `karandash-<name>` on its head commit (replaced on a rerun), then its
 * annotations 100 at a time.
 */
internal suspend fun BitbucketClient.publishCodeInsights(
    workspace: String,
    repoSlug: String,
    status: ResultStatus,
) {
    val reportId = "karandash-${status.name}"
    val report = "$baseUrl/repositories/${workspace.encodeURLPathPart()}/${repoSlug.encodeURLPathPart()}" +
        "/commit/${status.headSha.encodeURLPathPart()}/reports/${reportId.encodeURLPathPart()}"
    sendJson(
        HttpMethod.Put,
        report,
        buildJsonObject {
            put("title", status.name)
            put("details", "${status.title}\n\n${status.summary}".take(MAX_DETAILS))
            put("report_type", "BUG")
            put("reporter", "karandash")
            put("result", if (status.passed) "PASSED" else "FAILED")
            status.detailsUrl?.let { put("link", it) }
        },
    )
    status.annotations.take(MAX_ANNOTATIONS).withIndex().chunked(INSIGHTS_ANNOTATIONS_PER_REQUEST).forEach { batch ->
        val body = buildJsonArray {
            batch.forEach { (index, annotation) ->
                add(
                    buildJsonObject {
                        put("external_id", "$reportId-${index + 1}")
                        put("annotation_type", if (annotation.level == ResultAnnotationLevel.FAILURE) "BUG" else "CODE_SMELL")
                        put("summary", "${annotation.title}: ${annotation.message}".take(MAX_SUMMARY))
                        put("severity", annotation.level.insightsSeverity())
                        put("path", annotation.path)
                        put("line", annotation.line)
                    },
                )
            }
        }
        sendJson(HttpMethod.Post, "$report/annotations", body)
    }
}

private fun ResultAnnotationLevel.insightsSeverity(): String =
    when (this) {
        ResultAnnotationLevel.NOTICE -> "LOW"
        ResultAnnotationLevel.WARNING -> "MEDIUM"
        ResultAnnotationLevel.FAILURE -> "HIGH"
    }
