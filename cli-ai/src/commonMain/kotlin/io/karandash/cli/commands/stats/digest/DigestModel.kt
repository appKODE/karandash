package io.karandash.cli.commands.stats.digest

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The part of a `diagnostic.json` the digest reads. Every field is optional, so files from older releases (no
 * `rules`, no `findings`) still load and count toward what they do carry.
 */
@Serializable
data class DigestReport(
    val snapshots: List<DigestStamp> = emptyList(),
    val events: List<DigestStamp> = emptyList(),
    val summary: DigestSummary? = null,
    val context: DigestContext? = null,
    val rules: List<DigestRule> = emptyList(),
    val findings: List<DigestFinding> = emptyList(),
    /** Optional, `feedback.falsePositives`: findings reviewers marked as false positives. */
    val feedback: DigestFeedback? = null,
)

@Serializable
data class DigestStamp(
    @SerialName("timestamp_ms")
    val timestampMs: Long = 0,
)

@Serializable
data class DigestSummary(
    @SerialName("total_duration_ms")
    val totalDurationMs: Long = 0,
    val cache: DigestCache? = null,
    @SerialName("finding_summary")
    val findingSummary: DigestFindingSummary? = null,
    @SerialName("rule_timings")
    val ruleTimings: List<DigestRuleTiming> = emptyList(),
)

@Serializable
data class DigestCache(
    val hits: Int = 0,
    val misses: Int = 0,
)

@Serializable
data class DigestFindingSummary(
    val total: Int = 0,
    val passed: Boolean = true,
)

@Serializable
data class DigestRuleTiming(
    @SerialName("rule_id")
    val ruleId: String,
    @SerialName("finding_count")
    val findingCount: Int = 0,
)

@Serializable
data class DigestContext(
    @SerialName("mr_url")
    val mrUrl: String? = null,
    @SerialName("mr_iid")
    val mrIid: Int? = null,
    @SerialName("rule_ids")
    val ruleIds: List<String> = emptyList(),
)

@Serializable
data class DigestRule(
    @SerialName("rule_id")
    val ruleId: String,
    val status: String = "",
    val category: String? = null,
    @SerialName("from_cache")
    val fromCache: Boolean = false,
    @SerialName("finding_count")
    val findingCount: Int = 0,
    val model: String? = null,
    @SerialName("cost_usd")
    val costUsd: Double? = null,
)

@Serializable
data class DigestFinding(
    val rule: String,
    val message: String = "",
    val file: String? = null,
)

@Serializable
data class DigestFeedback(
    val falsePositives: Int = 0,
)
