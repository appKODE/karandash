package io.karandash.diagnostics

import io.karandash.api.finding.FindingFeedback
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Complete diagnostic report written to JSON. Contains periodic resource
 * snapshots, pipeline events, and an optional summary (null when interrupted).
 */
@Serializable
data class DiagnosticReport(
    val snapshots: List<ResourceSnapshot> = emptyList(),
    val events: List<DiagnosticEvent> = emptyList(),
    val summary: DiagnosticSummary? = null,
    val context: PipelineContext? = null,
    val feedback: FeedbackDiagnostic? = null,
    /** How each rule went (ok, skipped and why, error), recorded after validation; empty before it ends. */
    val rules: List<RuleOutcomeDiagnostic> = emptyList(),
    /** The findings of the run, recorded after validation. */
    val findings: List<FindingDiagnostic> = emptyList(),
)

/**
 * Outcome of one rule, keyed by its camelCase id (config keys and rule ids alike).
 *
 * [status] is `ok` (ran), `skipped` ([reason], [category]), `error` (an AI provider error or a failed analyzer) or
 * `not_run` (configured, but neither ran nor reported a skip). Tokens and cost are set for AI rules that report them.
 */
@Serializable
data class RuleOutcomeDiagnostic(
    @SerialName("rule_id")
    val ruleId: String,
    val status: String,
    val reason: String? = null,
    val category: String? = null,
    @SerialName("from_cache")
    val fromCache: Boolean = false,
    @SerialName("finding_count")
    val findingCount: Int = 0,
    val model: String? = null,
    @SerialName("input_tokens")
    val inputTokens: Int? = null,
    @SerialName("output_tokens")
    val outputTokens: Int? = null,
    @SerialName("cost_usd")
    val costUsd: Double? = null,
)

/** One finding of the run; [file]/[line] for a file location, [commit] for a commit one. */
@Serializable
data class FindingDiagnostic(
    val rule: String,
    val severity: String,
    val message: String,
    val file: String? = null,
    val line: Int? = null,
    val commit: String? = null,
)

/**
 * A point-in-time capture of process resource usage.
 *
 * On native: [peakRssBytes] from `getrusage`, CPU from `ru_utime`/`ru_stime`.
 * On JVM: heap stats from `Runtime`, GC from MXBeans.
 */
@Serializable
data class ResourceSnapshot(
    /** Milliseconds since collector start. */
    @SerialName("timestamp_ms")
    val timestampMs: Long,
    /** Peak resident set size in bytes (native) or heap used (JVM). */
    @SerialName("peak_rss_bytes")
    val peakRssBytes: Long = 0,
    /** Current resident set size in bytes. */
    @SerialName("current_rss_bytes")
    val currentRssBytes: Long = 0,
    /** Total heap/memory allocated in bytes. JVM only; 0 on native. */
    @SerialName("memory_total_bytes")
    val memoryTotalBytes: Long = 0,
    /** Max heap capacity in bytes. JVM only; 0 on native. */
    @SerialName("memory_max_bytes")
    val memoryMaxBytes: Long = 0,
    /** Cumulative user-mode CPU time in milliseconds. */
    @SerialName("cpu_user_ms")
    val cpuUserTimeMs: Long = 0,
    /** Cumulative kernel-mode CPU time in milliseconds. */
    @SerialName("cpu_system_ms")
    val cpuSystemTimeMs: Long = 0,
    /** Total garbage collection count. */
    @SerialName("gc_count")
    val gcCount: Long = 0,
    /** Total garbage collection pause time in milliseconds. */
    @SerialName("gc_time_ms")
    val gcTimeMs: Long = 0,
    /** Number of active threads. */
    @SerialName("thread_count")
    val threadCount: Int = 0,
    /** Number of open file descriptors. */
    @SerialName("open_fds")
    val openFds: Int = 0,
)

/**
 * A timestamped event in the pipeline execution timeline.
 */
@Serializable
data class DiagnosticEvent(
    /** Milliseconds since collector start. */
    @SerialName("timestamp_ms")
    val timestampMs: Long,
    /** Event type: "stage.start", "stage.end", "http", "rule.start", "rule.end". */
    val type: String,
    /** Stage name, URL, rule ID, or module ID. */
    val name: String,
    /** Duration in milliseconds (set on end/completion events). */
    @SerialName("duration_ms")
    val durationMs: Long? = null,
    /** Additional key-value metadata. */
    val metadata: Map<String, String> = emptyMap(),
)

/**
 * Aggregated summary computed at pipeline completion.
 * Null in the report when the pipeline was interrupted before finishing.
 */
