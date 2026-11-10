package io.karandash.api.platform

import kotlinx.serialization.Serializable

/**
 * Implemented by a platform service that shows a run's verdict outside MR comments, on the head commit: a GitHub check
 * run, a Bitbucket Code Insights report, an Azure DevOps pull request status, a GitLab commit status. Declared by
 * [PlatformOperation.RESULT_STATUS].
 */
public fun interface ResultStatusPublisher {
    /**
     * Publishes [status] for merge request [mrId] of [projectId]. A platform that cannot place [ResultStatus.annotations]
     * shows the verdict and the summary only. Throws on an API failure.
     */
    public suspend fun publishResultStatus(
        projectId: String,
        mrId: String,
        status: ResultStatus,
    )
}

/**
 * One run's result for [ResultStatusPublisher].
 *
 * @property name The check, report or status context name (e.g. `karandash`), stable across runs so a rerun replaces it.
 * @property passed The verdict.
 * @property title One line, e.g. `2 errors, 1 warning`.
 * @property summary Longer markdown text, cut to the platform's limit by the platform.
 * @property headSha The commit the result belongs to.
 * @property annotations Findings with a file and line.
 * @property detailsUrl Optional link to the full report.
 */
@Serializable
public data class ResultStatus(
    val name: String,
    val passed: Boolean,
    val title: String,
    val summary: String,
    val headSha: String,
    val annotations: List<ResultAnnotation> = emptyList(),
    val detailsUrl: String? = null,
)

/** A finding placed on [path] at [line] (to [endLine]) of the head commit. */
@Serializable
public data class ResultAnnotation(
    val path: String,
    val line: Int,
    val endLine: Int? = null,
    val level: ResultAnnotationLevel,
    val title: String,
    val message: String,
)

/** Severity of a [ResultAnnotation]. */
@Serializable
public enum class ResultAnnotationLevel {
    NOTICE,
    WARNING,
    FAILURE,
}
