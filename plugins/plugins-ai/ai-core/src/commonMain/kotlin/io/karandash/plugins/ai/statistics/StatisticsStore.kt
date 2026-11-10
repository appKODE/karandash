package io.karandash.plugins.ai.statistics

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.writeTextAtomic
import io.karandash.plugins.ai.core.config.AiStatisticsConfig
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Persists [RunRecord]s to `{statsDir}/statistics.json`.
 *
 * Keeps at most [maxHistory] records; oldest are evicted when the limit is exceeded.
 * All reads are fault-tolerant and return empty defaults on missing or corrupt files.
 *
 * @param statsDir Directory where statistics are stored.
 * @param maxHistory Maximum number of run records to retain.
 */
class StatisticsStore(
    private val statsDir: String,
    private val maxHistory: Int = DEFAULT_MAX_HISTORY,
    private val logger: Logger = NoOpLogger,
) {
    /**
     * Constructs a [StatisticsStore] from an [AiStatisticsConfig].
     */
    constructor(config: AiStatisticsConfig, logger: Logger = NoOpLogger) : this(
        statsDir = config.dir,
        maxHistory = config.maxHistory,
        logger = logger,
    )

    private val json = KarandashJson.IgnoreUnknown

    /**
     * Appends [record] to the statistics file.
     *
     * If the number of stored records exceeds [maxHistory], the oldest records are removed.
     */
    @Suppress("TooGenericExceptionCaught")
    fun record(record: RunRecord) {
        try {
            val existing = (loadOrNull() ?: moveCorruptAside()).toMutableList()
            existing.add(record)
            if (existing.size > maxHistory) {
                existing.subList(0, existing.size - maxHistory).clear()
            }
            save(existing)
        } catch (e: Exception) {
            // boundary: stats errors must never fail the pipeline
            logger.warn("AI statistics: failed to record run: ${e.message}")
        }
    }

    /**
     * Loads all stored run records, oldest-first.
     *
     * @return List of [RunRecord]s, or empty list if the file is absent or corrupt.
     */
    fun loadAll(): List<RunRecord> = loadOrNull().orEmpty()

    /** The stored records, or null when the file exists but cannot be read (warned). */
    @Suppress("TooGenericExceptionCaught")
    private fun loadOrNull(): List<RunRecord>? {
        val path = statsPath()
        if (!SystemFileSystem.exists(path)) return emptyList()
        return try {
            val text = path.readText()
            json.decodeFromString(StatisticsFile.serializer(), text).runs
        } catch (e: Exception) {
            // boundary: corrupt stats file must not fail the pipeline
            logger.warn("AI statistics: unreadable stats file '$path': ${e.message}")
            null
        }
    }

    /** an unreadable file is kept as `statistics.json.corrupt` instead of being overwritten. */
    private fun moveCorruptAside(): List<RunRecord> {
        val aside = Path(statsDir, "$STATS_FILE$CORRUPT_SUFFIX")
        SystemFileSystem.atomicMove(statsPath(), aside)
        logger.warn("AI statistics: kept the unreadable stats file as '$aside'; history starts again")
        return emptyList()
    }

    /**
     * Returns the most recent [limit] run records, newest-last.
     *
     * @param limit Maximum number of records to return.
     * @return List of at most [limit] most recent [RunRecord]s.
     */
    fun recentRecords(limit: Int): List<RunRecord> {
        val all = loadAll()
        return if (all.size <= limit) all else all.subList(all.size - limit, all.size)
    }

    /**
     * Removes all stored statistics.
     */
    @Suppress("TooGenericExceptionCaught")
    fun clear() {
        try {
            val path = statsPath()
            if (SystemFileSystem.exists(path)) {
                SystemFileSystem.delete(path, mustExist = false)
            }
        } catch (e: Exception) {
            // boundary: best-effort cleanup
            logger.warn("AI statistics: failed to clear stats: ${e.message}")
        }
    }

    private fun statsPath(): Path = Path(statsDir, STATS_FILE)

    private fun save(records: List<RunRecord>) {
        val dir = Path(statsDir)
        if (!SystemFileSystem.exists(dir)) {
            createDirectories(dir)
        }
        val text = json.encodeToString(StatisticsFile.serializer(), StatisticsFile(records))
        // write a temp file and rename it over the old one, so a crash mid-write leaves the old file whole.
        // limit: no lock across processes; two concurrent runs are last-writer-wins.
        statsPath().writeTextAtomic(text)
    }

    companion object {
        /** Default directory for statistics files, relative to the working directory. */
        const val DEFAULT_STATS_DIR = ".karandash/stats"

        /** Default maximum number of run records to keep. */
        const val DEFAULT_MAX_HISTORY = 100

        internal const val STATS_FILE = "statistics.json"
        private const val CORRUPT_SUFFIX = ".corrupt"

        /**
         * The one stats-dir resolver: `ai.statistics.dir` from the normalized `ai:` block [aiConfig],
         * else [DEFAULT_STATS_DIR]. Writers (the AI pipeline) and readers (`stats`, AI reports) share it.
         */
        fun resolveDir(aiConfig: JsonElement?): String =
            (((aiConfig as? JsonObject)?.get(STATISTICS_KEY) as? JsonObject)?.get(DIR_KEY) as? JsonPrimitive)
                ?.contentOrNull
                ?.takeIf { it.isNotBlank() }
                ?: DEFAULT_STATS_DIR

        private const val STATISTICS_KEY = "statistics"
        private const val DIR_KEY = "dir"
    }
}

@Serializable
internal data class StatisticsFile(
    val runs: List<RunRecord> = emptyList(),
)
