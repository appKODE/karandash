package io.karandash.plugins.ai.core

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.module.ExtensionStateStore
import io.karandash.api.support.rethrowCancellation
import io.karandash.cache.cas.CasCache
import io.karandash.cache.cas.asStateStore
import io.karandash.duration.KarandashDuration
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.config.CanaryRuleStats
import io.karandash.plugins.ai.core.config.GraceUsageState
import io.karandash.plugins.ai.core.research.DefectModelState
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.state.snapshot.SnapshotPersistence
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer

/**
 * Persists AI module state via [CasCache] (namespace `"state"`, key prefix `"ai_"`).
 *
 * All per-MR state is stored as a single [AiMrState] blob. Individual load/save
 * operations follow a load-modify-save pattern, which is safe because AI state
 * blobs are small (<50 KB) and updated infrequently.
 *
 * TTL is forwarded to CAS as milliseconds. A [KarandashDuration.NEVER] value
 * is stored with `Long.MAX_VALUE / 2` so the index entry never expires.
 *
 * All reads are fault-tolerant and return empty defaults on missing or corrupt entries.
 *
 * @param casCache State store for the `"state"` namespace; its [ExtensionStateStore.ttl] is how long each MR's state is kept.
 * @param readCaches When false (`--no-cache`), snapshot, response and findings caches load empty;
 *   saves still merge with the stored state so feedback is kept. Defaults to [ExtensionStateStore.reuseCached].
 */
