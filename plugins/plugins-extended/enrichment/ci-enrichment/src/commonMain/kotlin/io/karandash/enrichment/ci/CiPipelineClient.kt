package io.karandash.enrichment.ci

import io.karandash.http.PathSegmentEncoder
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.providers.common.fetchOrNull
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable

internal const val PRIVATE_TOKEN_HEADER = "PRIVATE-TOKEN"

/** GitLab header for a CI job token (`CI_JOB_TOKEN`). */
internal const val JOB_TOKEN_HEADER = "JOB-TOKEN"
private const val API_V4 = "/api/v4"

/** The summarizer reads only the last lines of a trace; 128 KiB is ample. */
private const val TRACE_TAIL_BYTES = 128 * 1024
private const val READ_CHUNK_BYTES = 16 * 1024

/** A CI system's API for the failed jobs of an MR's latest pipeline, with log snippets. */
internal interface CiJobsApi {
    /** Failed jobs from the API, at most [maxJobs]; empty when the API has none (the caller falls back to the MR). */
    suspend fun failedJobs(
        mr: MergeRequest,
        maxJobs: Int,
    ): List<FailedJob>

    fun close()
}

/**
 * HTTP client for the GitLab CI REST API.
 *
 * Fetches pipeline and job information to produce [FailedJob] enrichment; the client degrades gracefully when
 * config is incomplete (empty URL/projectId), returning empty results.
 */
internal class CiPipelineClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val projectId: String,
    private val logger: Logger = NoOpLogger,
) : CiJobsApi {
    private val cleanBase = baseUrl.trimEnd('/')
    private val encodedProject = PathSegmentEncoder.encode(projectId)
    private val json = KarandashJson.Default

    override suspend fun failedJobs(
        mr: MergeRequest,
        maxJobs: Int,
    ): List<FailedJob> {
        val pipelineId = fetchLatestPipelineId(mr.iid) ?: return emptyList()
        return fetchFailedJobs(pipelineId).take(maxJobs).map { apiJob ->
            FailedJob(
                name = apiJob.name,
                stage = apiJob.stage,
                jobUrl = apiJob.webUrl,
                errorSnippet = fetchJobTrace(apiJob.id)?.let { FailedJobSummarizer.extractSnippet(it) },
                failedAt = apiJob.finishedAt,
            )
        }
    }

    suspend fun fetchLatestPipelineId(mrIid: Int): Long? {
        if (cleanBase.isBlank() || projectId.isBlank()) return null
        return fetchOrNull("CI enrichment: failed to fetch pipelines for MR $mrIid", logger::warn) {
            val resp = httpClient.get("$cleanBase$API_V4/projects/$encodedProject/merge_requests/$mrIid/pipelines")
            if (!resp.status.isSuccess()) return null
            json.decodeFromString<List<GitLabPipeline>>(resp.bodyAsText()).firstOrNull()?.id
        }
    }

    suspend fun fetchFailedJobs(pipelineId: Long): List<GitLabJob> {
        if (cleanBase.isBlank() || projectId.isBlank()) return emptyList()
        return fetchOrNull("CI enrichment: failed to fetch jobs for pipeline $pipelineId", logger::warn) {
            val resp = httpClient.get(
                "$cleanBase$API_V4/projects/$encodedProject/pipelines/$pipelineId/jobs?scope[]=failed",
            )
            if (!resp.status.isSuccess()) return emptyList()
            json.decodeFromString<List<GitLabJob>>(resp.bodyAsText())
        }.orEmpty()
    }

    suspend fun fetchJobTrace(jobId: Long): String? {
        if (cleanBase.isBlank() || projectId.isBlank()) return null
        return fetchOrNull("CI enrichment: failed to fetch trace for job $jobId", logger::debug) {
            httpClient.prepareGet("$cleanBase$API_V4/projects/$encodedProject/jobs/$jobId/trace").execute { resp ->
                if (resp.status.isSuccess()) readTail(resp.bodyAsChannel()) else null
            }
        }
    }

    override fun close() {
        httpClient.close()
    }
}

/**
 * Streams the body into a [TRACE_TAIL_BYTES] ring buffer and keeps only its last bytes, so a huge trace never sits
 * in memory and no byte is copied more than once.
 */
internal suspend fun readTail(channel: ByteReadChannel): String {
    val ring = ByteArray(TRACE_TAIL_BYTES)
    val chunk = ByteArray(READ_CHUNK_BYTES)
    var total = 0L
    while (true) {
        val n = channel.readAvailable(chunk, 0, chunk.size)
        if (n < 0) break
        for (i in 0 until n) ring[((total + i) % TRACE_TAIL_BYTES).toInt()] = chunk[i]
        total += n
    }
    if (total <= TRACE_TAIL_BYTES) return ring.decodeToString(0, total.toInt())
    val start = (total % TRACE_TAIL_BYTES).toInt()
    return (ring.copyOfRange(start, TRACE_TAIL_BYTES) + ring.copyOfRange(0, start)).decodeToString()
}