@Serializable
data class DiagnosticSummary(
    @SerialName("total_duration_ms")
    val totalDurationMs: Long,
    @SerialName("peak_memory_bytes")
    val peakMemoryBytes: Long,
    @SerialName("peak_current_rss_bytes")
    val peakCurrentRssBytes: Long = 0,
    @SerialName("cpu_user_ms")
    val totalCpuUserMs: Long,
    @SerialName("cpu_system_ms")
    val totalCpuSystemMs: Long,
    @SerialName("gc_count")
    val totalGcCount: Long,
    @SerialName("gc_time_ms")
    val totalGcTimeMs: Long,
    @SerialName("max_thread_count")
    val maxThreadCount: Int = 0,
    @SerialName("max_open_fds")
    val maxOpenFds: Int = 0,
    @SerialName("snapshot_count")
    val snapshotCount: Int,
    @SerialName("stage_timings")
    val stageTimings: Map<String, Long>,
    @SerialName("rule_timings")
    val ruleTimings: List<RuleDiagnostic>,
    val cache: CacheDiagnostic,
    @SerialName("http_requests")
    val httpRequests: List<HttpDiagnostic> = emptyList(),
    @SerialName("http_total_ms")
    val httpTotalMs: Long = 0,
    @SerialName("http_count")
    val httpCount: Int = 0,
    @SerialName("enrichment_timings")
    val enrichmentTimings: List<EnrichmentDiagnostic> = emptyList(),
    @SerialName("reporter_timings")
    val reporterTimings: List<ReporterDiagnostic> = emptyList(),
    @SerialName("finding_summary")
    val findingSummary: FindingSummaryDiagnostic? = null,
    /** The stage running when the summary was taken, else the last stage that started or ended. */
    @SerialName("last_active_stage")
    val lastActiveStage: String?,
    @SerialName("last_active_stage_duration_ms")
    val lastActiveStageDurationMs: Long? = null,
    @SerialName("client_lifecycle")
    val clientLifecycle: ClientLifecycleDiagnostic? = null,
    @SerialName("stage_resources")
    val stageResources: Map<String, StageResourceDelta> = emptyMap(),
    @SerialName("memory_growth_rate_bytes_per_sec")
    val memoryGrowthRateBytesPerSec: Long? = null,
    @SerialName("slowest_http_url")
    val slowestHttpUrl: String? = null,
    @SerialName("slowest_http_duration_ms")
    val slowestHttpDurationMs: Long? = null,
    @SerialName("http_error_count")
    val httpErrorCount: Int = 0,
    /** AI subsystem state of the run; null when no AI rule was configured. */
    val ai: AiDiagnostic? = null,
)

/**
 * AI subsystem diagnostics.
 *
 * @property funnel The review-comments funnel by stage (sampled, agreed, grounded, posted, singletons, refuted);
 *   empty when the run had no review comments.
 */
@Serializable
data class AiDiagnostic(
    val health: AiHealthDiagnostic,
    val funnel: Map<String, Int> = emptyMap(),
)

/**
 * Whether the AI provider answered this run.
 *
 * @property calls AI rules that reached the provider (answered or failed).
 * @property failed Of those, the calls that failed (timeout, HTTP error, connection).
 * @property reason The run's AI status line (`AI: unavailable — 12/12 calls failed`); null when there is none.
 * @property context Per AI rule, how its request used `ai.context.budget` (budget, used %, diff and per-tier tokens
 *   kept, cut and dropped, diff hunks cut).
 */
@Serializable
data class AiHealthDiagnostic(
    val calls: Int,
    val failed: Int,
    val reason: String? = null,
    val context: Map<String, String> = emptyMap(),
)

/** Per-rule diagnostic entry with start/end timestamps for concurrency analysis. */
@Serializable
data class RuleDiagnostic(
    @SerialName("rule_id")
    val ruleId: String,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("start_ms")
    val startMs: Long = 0,
    @SerialName("end_ms")
    val endMs: Long = 0,
    @SerialName("from_cache")
    val fromCache: Boolean,
    @SerialName("finding_count")
    val findingCount: Int,
    @SerialName("error_count")
    val errorCount: Int = 0,
    @SerialName("warning_count")
    val warningCount: Int = 0,
    @SerialName("info_count")
    val infoCount: Int = 0,
)

/**
 * Cache hit/miss summary. A hit is a rule whose findings came from a cache, including rules served whole by an
 * extension's incremental cache. [source] is the layer the run's `cache.state` came from: `local` (the cache dir),
 * `remote` (`cache.remote`, e.g. on a fresh CI runner) or `none`. [firstRun] is true when the run looked
 * for stored state and found none in any layer (the first run on an MR).
 */