@OptIn(ExperimentalKarandashApi::class)
class AiStatePersistence(
    private val casCache: ExtensionStateStore,
    private val logger: Logger = NoOpLogger,
    private val readCaches: Boolean = casCache.reuseCached,
) : SnapshotPersistence {
    constructor(
        casCache: CasCache,
        ttl: KarandashDuration = DEFAULT_TTL,
        logger: Logger = NoOpLogger,
        readCaches: Boolean = true,
    ) : this(casCache.asStateStore(ttl, readCaches), logger)

    val ttl: KarandashDuration get() = casCache.ttl

    // -- Feedback ---------------------------------------------------------------

    /**
     * Loads the feedback store for a specific MR.
     *
     * @param mrKey The MR key.
     * @return The persisted [FeedbackStore], or an empty store if not found or corrupt.
     */
    fun loadFeedback(mrKey: String): FeedbackStore {
        val state = loadState(mrKey)
        return FeedbackStore.fromEntries(state.feedback.entries, state.feedback.accepted)
    }

    /**
     * Saves the feedback store for a specific MR.
     *
     * @param mrKey The MR key.
     * @param store The [FeedbackStore] to persist.
     */
    fun saveFeedback(
        mrKey: String,
        store: FeedbackStore,
    ) {
        val state = loadState(mrKey)
        val updated = state.copy(feedback = FeedbackStoreData(entries = store.toSerializable(), accepted = store.acceptedAnchors()))
        saveState(mrKey, updated)
    }

    /**
     * Brings [mrKey]'s state in from the shared remote cache when it is not stored locally
     * ([ExtensionStateStore.fetch]), so a fresh CI runner sees the previous run's snapshot. Best-effort.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: CAS and remote I/O can throw various exceptions
    suspend fun prefetch(mrKey: String) {
        try {
            casCache.fetch(casKey(mrKey))
        } catch (e: Exception) {
            // boundary: a failed prefetch only means a cache miss
            e.rethrowCancellation()
            logger.warn("AI state prefetch failed for $mrKey: ${e::class.simpleName}: ${e.message}")
        }
    }

    // -- Snapshot ---------------------------------------------------------------

    /**
     * Loads a per-MR snapshot (map of file path to diff hash).
     *
     * @param mrKey The MR key.
     * @return The persisted snapshot map, or an empty map if not found or corrupt.
     */
    override fun loadSnapshot(mrKey: String): Map<String, Long> = if (readCaches) loadState(mrKey).snapshot else emptyMap()

    /**
     * Saves a per-MR snapshot.
     *
     * @param mrKey The MR key.
     * @param snapshot Map of file path to diff hash.
     */
    override fun saveSnapshot(
        mrKey: String,
        snapshot: Map<String, Long>,
    ) {
        val state = loadState(mrKey)
        saveState(mrKey, state.copy(snapshot = snapshot))
    }

    // -- Response cache ---------------------------------------------------------

    /**
     * Loads cached AI responses for a specific MR.
     *
     * @param mrKey The MR key.
     * @return Map of cache key to [CachedResponse].
     */
    fun loadResponseCache(mrKey: String): Map<String, CachedResponse> =
        if (!readCaches) emptyMap() else loadState(mrKey).responseCache.mapValues { (_, dto) -> dto.toCachedResponse() }

    /**
     * Saves cached AI responses for a specific MR; a no-op under `--no-cache`, which neither reads nor writes replies.
     *
     * @param mrKey The MR key.
     * @param entries Map of cache key to [CachedResponse] to persist.
     */
    fun saveResponseCache(
        mrKey: String,
        entries: Map<String, CachedResponse>,
    ) {
        if (!readCaches) return
        val state = loadState(mrKey)
        val updated = state.copy(
            responseCache = entries.mapValues { (_, r) -> CachedResponseDto.fromCachedResponse(r) },
        )
        saveState(mrKey, updated)
    }

    // -- Findings cache ---------------------------------------------------------

    /**
     * Loads the per-file AI findings cache for a specific MR.
     *
     * @param mrKey The MR key.
     * @return Map of file path to list of [Finding]s, or empty map if missing or corrupt.
     */
    fun loadFindingsCache(mrKey: String): Map<String, List<Finding>> = if (readCaches) loadState(mrKey).findingsCache else emptyMap()

    /**
     * Saves per-file AI findings for a specific MR.
     *
     * @param mrKey The MR key.
     * @param findings Map of file path to list of [Finding]s to persist.
     */
    fun saveFindingsCache(
        mrKey: String,
        findings: Map<String, List<Finding>>,
    ) {
        val state = loadState(mrKey)
        saveState(mrKey, state.copy(findingsCache = findings))
    }

    /**
     * Atomically saves a snapshot and its associated findings for a specific MR.
     *
     * Prefer this over calling [saveSnapshot] and [saveFindingsCache] separately to
     * avoid a torn write where the snapshot is updated but the findings are not (or
     * vice-versa) if the process is interrupted between the two calls.
     *
     * @param mrKey The MR key.
     * @param snapshot Map of file path to diff hash.
     * @param findings Map of file path (or [io.karandash.plugins.ai.core.incremental.IncrementalValidator.MR_LEVEL_KEY])
     *   to list of [Finding]s to persist.
     * @param lastReviewedSha Head commit SHA of this run; `null` keeps the stored one.
     * @param pendingRules Rules that did not complete in this run ([loadPendingRules]).
     */
    fun saveSnapshotAndFindings(
        mrKey: String,
        snapshot: Map<String, Long>,
        findings: Map<String, List<Finding>>,
        lastReviewedSha: String? = null,
        pendingRules: Set<String> = emptySet(),
    ) {
        val state = loadState(mrKey)
        val sha = lastReviewedSha ?: state.lastReviewedSha
        saveState(mrKey, state.copy(snapshot = snapshot, findingsCache = findings, lastReviewedSha = sha, pendingRules = pendingRules))
    }

    override fun loadPendingRules(mrKey: String): Set<String> = if (readCaches) loadState(mrKey).pendingRules else emptySet()

    /** Head commit SHA saved by the last incremental run for [mrKey], or `null` (also under `--no-cache`). */
    fun loadLastReviewedSha(mrKey: String): String? = if (readCaches) loadState(mrKey).lastReviewedSha else null

    // -- Canary ------------------------------------------------------------------

    /**
     * Loads the cross-MR canary observations, keyed by rule ID. Not a cache: read even under `--no-cache`.
     *
     * @return The persisted stats, or an empty map if missing or corrupt.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: CAS I/O can throw various exceptions
    fun loadCanaryStats(): Map<String, CanaryRuleStats> {
        val bytes = try {
            casCache.get(CANARY_KEY)
        } catch (e: Exception) {
            // boundary: CAS I/O can throw various exceptions
            logger.warn("AI canary state read failed: ${e::class.simpleName}: ${e.message}")
            null
        } ?: return emptyMap()
        return try {
            KarandashJson.Default.decodeFromString(CANARY_SERIALIZER, bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            logger.warn("AI canary state read failed: ${e::class.simpleName}: ${e.message}")
            emptyMap()
        }
    }

    /**
     * Saves the cross-MR canary observations (kept for the store's [ttl] after the last save).
     *
     * @param stats Observations keyed by rule ID.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: best-effort persistence must not crash the pipeline
    fun saveCanaryStats(stats: Map<String, CanaryRuleStats>) {
        try {
            casCache.put(CANARY_KEY, KarandashJson.Default.encodeToString(CANARY_SERIALIZER, stats).encodeToByteArray())
        } catch (e: Exception) {
            // boundary: best-effort persistence must not crash the pipeline
            logger.warn("AI canary state save failed: ${e.message}")
        }
    }

    // -- Cross-MR models -----------------------------------------------------------

    /** Loads the author violation history for temporal escalation (empty when missing or corrupt). */
    fun loadTemporalState(): TemporalState = loadGlobal(TEMPORAL_KEY, TemporalState.serializer()) ?: TemporalState()

    /** Saves the author violation history for temporal escalation. */
    fun saveTemporalState(state: TemporalState) = saveGlobal(TEMPORAL_KEY, TemporalState.serializer(), state)

    /** Loads the defect-model drift statistics (empty when missing or corrupt). */
    fun loadDefectModelState(): DefectModelState = loadGlobal(DEFECT_MODEL_KEY, DefectModelState.serializer()) ?: DefectModelState()

    /** Saves the defect-model drift statistics. */
    fun saveDefectModelState(state: DefectModelState) = saveGlobal(DEFECT_MODEL_KEY, DefectModelState.serializer(), state)

    /** Loads the grace-period uses (empty when missing or corrupt). */
    fun loadGraceUsage(): GraceUsageState = loadGlobal(GRACE_KEY, GraceUsageState.serializer()) ?: GraceUsageState()

    /** Saves the grace-period uses. */
    fun saveGraceUsage(state: GraceUsageState) = saveGlobal(GRACE_KEY, GraceUsageState.serializer(), state)

    @Suppress("TooGenericExceptionCaught") // boundary: CAS I/O can throw various exceptions
    private fun <T> loadGlobal(
        key: String,
        serializer: KSerializer<T>,
    ): T? {
        val bytes = try {
            casCache.get(key)
        } catch (e: Exception) {
            // boundary: CAS store is best-effort; I/O failures degrade to no state
            e.rethrowCancellation()
            readFailed(key, e)
        } ?: return null
        return try {
            KarandashJson.Default.decodeFromString(serializer, bytes.decodeToString())
        } catch (e: SerializationException) {
            readFailed(key, e)
        } catch (e: IllegalArgumentException) {
            readFailed(key, e)
        }
    }

    private fun readFailed(
        key: String,
        e: Exception,
    ): Nothing? {
        logger.warn("AI state '$key' read failed: ${e::class.simpleName}: ${e.message}")
        return null
    }

    @Suppress("TooGenericExceptionCaught") // boundary: best-effort persistence must not crash the pipeline
    private fun <T> saveGlobal(
        key: String,
        serializer: KSerializer<T>,
        value: T,
    ) {
        try {
            casCache.put(key, KarandashJson.Default.encodeToString(serializer, value).encodeToByteArray())
        } catch (e: Exception) {
            // boundary: CAS store is best-effort; I/O failures degrade to no state
            e.rethrowCancellation()
            logger.warn("AI state '$key' save failed: ${e.message}")
        }
    }

    // -- Lifecycle --------------------------------------------------------------

    /**
     * Removes expired MR state entries based on [ttl].
     *
     * When TTL is [KarandashDuration.NEVER], no entries are removed. CAS TTL handles expiry
     * automatically; this method iterates keys and counts those that are now missing
     * from the index (already expired and evicted).
     *
     * @return Number of MR entries invalidated.
     */
    fun cleanupExpired(): Int {
        if (ttl == KarandashDuration.NEVER) return 0
        val keys = casCache.keys().filter { it.startsWith(KEY_PREFIX) }
        var removed = 0
        for (key in keys) {
            // Attempt a get; if null, the entry is expired - invalidate the index entry.
            if (casCache.get(key) == null) {
                casCache.invalidate(key)
                removed++
            }
        }
        return removed
    }

    /**
     * Lists all MR keys that have persisted state.
     *
     * @return List of MR key strings (without the `"ai_"` prefix).
     */
    fun listMrKeys(): List<String> =
        casCache
            .keys()
            .filter { it.startsWith(KEY_PREFIX) }
            .map { it.removePrefix(KEY_PREFIX) }

    /**
     * Removes all persisted state for a specific MR.
     *
     * @param mrKey The MR key whose state should be deleted.
     */
    fun clearMr(mrKey: String) {
        casCache.invalidate(casKey(mrKey))
    }

    /**
     * Removes all persisted AI state entries.
     */
    fun clearAll() {
        val keys = casCache.keys().filter { it.startsWith(KEY_PREFIX) }
        for (key in keys) {
            casCache.invalidate(key)
        }
    }

    // -- Private helpers --------------------------------------------------------

    private fun loadState(mrKey: String): AiMrState {
        val stored = readState(mrKey) ?: return AiMrState()
        if (stored.schemaVersion > AiMrState.CURRENT_SCHEMA_VERSION) {
            logger.warn(newerSchemaMessage(mrKey, stored.schemaVersion, "ignoring it"))
            return AiMrState()
        }
        return stored
    }

    @Suppress("TooGenericExceptionCaught") // boundary: CAS I/O can throw various exceptions
    private fun readState(mrKey: String): AiMrState? {
        val bytes = try {
            casCache.get(casKey(mrKey))
        } catch (e: Exception) {
            // boundary: CAS I/O can throw various exceptions
            logger.warn("AI state read failed for $mrKey: ${e::class.simpleName}: ${e.message}")
            null
        } ?: return null
        return try {
            KarandashJson.Default.decodeFromString(AiMrState.serializer(), bytes.decodeToString())
        } catch (e: IllegalArgumentException) {
            logger.warn("AI state read failed for $mrKey: ${e::class.simpleName}: ${e.message}")
            null
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: CAS I/O can throw various exceptions
    private fun saveState(
        mrKey: String,
        state: AiMrState,
    ) {
        val storedVersion = readState(mrKey)?.schemaVersion ?: AiMrState.CURRENT_SCHEMA_VERSION
        if (storedVersion > AiMrState.CURRENT_SCHEMA_VERSION) {
            logger.warn(newerSchemaMessage(mrKey, storedVersion, "not overwriting it"))
            return
        }
        try {
            val json = KarandashJson.Default.encodeToString(AiMrState.serializer(), state)
            casCache.put(casKey(mrKey), json.encodeToByteArray())
        } catch (e: Exception) {
            // boundary: best-effort persistence - failures must not crash the pipeline.
            // Orphaned CAS blobs are cleaned by CacheGarbageCollector on shutdown.
            logger.warn("AI state save failed for $mrKey: ${e.message}")
        }
    }

    private fun newerSchemaMessage(
        mrKey: String,
        version: Int,
        action: String,
    ): String =
        "AI state for $mrKey has schemaVersion $version, newer than supported " +
            "${AiMrState.CURRENT_SCHEMA_VERSION} (written by a newer karandash) - $action"

    private fun casKey(mrKey: String): String = "$KEY_PREFIX${sanitizeKey(mrKey)}"

    private fun sanitizeKey(key: String): String = key.replace('/', '_').replace('\\', '_').replace(':', '_')

    companion object {
        /** Default TTL: 7 days. */
        val DEFAULT_TTL: KarandashDuration = KarandashDuration.ofSeconds(7 * 24 * 3_600L)

        internal const val KEY_PREFIX = "ai_"

        /** Outside [KEY_PREFIX] so MR listings and [clearAll] leave it alone. */
        internal const val CANARY_KEY = "ai-canary"

        /** Cross-MR author history; outside [KEY_PREFIX] like [CANARY_KEY]. */
        internal const val TEMPORAL_KEY = "ai-temporal"

        /** Cross-MR defect-model statistics; outside [KEY_PREFIX] like [CANARY_KEY]. */
        internal const val DEFECT_MODEL_KEY = "ai-defect-model"

        /** Grace-period uses across MRs; outside [KEY_PREFIX] like [CANARY_KEY]. */
        internal const val GRACE_KEY = "ai-grace"

        private val CANARY_SERIALIZER = MapSerializer(String.serializer(), CanaryRuleStats.serializer())
    }
}

/**
 * Single blob holding all per-MR AI state.
 *
 * Stored as one JSON entry in the CAS cache keyed by `"ai:{mrKey}"`.
 * All fields have safe defaults so that a fresh read returns empty state.
 *
 * @param feedback Dismissed finding fingerprints for the feedback loop.
 * @param snapshot File diff hashes for incremental review.
 * @param responseCache Cached AI provider responses, keyed by content hash.
 * @param findingsCache Per-file findings from the previous run, keyed by file path.
 * @param lastReviewedSha Head commit SHA of the last run that saved [snapshot] (`ai.incremental`).
 * @param pendingRules AI rules that did not complete in the run that saved [snapshot]; they review the whole MR next run.
 * @param schemaVersion Format version of this blob. A blob with a higher version is ignored with a warning.
 */
@Serializable
data class AiMrState(
    val feedback: FeedbackStoreData = FeedbackStoreData(),
    val snapshot: Map<String, Long> = emptyMap(),
    val responseCache: Map<String, CachedResponseDto> = emptyMap(),
    val findingsCache: Map<String, List<Finding>> = emptyMap(),
    val lastReviewedSha: String? = null,
    val pendingRules: Set<String> = emptySet(),
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
) {
    companion object {
        /** Bump when the persisted shape changes incompatibly; add a migration for the old version. */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}

/**
 * Serialization wrapper for [FeedbackStore] state.
 *
 * @param entries The list of dismissed finding entries.
 * @param accepted Anchor fingerprints of findings a reviewer accepted ([FeedbackStore.acceptedAnchors]).
 */
@Serializable
data class FeedbackStoreData(
    val entries: List<DismissedEntry> = emptyList(),
    val accepted: List<String> = emptyList(),
)

/**
 * A cached AI provider response with TTL metadata.
 *
 * @param content The text content of the AI response.
 * @param promptTokens Number of tokens in the prompt.
 * @param completionTokens Number of tokens in the completion.
 * @param totalTokens Total tokens consumed.
 * @param model The model that produced this response.
 * @param createdAt Epoch seconds when this entry was cached.
 */
@Serializable
data class CachedResponse(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val model: String,
    val createdAt: Long,
)

/**
 * Serializable DTO for [CachedResponse].
 */
@Serializable
data class CachedResponseDto(
    val content: String,
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int,
    val model: String,
    val createdAt: Long,
) {
    /** Converts this DTO to the public [CachedResponse] type. */
    fun toCachedResponse(): CachedResponse =
        CachedResponse(
            content = content,
            promptTokens = promptTokens,
            completionTokens = completionTokens,
            totalTokens = totalTokens,
            model = model,
            createdAt = createdAt,
        )

    companion object {
        /** Creates a [CachedResponseDto] from a [CachedResponse]. */
        fun fromCachedResponse(r: CachedResponse): CachedResponseDto =
            CachedResponseDto(
                content = r.content,
                promptTokens = r.promptTokens,
                completionTokens = r.completionTokens,
                totalTokens = r.totalTokens,
                model = r.model,
                createdAt = r.createdAt,
            )
    }
}
