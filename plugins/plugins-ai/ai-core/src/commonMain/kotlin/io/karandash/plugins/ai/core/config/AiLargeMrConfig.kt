package io.karandash.plugins.ai.core.config

import io.karandash.api.exception.ConfigParseException
import io.karandash.logger.Logger
import kotlinx.serialization.Serializable

/**
 * `ai.large-mr`: how an MR too big for one prompt is reviewed.
 *
 * @property maxUnits An MR with more review units (modules or directories) than this is oversized: the summary
 *   suggests splitting it, and [incremental] applies to it.
 * @property maxParallel Chunks of one rule sent at once; every call still takes a permit of `ai.concurrency`.
 * @property incremental For an oversized MR, overrides `ai.incremental.enabled`; null follows it.
 * @property testGaps Whether the summary gets a test-gap line per unit that changed production code and no test.
 * @property chunkContext Whether a chunked call gives half its diff room to context (cross-file, enricher blocks)
 *   instead of filling it with diff.
 * @property scaleBudget Whether each MR gets a budget plan before any rule calls the model: a chunked rule gets one
 *   call ([maxCallTokens], at most the model context, never its smaller share) per chunk, so a big diff goes in more calls, not a
 *   bigger one; a whole-MR rule gets its prompt, output reserve and the MR's diff, at most the model context; funded
 *   highest `priority` first (default review-comments, whole-MR rules, the rest); the run budget grows by the same
 *   tokens, so the other rules keep theirs. Without `cost-budget` every rule is funded in full and one INFO line
 *   names the run total; with it, its token and USD caps bound the run. Off, every rule keeps its one share.
 * @property mrMapMaxFiles The most files of other chunks each chunk's call lists (path, lines added and removed), so the
 *   model knows the rest of the MR exists and does not report it missing; 0 sends no list.
 * @property maxCallTokens Most tokens one chunked call is sized to (prompt, output reserve and diff): with
 *   [scaleBudget] every chunked call is this size, at most the model context; off, a rule's share at most this.
 *   Past it, more calls review better than a bigger one.
 * @property maxRunTokens Without `cost-budget`, the most tokens [scaleBudget] grows one MR's run to (never below the
 *   run budget already set); past it the lowest-priority rules are short and one warning says so. 0 turns it off.
 */
@Serializable
data class AiLargeMrConfig(
    val maxUnits: Int = DEFAULT_MAX_UNITS,
    val maxParallel: Int = DEFAULT_MAX_PARALLEL,
    val incremental: Boolean? = null,
    val testGaps: Boolean = true,
    val chunkContext: Boolean = true,
    val scaleBudget: Boolean = true,
    val mrMapMaxFiles: Int = DEFAULT_MR_MAP_MAX_FILES,
    val maxRunTokens: Int = DEFAULT_MAX_RUN_TOKENS,
    val maxCallTokens: Int = DEFAULT_MAX_CALL_TOKENS,
) {
    /** This config with a [maxUnits], [maxParallel] or [maxCallTokens] under 1 back at its default, each named in a warning. */
    fun validated(logger: Logger?): AiLargeMrConfig =
        copy(
            maxUnits = atLeastOne("max-units", maxUnits, DEFAULT_MAX_UNITS, logger),
            maxParallel = atLeastOne("max-parallel", maxParallel, DEFAULT_MAX_PARALLEL, logger),
            maxCallTokens = atLeastOne("max-call-tokens", maxCallTokens, DEFAULT_MAX_CALL_TOKENS, logger),
        )

    private fun atLeastOne(
        key: String,
        value: Int,
        default: Int,
        logger: Logger?,
    ): Int {
        if (value >= 1) return value
        logger?.warn("ai.large-mr.$key: $value ignored, expected at least 1; using $default")
        return default
    }

    companion object {
        const val DEFAULT_MAX_UNITS: Int = 10
        const val DEFAULT_MAX_PARALLEL: Int = 4
        const val DEFAULT_MR_MAP_MAX_FILES: Int = 50
        const val DEFAULT_MAX_RUN_TOKENS: Int = 500_000
        const val DEFAULT_MAX_CALL_TOKENS: Int = 16_000
    }
}

/**
 * `ai.testGap`: when the summary names a test gap (with `ai.large-mr.test-gaps` on).
 *
 * @property level `mr` (default): only when the MR changes production code and no test anywhere, so a test in another
 *   unit counts; `file`: every review unit that changes production code and no test of its own.
 */
@Serializable
data class TestGapConfig(
    val level: String = MR,
) {
    /** This config, or a [ConfigParseException] naming `ai.testGap.level`. */
    fun validated(): TestGapConfig {
        if (level !in setOf(MR, FILE)) throw ConfigParseException("ai.testGap.level: '$level' is not $MR or $FILE")
        return this
    }

    companion object {
        const val MR: String = "mr"
        const val FILE: String = "file"
    }
}