@Serializable
data class CacheDiagnostic(
    val hits: Int,
    val misses: Int,
    val source: String = SOURCE_NONE,
    @SerialName("first_run") val firstRun: Boolean = false,
) {
    companion object {
        /** [source] when no stored state was found. */
        const val SOURCE_NONE = "none"
    }
}

/** Per-HTTP-request diagnostic entry. */
@Serializable
data class HttpDiagnostic(
    val url: String,
    val method: String,
    @SerialName("status_code")
    val statusCode: Int? = null,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("response_size_bytes")
    val responseSizeBytes: Long? = null,
    val error: String? = null,
)

/** HTTP client lifecycle diagnostic. */
@Serializable
data class ClientLifecycleDiagnostic(
    @SerialName("client_creates")
    val clientCreates: Int = 0,
    @SerialName("client_closes")
    val clientCloses: Int = 0,
)

/** Resource delta measured between stage start and end. */
@Serializable
data class StageResourceDelta(
    @SerialName("rss_start_bytes")
    val rssStartBytes: Long = 0,
    @SerialName("rss_end_bytes")
    val rssEndBytes: Long = 0,
    @SerialName("rss_delta_bytes")
    val rssDeltaBytes: Long = 0,
    @SerialName("thread_delta")
    val threadDelta: Int = 0,
    @SerialName("fd_delta")
    val fdDelta: Int = 0,
)

/** Per-enrichment-provider diagnostic entry. */
@Serializable
data class EnrichmentDiagnostic(
    @SerialName("provider_id")
    val providerId: String,
    @SerialName("duration_ms")
    val durationMs: Long,
    @SerialName("from_cache")
    val fromCache: Boolean,
    @SerialName("output_ids")
    val outputIds: List<String> = emptyList(),
)

/** Per-reporter diagnostic entry with format and output timing. */
@Serializable
data class ReporterDiagnostic(
    @SerialName("format_id")
    val formatId: String,
    @SerialName("output_id")
    val outputId: String,
    @SerialName("format_ms")
    val formatMs: Long,
    @SerialName("output_ms")
    val outputMs: Long,
)

/** Overall finding severity breakdown. */
@Serializable
data class FindingSummaryDiagnostic(
    val total: Int,
    val errors: Int,
    val warnings: Int,
    val infos: Int,
    val passed: Boolean,
)

/** Pipeline execution context - platform, build variant, config, MR metadata. */
@Serializable
data class PipelineContext(
    val platform: String? = null,
    @SerialName("build_variant")
    val buildVariant: String? = null,
    val os: String? = null,
    @SerialName("karandash_version")
    val karandashVersion: String? = null,
    @SerialName("config_source")
    val configSource: String? = null,
    @SerialName("rule_count")
    val ruleCount: Int = 0,
    /** Every rule of the run, camelCase: configured keys, rules that ran or were skipped, rules with findings. */
    @SerialName("rule_ids")
    val ruleIds: List<String> = emptyList(),
    @SerialName("reporter_ids")
    val reporterIds: List<String> = emptyList(),
    @SerialName("enrichment_provider_ids")
    val enrichmentProviderIds: List<String> = emptyList(),
    @SerialName("mr_url")
    val mrUrl: String? = null,
    @SerialName("mr_changed_lines")
    val mrChangedLines: Int? = null,
    @SerialName("mr_file_count")
    val mrFileCount: Int? = null,
    @SerialName("platform_confidence")
    val platformConfidence: String? = null,
    @SerialName("platform_source")
    val platformSource: String? = null,
    @SerialName("mr_iid")
    val mrIid: Int? = null,
    @SerialName("head_sha")
    val headSha: String? = null,
    /** The `extends` chain, base config first and this config last, credentials redacted; empty without `extends`. */
    @SerialName("config_chain")
    val configChain: List<String> = emptyList(),
)

/**
 * Reviewer feedback on the findings karandash posted to the MR, read back once per run: counted only, never used to
 * suppress a finding. Keys are camelCase (`feedback.falsePositives`), unlike the snake_case summary.
 */
@Serializable
data class FeedbackDiagnostic(
    val falsePositives: Int = 0,
    val byRule: Map<String, Int> = emptyMap(),
    val items: List<FindingFeedback> = emptyList(),
) {
    companion object {
        /** The counts of [items]. */
        fun of(items: List<FindingFeedback>): FeedbackDiagnostic {
            val falsePositives = items.filter { it.reaction == FindingFeedback.FALSE_POSITIVE }
            return FeedbackDiagnostic(falsePositives.size, falsePositives.groupingBy { it.rule }.eachCount(), items)
        }
    }
}
