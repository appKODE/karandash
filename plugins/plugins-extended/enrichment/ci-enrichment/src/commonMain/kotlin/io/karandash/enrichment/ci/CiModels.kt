package io.karandash.enrichment.ci

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FailedJob(
    val name: String,
    val stage: String? = null,
    val jobUrl: String? = null,
    val errorSnippet: String? = null,
    val failedAt: String? = null,
)

@Serializable
data class UncoveredRange(
    val startLine: Int,
    val endLine: Int,
    val branchKind: String? = null,
)

@Serializable
internal data class GitLabPipeline(
    val id: Long,
    val status: String,
)

@Serializable
internal data class GitLabJob(
    val id: Long,
    val name: String,
    val stage: String? = null,
    @SerialName("web_url")
    val webUrl: String? = null,
    @SerialName("finished_at")
    val finishedAt: String? = null,
    val status: String = "",
)

@Serializable
internal data class GitHubWorkflowRuns(
    @SerialName("workflow_runs")
    val workflowRuns: List<GitHubWorkflowRun> = emptyList(),
)

@Serializable
internal data class GitHubWorkflowRun(
    val id: Long,
    val name: String? = null,
    val conclusion: String? = null,
)

@Serializable
internal data class GitHubJobs(
    val jobs: List<GitHubJob> = emptyList(),
)

@Serializable
internal data class GitHubJob(
    val id: Long,
    val name: String,
    val conclusion: String? = null,
    @SerialName("html_url")
    val htmlUrl: String? = null,
    @SerialName("completed_at")
    val completedAt: String? = null,
    val steps: List<GitHubStep> = emptyList(),
)

@Serializable
internal data class GitHubStep(
    val name: String,
    val conclusion: String? = null,
)
