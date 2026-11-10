@file:OptIn(ExperimentalSerializationApi::class)

package io.karandash.enrichment.ci

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonNames

@Serializable
internal data class CiFailedJobsSubConfig(
    val enabled: Boolean = true,
    @SerialName("max-jobs") @JsonNames("maxJobs")
    val maxJobs: Int = 10,
)

@Serializable
internal data class CiCoverageGapsSubConfig(
    val enabled: Boolean = true,
    @SerialName("artifact-patterns") @JsonNames("artifactPatterns")
    val artifactPatterns: List<String> = listOf("**/lcov.info", "**/*jacoco.xml", "**/jacoco-aggregated.xml"),
)

@Serializable
internal data class CiEnrichmentConfig(
    /** `gitlab` or `github`; unset: `github` under GitHub Actions (`GITHUB_ACTIONS=true`), else `gitlab`. */
    val platform: String? = null,
    val url: String = "",
    val token: String? = null,
    @SerialName("project-id") @JsonNames("projectId")
    val projectId: String = "",
    @SerialName("failed-jobs") @JsonNames("failedJobs")
    val failedJobs: CiFailedJobsSubConfig = CiFailedJobsSubConfig(),
    @SerialName("coverage-gaps") @JsonNames("coverageGaps")
    val coverageGaps: CiCoverageGapsSubConfig = CiCoverageGapsSubConfig(),
)
