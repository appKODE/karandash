package io.karandash.enrichment.ci

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

/**
 * GitHub Actions REST client: the failed jobs of the workflow runs for the pull request's head commit, each with the
 * tail of its log (`/actions/jobs/{id}/logs` redirects to blob storage; the token stays on the API host).
 */
internal class GitHubActionsClient(
    private val httpClient: HttpClient,
    baseUrl: String,
    private val repository: String,
    private val logger: Logger = NoOpLogger,
) : CiJobsApi {
    private val repoBase = "${baseUrl.trimEnd('/')}/repos/$repository"
    private val json = KarandashJson.Default

    override suspend fun failedJobs(
        mr: MergeRequest,
        maxJobs: Int,
    ): List<FailedJob> {
        val sha = mr.diffRefs?.headSha ?: mr.commits.lastOrNull()?.sha ?: return emptyList()
        val runs = get<GitHubWorkflowRuns>("$repoBase/actions/runs?head_sha=$sha&per_page=$PAGE_SIZE", "runs for $sha")
            ?.workflowRuns
            .orEmpty()
            .filter { it.conclusion == CONCLUSION_FAILURE }
        return runs
            .flatMap { run ->
                get<GitHubJobs>("$repoBase/actions/runs/${run.id}/jobs?filter=latest&per_page=$PAGE_SIZE", "jobs of run ${run.id}")
                    ?.jobs
                    .orEmpty()
                    .filter { it.conclusion == CONCLUSION_FAILURE }
                    .map { run to it }
            }.take(maxJobs)
            .map { (run, job) ->
                FailedJob(
                    name = job.name,
                    stage = job.steps.firstOrNull { it.conclusion == CONCLUSION_FAILURE }?.name ?: run.name,
                    jobUrl = job.htmlUrl,
                    errorSnippet = fetchLog(job.id)?.let { FailedJobSummarizer.extractSnippet(it) },
                    failedAt = job.completedAt,
                )
            }
    }

    private suspend inline fun <reified T> get(
        url: String,
        what: String,
    ): T? =
        fetchOrNull("CI enrichment: failed to fetch GitHub Actions $what in $repository", logger::warn) {
            val resp = httpClient.get(url)
            if (resp.status.isSuccess()) json.decodeFromString<T>(resp.bodyAsText()) else null
        }

    private suspend fun fetchLog(jobId: Long): String? =
        fetchOrNull("CI enrichment: failed to fetch log for GitHub job $jobId", logger::debug) {
            httpClient.prepareGet("$repoBase/actions/jobs/$jobId/logs").execute { resp ->
                if (resp.status.isSuccess()) readTail(resp.bodyAsChannel()) else null
            }
        }

    override fun close() = httpClient.close()

    private companion object {
        const val CONCLUSION_FAILURE = "failure"
        const val PAGE_SIZE = 100
    }
}
