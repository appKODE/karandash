package io.karandash.detect

import kotlinx.serialization.Serializable

/**
 * Confidence level of platform detection
 */
enum class DetectionConfidence {
    HIGH, // From explicit config or CI env vars
    MEDIUM, // From git remote parsing
    LOW // From a provider score without a CI environment (a heuristic)
}

/**
 * Result of platform detection containing all necessary information
 * to connect to the platform API
 *
 * @property platform Platform identifier: "github", "gitlab", "bitbucket", "azure-devops", "gerrit", "gitea", "aws-codecommit"
 * @property projectId Project identifier in format "owner/repo" or platform-specific format
 * @property mrId Merge/Pull request ID/number
 * @property baseUrl API base URL for the platform
 * @property token Authentication token from environment
 * @property confidence Confidence level of the detection
 * @property source Source of detection: "ci:github-actions", "git-remote:github", "explicit", etc.
 */
@Serializable
data class DetectionResult(
    val platform: String,
    val projectId: String?,
    val mrId: String?,
    val baseUrl: String?,
    val token: String?,
    val confidence: DetectionConfidence,
    val source: String,
) {
    /** Masks [token] so a logged or interpolated result never prints it. */
    override fun toString(): String =
        "DetectionResult(platform=$platform, projectId=$projectId, mrId=$mrId, baseUrl=$baseUrl, " +
            "token=${token?.let { "***" }}, confidence=$confidence, source=$source)"
}
