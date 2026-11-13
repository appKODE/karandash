package io.karandash.enrichment.localgit

import io.karandash.api.PluginContext
import io.karandash.api.cache.KarandashCache
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.platform.SubprocessBudget
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.enrichment.EnrichmentKeys
import kotlinx.atomicfu.atomic
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/**
 * Enrichment provider that contributes local git repository statistics.
 *
 * Provides [ENRICHMENT_FILE_FREQUENCY] (`git log --name-only`, via [FileFrequencyAnalyzer]) and
 * [ENRICHMENT_CONTRIBUTOR_STATS] (`git log` authors, via [ContributorAnalyzer]) for the MR's changed
 * files over the configured period. An ID is omitted when git is unavailable or yields no data;
 * rules degrade gracefully when enrichment data is absent.
 */
class LocalGitEnrichmentProvider : EnrichmentProviderModule {
    override val id: String = ID
    override val name: String = "Local Git Enrichment"
    override val version: String = "1.0.0"
    override val dslName: String = "localGit"
    override val configKey: String = "local-git"
    override val defaultEnabled: Boolean = false
    override val defaultPriority: Int = 200
    override val description: String =
        "Contributes local git repository statistics (file frequency, contributor stats)."

    private var logger: Logger = NoOpLogger
    private val cache = atomic<KarandashCache?>(null)
    private val workingDirectory = atomic<String>("")
    private val noDataReason = atomic<String?>(null)
    private val subprocessBudgetMaxCalls = atomic<Int>(SubprocessBudget.DEFAULT_MAX_CALLS)
    private val subprocessBudgetMaxWallMillis = atomic<Long>(SubprocessBudget.DEFAULT_MAX_WALL_MILLIS)

    override val providedEnrichment: Set<String> =
        setOf(
            ENRICHMENT_FILE_FREQUENCY,
            ENRICHMENT_CONTRIBUTOR_STATS,
        )

    override suspend fun initialize(context: PluginContext): Boolean {
        logger = context.logger
        workingDirectory.value = context.workingDirectory
        if (context.cacheProvider.enabled) {
            cache.value = context.cacheProvider.getStringCache("enrichment:local-git")
        }
        val budget = resolveSubprocessBudget(context.pluginConfig)
        subprocessBudgetMaxCalls.value = budget.maxCalls
        subprocessBudgetMaxWallMillis.value = budget.maxWallMillis
        return true
    }

    override fun skipReason(): String? =
        if (workingDirectory.value.isBlank()) "no working directory: no local git history" else noDataReason.value

    override suspend fun shutdown() {
        // No resources to release
    }

    override suspend fun enrich(
        mr: MergeRequest,
        config: JsonElement,
        upstreamContext: EnrichmentContext,
    ): Map<String, JsonElement> {
        val c = cache.value
        if (c == null) return doEnrich(mr, config)
        val deps = buildDependencies(mr, config)
        val cached = c.getOrPut(CACHE_KEY, deps) {
            val result = doEnrich(mr, config)
            KarandashJson.Default.encodeToString(enrichmentSerializer, result)
        }
        return KarandashJson.Default.decodeFromString(enrichmentSerializer, cached)
    }

    /**
     * Runs `git log --name-only` and `git log` authors (both `--since=<period>`) for the MR's changed
     * files: `{"frequencies": { path: count }, "period"}` under [ENRICHMENT_FILE_FREQUENCY] and
     * `{"contributors": [{name, email, commits}], "period"}` under [ENRICHMENT_CONTRIBUTOR_STATS].
     *
     * Returns empty when git is unavailable, the working directory is
     * unset, no commits fall inside the window, or the MR has no changes.
     */
    private suspend fun doEnrich(
        mr: MergeRequest,
        config: JsonElement
    ): Map<String, JsonElement> {
        if (workingDirectory.value.isBlank() || mr.changes.isEmpty()) return emptyMap()

        val period = resolvePeriod(config)
        val frequencyBudget = sliceBudget(subprocessBudgetMaxCalls.value, subprocessBudgetMaxWallMillis.value)
        val contributorBudget = sliceBudget(subprocessBudgetMaxCalls.value, subprocessBudgetMaxWallMillis.value)
        val paths = mr.changes.map { it.path }
        logger.debug("Local git enrichment: analyzing ${paths.size} file(s) over $period")
        val counts = FileFrequencyAnalyzer.analyze(workingDirectory.value, paths, period, budget = frequencyBudget, logger = logger)
        warnIfExhausted("file frequency", frequencyBudget)
        val contributors = ContributorAnalyzer.analyze(workingDirectory.value, paths, period, budget = contributorBudget, logger = logger)
        warnIfExhausted("contributor stats", contributorBudget)
        if (counts.isEmpty() && contributors.isEmpty()) {
            logger.debug("Local git enrichment: no frequency or contributor data produced")
            noDataReason.value = whyNoData()
            return emptyMap()
        }
        return buildMap {
            if (counts.isNotEmpty()) {
                put(ENRICHMENT_FILE_FREQUENCY, fileFrequencyJson(counts, period))
            }
            if (contributors.isNotEmpty()) put(ENRICHMENT_CONTRIBUTOR_STATS, ContributorAnalyzer.toJson(contributors, period))
        }
    }

