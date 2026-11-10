package io.karandash.cli.commands.calibrate

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.double
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.StatsHistoryOptions
import io.karandash.cli.commands.stats.audit.AuditOptions
import io.karandash.cli.commands.stats.audit.MrAudit
import io.karandash.cli.commands.stats.audit.StatsAuditRunner
import io.karandash.cli.errors.ExitCodes
import io.karandash.platform.createDirectories
import io.karandash.platform.readText
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.LearningsConfig
import io.karandash.plugins.ai.core.config.EvtAutoThresholdConfig
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.plugins.ai.core.metrics.CalibrationEngine
import io.karandash.plugins.ai.statistics.StatisticsStore
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * CLI command to analyze rule threshold calibration.
 *
 * Reads accumulated validation statistics and recommends threshold adjustments
 * for rules that fire too often (loosen) or too rarely (tighten).
 *
 * `--rule` restricts the analysis to one rule, `--window` to runs recorded in the last N days, and
 * `--target-fp-rate` replaces the 80% fire-rate ceiling above which a rule is recommended LOOSEN (the fire
 * rate stands in for the false-positive rate; no labelled outcomes are recorded).
 *
 * `--corpus DIR` instead reads a `stats --audit` review-history corpus and prints each analyzer's precision from
 * the accepted/rejected labels with a proposed severity change (below [AnalyzerCalibration.LOW_PRECISION]); the
 * proposal is printed, never applied. `--learn` also writes the `ai.learnings.file` ([ReviewLearnings]); no git.
 *
 * Usage: `karandash calibrate [--rule ID] [--window DAYS] [--target-fp-rate F] [--min-samples N] [--stats-dir DIR]`
 * or `karandash calibrate --corpus DIR [--bot NAME]... [--learn]`
 */
class CalibrateCommand(
    /** Supplies the project's merged `ai:` block (see `loadAiConfig`); `null` when there is none. */
    private val aiConfig: suspend () -> JsonElement? = { null },
) : SuspendCliktCommand(name = "calibrate") {
    override fun help(context: Context) = "Analyze rule thresholds and recommend calibration adjustments"

    private val minSamples by option("--min-samples", help = "Minimum run count for analysis (default $DEFAULT_MIN_SAMPLES)")
        .int()

    private val history by StatsHistoryOptions(windowHelp = "Analyze only runs recorded in the last N days")

    private val rule by option("--rule", help = "Analyze only this rule ID")

    private val targetFireRate by option(
        "--target-fp-rate",
        help = "Fire rate (0-1) above which a rule is recommended LOOSEN; the fire rate is the false-positive proxy",
    ).double()
        .restrictTo(0.0..1.0)

    private val corpus by option("--corpus", help = "Review-history corpus (as for stats --audit): propose per-analyzer severities")

    private val bots by option("--bot", help = "--corpus: karandash bot username (repeatable)").multiple()

    private val learn by option("--learn", help = "--corpus: write the ai.learnings.file the corpus supports").flag()

    override suspend fun suspendRun() {
        corpus?.let {
            rejectCorpusIgnored()
            return runCorpus(it)
        }
        if (learn) throw UsageError("--learn needs --corpus")
        val cutoff = history.cutoff()
        val minSamples = minSamples ?: DEFAULT_MIN_SAMPLES
        val records = StatisticsStore(history.dir(aiConfig)).loadAll().filter {
            cutoff == null ||
                it.timestamp >= cutoff
        }
        history.windowDays?.let { echo("Window: last $it days") }

        if (records.size < minSamples) {
            echo("Insufficient data: ${records.size} runs found, need at least $minSamples")
            return
        }

        val ruleIds = rule?.let { setOf(it) } ?: records.flatMap { it.findingsByRule.keys }.toSet()
        val recommendations = targetFireRate?.let { CalibrationEngine.analyze(records, ruleIds, evtConfig(), it) }
            ?: CalibrationEngine.analyze(records, ruleIds, evtConfig())

        if (recommendations.isEmpty()) {
            echo("All rules are well-calibrated (${records.size} runs analyzed)")
            return
        }

        echo("Calibration Analysis (${records.size} runs)")
        echo("=".repeat(SEPARATOR_LENGTH))
        for (rec in recommendations) {
            echo("")
            echo("Rule: ${rec.ruleId}")
            echo("  Fire rate: ${(rec.currentFireRate * PERCENT).roundToInt()}%")
            echo("  Avg findings/run: ${rec.averageFindingsPerRun.formatDecimals(1)}")
            echo("  Recommendation: ${rec.recommendation}")
            echo("  Reason: ${rec.reason}")
        }
    }

    /** The statistics-mode flags do nothing with `--corpus`; a usage error beats silently ignoring them. */
    private fun rejectCorpusIgnored() {
        val given = listOfNotNull(
            "--rule".takeIf { rule != null },
            "--window".takeIf { history.windowDays != null },
            "--target-fp-rate".takeIf { targetFireRate != null },
            "--min-samples".takeIf { minSamples != null },
            "--stats-dir".takeIf { history.statsDir != null },
        )
        if (given.isNotEmpty()) throw UsageError("${given.joinToString()} cannot be combined with --corpus")
    }

    private suspend fun runCorpus(dir: String) {
        val audits = try {
            StatsAuditRunner.audits(Path(dir), AuditOptions(bots = bots.toSet()))
        } catch (e: IllegalArgumentException) {
            // also SerializationException: a corpus file that is not valid corpus JSON
            echo("Calibration failed: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        echo(AnalyzerCalibration.render(AnalyzerCalibration.proposals(audits)))
        if (learn) writeLearnings(audits)
    }

    /** Writes the learnings file only; committing it (and so reviewing it) is left to the user. */
    private suspend fun writeLearnings(audits: List<MrAudit>) {
        val ai = aiConfig() as? JsonObject
        val cfg = ai?.let { AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(it)).learnings }
            ?: LearningsConfig()
        val learnings = ReviewLearnings.learn(audits, cfg.minRejections)
        val path = Path(cfg.file)
        val existing = if (SystemFileSystem.exists(path)) path.readText() else null
        if (learnings.isEmpty() && existing == null) {
            echo(
                "\nNo learnings: no rule has ${cfg.minRejections}+ rejections at ${ReviewLearnings.MIN_REJECTED_SHARE} share; ${cfg.file} not written"
            )
            return
        }
        val merged = ReviewLearnings.merge(existing, learnings)
        path.safeParent?.let { createDirectories(it) }
        path.writeText(merged.yaml)
        echo(
            "\nWrote ${cfg.file}: ${merged.kept} hand-written kept, ${merged.added} added, ${merged.removed} removed; " +
                "review and commit it to apply it to AI reviews"
        )
    }

    /** `ai.research.evtAutoThreshold` only when the user set it; unset keeps the engine's default EVT parameters. */
    private suspend fun evtConfig(): EvtAutoThresholdConfig? {
        val ai = aiConfig() as? JsonObject ?: return null
        if ((ai[RESEARCH_KEY] as? JsonObject)?.get(EVT_KEY) == null) return null
        return try {
            AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(ai)).research.evtAutoThreshold
        } catch (e: IllegalArgumentException) {
            echo("Ignoring invalid ai.research.evtAutoThreshold: ${e.message}", err = true)
            null
        }
    }

    companion object {
        private const val RESEARCH_KEY = "research"
        private const val EVT_KEY = "evtAutoThreshold"

        private const val DEFAULT_MIN_SAMPLES = 20
        private const val SEPARATOR_LENGTH = 50
        private const val PERCENT = 100
    }
}
