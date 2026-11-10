package io.karandash.cli.commands

import com.github.ajalt.clikt.parameters.groups.OptionGroup
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.karandash.plugins.ai.statistics.StatisticsStore
import kotlinx.serialization.json.JsonElement
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days

/** `--stats-dir` and `--window` of the commands that read recorded validation history (`calibrate`, `test-rule`). */
class StatsHistoryOptions(
    windowHelp: String,
) : OptionGroup() {
    val statsDir by option(
        "--stats-dir",
        help = "Statistics directory (default: ai.statistics.dir from config, else ${StatisticsStore.DEFAULT_STATS_DIR})",
    )

    val windowDays by option("--window", help = windowHelp)
        .int()
        .restrictTo(min = 1)

    /** `--stats-dir`, else `ai.statistics.dir` of [aiConfig], else the default directory. */
    suspend fun dir(aiConfig: suspend () -> JsonElement?): String = statsDir ?: StatisticsStore.resolveDir(aiConfig())

    /** Epoch seconds before which a record is outside `--window`; `null` without it. */
    fun cutoff(): Long? = windowDays?.let { (Clock.System.now() - it.days).epochSeconds }
}
