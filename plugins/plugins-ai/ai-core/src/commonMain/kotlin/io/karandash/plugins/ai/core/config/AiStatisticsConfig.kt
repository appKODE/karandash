package io.karandash.plugins.ai.core.config

import kotlinx.serialization.Serializable

/**
 * Configuration for AI statistics persistence.
 *
 * @property dir Directory where statistics files are stored (relative to working directory).
 * @property maxHistory Maximum number of run records to retain.
 */
@Serializable
data class AiStatisticsConfig(
    val dir: String = DEFAULT_STATS_DIR,
    val maxHistory: Int = DEFAULT_MAX_HISTORY,
) {
    companion object {
        const val DEFAULT_STATS_DIR = ".karandash/stats"
        const val DEFAULT_MAX_HISTORY = 100
    }
}
