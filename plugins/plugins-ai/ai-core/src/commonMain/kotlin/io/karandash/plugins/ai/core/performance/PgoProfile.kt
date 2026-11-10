package io.karandash.plugins.ai.core.performance

import io.karandash.plugins.ai.core.reporters.AiReportText
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.update
import kotlinx.serialization.Serializable

/**
 * Profile-Guided Optimization support.
 *
 * Tracks hot code paths during validation to generate profiling data
 * that can be fed back to the LLVM compiler for optimized native binary layout.
 *
 * Usage:
 * 1. Build with profiling: `./gradlew :cli:linkReleaseExecutableMacosArm64 -Pkarandash.pgo.generate=true`
 * 2. Run validation workload to generate profile data
 * 3. Rebuild with profile: `./gradlew :cli:linkReleaseExecutableMacosArm64 -Pkarandash.pgo.use=true`
 */
object PgoProfile {
    @Serializable
    private data class PgoState(
        val hotPaths: Map<String, Long> = emptyMap(),
        val profilingEnabled: Boolean = false,
    )

    private val state = atomic(PgoState())

    /** Enables runtime path tracking for PGO profile generation. */
    fun enableProfiling() {
        state.update { it.copy(profilingEnabled = true) }
    }

    /** Records a hot path execution. Call from performance-critical code paths. */
    fun recordPath(pathId: String) {
        if (!state.value.profilingEnabled) return
        state.update { s ->
            s.copy(hotPaths = s.hotPaths + (pathId to (s.hotPaths[pathId] ?: 0L) + 1L))
        }
    }

    /** Returns the top N hottest paths sorted by execution count. */
    fun hotPaths(topN: Int = DEFAULT_TOP_N): List<HotPath> {
        val snapshot = state.value.hotPaths
        return snapshot.entries
            .toList()
            .sortedByDescending { entry -> entry.value }
            .take(topN)
            .map { entry -> HotPath(entry.key, entry.value) }
    }

    /** Resets all profiling data. */
    fun reset() {
        state.value = PgoState()
    }

    /** Total recorded path executions. */
    fun totalExecutions(): Long =
        state.value.hotPaths.values
            .sum()

    /** Returns a human-readable profiling report. */
    fun formatReport(language: String? = null): String =
        buildString {
            val snapshot = state.value
            val total = snapshot.hotPaths.values.sum()
            val size = snapshot.hotPaths.size
            val paths = snapshot.hotPaths.entries
                .toList()
                .sortedByDescending { entry -> entry.value }
                .take(DEFAULT_TOP_N)
                .map { entry -> HotPath(entry.key, entry.value) }
            appendLine(AiReportText.PGO_TITLE.of(language))
            appendLine(AiReportText.PGO_TOTAL.of(language, total))
            appendLine(AiReportText.PGO_UNIQUE.of(language, size))
            if (paths.isNotEmpty()) {
                appendLine()
                appendLine(AiReportText.PGO_TOP.of(language))
                for (hp in paths) {
                    appendLine("  ${hp.pathId}: ${hp.executionCount}")
                }
            }
        }

    private const val DEFAULT_TOP_N = 20
}

/** A single hot path entry with its execution count. */
@Serializable
data class HotPath(
    val pathId: String,
    val executionCount: Long,
)