    /** Why git gave nothing, when it is not just a quiet history: git cannot run, or the clone is shallow. */
    private suspend fun whyNoData(): String? {
        // GitRunner turns a git that cannot start (or a shell `git: not found`) into null, silently: say why once
        if (GitCliHelper.run(null, listOf("--version")) == null) {
            logger.warn("Local git enrichment: `git --version` failed, git is not installed or not on PATH; no local git history")
            return "no data: git not available"
        }
        if (ShallowCloneDetector.isShallow(workingDirectory.value, logger = logger)) {
            logger.warn("Local git enrichment: shallow clone, history is cut; set GIT_DEPTH: 0 (or a larger depth) for local git history")
            return "no data: shallow clone"
        }
        return null
    }

    private fun warnIfExhausted(
        analyzer: String,
        budget: SubprocessBudget
    ) {
        if (budget.hasRejected) {
            logger.warn("Local git enrichment: $analyzer subprocess budget exhausted, data may be partial")
        }
    }

    private fun resolveSubprocessBudget(config: JsonElement): SubprocessBudget {
        val obj = (config as? JsonObject) ?: return SubprocessBudget.default()
        val inner = (obj["subprocessBudget"] as? JsonObject)
            ?: (obj["subprocess-budget"] as? JsonObject)
            ?: return SubprocessBudget.default()
        val maxCalls = (inner["maxCalls"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: (inner["max-calls"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull()
            ?: SubprocessBudget.DEFAULT_MAX_CALLS
        val maxWallMillis = (inner["maxWallMillis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: (inner["max-wall-millis"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            ?: SubprocessBudget.DEFAULT_MAX_WALL_MILLIS
        return SubprocessBudget(
            maxCalls = maxCalls.coerceAtLeast(0),
            maxWallMillis = maxWallMillis.coerceAtLeast(0L),
        )
    }

    /** Read `period` from the provider config (camelCase or kebab-case). */
    private fun resolvePeriod(config: JsonElement): String {
        val obj = (config as? JsonObject) ?: return FileFrequencyAnalyzer.DEFAULT_PERIOD
        val inner = (obj["fileFrequency"] as? JsonObject)
            ?: (obj["file-frequency"] as? JsonObject)
            ?: obj
        val primitive = inner[CONFIG_KEY_PERIOD] as? JsonPrimitive
        val value = primitive?.contentOrNull
        return if (value.isNullOrBlank()) FileFrequencyAnalyzer.DEFAULT_PERIOD else value
    }

    private fun buildDependencies(
        mr: MergeRequest,
        config: JsonElement
    ): Map<String, String> =
        mapOf(
            "commits" to mr.commits.joinToString(",") { it.sha },
            "changes" to mr.changes.joinToString(",") { it.path },
            CONFIG_KEY_PERIOD to resolvePeriod(config),
        )

    companion object {
        /** Number of analyzers sharing the configured subprocess budget; each gets an equal slice. */
        private const val ANALYZER_COUNT = 2

        /** One analyzer's share of the configured budget (calls rounded up so a non-zero budget stays usable). */
        internal fun sliceBudget(
            maxCalls: Int,
            maxWallMillis: Long
        ): SubprocessBudget =
            SubprocessBudget(
                maxCalls = (maxCalls + ANALYZER_COUNT - 1) / ANALYZER_COUNT,
                maxWallMillis = maxWallMillis / ANALYZER_COUNT,
            )

        /** JSON key of the `{ path: count }` map inside the [ENRICHMENT_FILE_FREQUENCY] value. */
        const val KEY_FREQUENCIES = "frequencies"

        /** JSON key of the analysis period inside the [ENRICHMENT_FILE_FREQUENCY] value. */
        const val KEY_PERIOD = "period"

        /**
         * The [ENRICHMENT_FILE_FREQUENCY] value: `{"frequencies": { path: count }, "period": period}`.
         * Read back by [LocalGitEnrichmentContextEnricher].
         */
        fun fileFrequencyJson(
            counts: Map<String, Int>,
            period: String,
        ): JsonElement =
            buildJsonObject {
                putJsonObject(KEY_FREQUENCIES) { counts.forEach { (path, count) -> put(path, count) } }
                put(KEY_PERIOD, period)
            }

        const val ID = "local-git-enrichment"
        const val ENRICHMENT_FILE_FREQUENCY = EnrichmentKeys.LOCAL_GIT_FILE_FREQUENCY
        const val ENRICHMENT_CONTRIBUTOR_STATS = EnrichmentKeys.LOCAL_GIT_CONTRIBUTOR_STATS
        private const val CACHE_KEY = "local-git-enrichment"
        private const val CONFIG_KEY_PERIOD = KEY_PERIOD
        private val enrichmentSerializer = MapSerializer(String.serializer(), JsonElement.serializer())
    }
}
