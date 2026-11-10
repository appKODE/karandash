package io.karandash.cli.commands.stats

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.core.UsageError
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiService
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.explain.pickAiProvider
import io.karandash.cli.commands.stats.audit.AuditOptions
import io.karandash.cli.commands.stats.audit.StatsAuditRunner
import io.karandash.cli.commands.stats.digest.DiagnosticDigest
import io.karandash.cli.commands.stats.digest.DigestRenderer
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.Config
import io.karandash.models.support.KarandashJson
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.createDirectories
import io.karandash.platform.safeParent
import io.karandash.platform.writeText
import io.karandash.plugins.ai.core.AiDefaultModels
import io.karandash.plugins.ai.core.LlmReplyJudge
import io.karandash.plugins.ai.statistics.RunRecord
import io.karandash.plugins.ai.statistics.StatisticsStore
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlin.time.Instant

/**
 * CLI command that displays or exports validation run statistics.
 *
 * Usage examples:
 * ```
 * karandash stats                      # show last 20 runs as a table
 * karandash stats --limit 50           # show last 50 runs
 * karandash stats --json               # export full history as JSON
 * karandash stats --clear              # delete all stored statistics
 * karandash stats --rule title-format  # filter by rule
 * karandash stats --audit --corpus corpus/ --bot karandash-bot --cutoff 2026-06-01 --out baseline-metrics.json
 * karandash stats --diagnostics digest/ --format markdown   # weekly digest over downloaded diagnostic.json files
 * ```
 */
class StatsCommand(
    /** Supplies the project's merged `ai:` block (see `loadAiConfig` in cli); `null` when there is none. */
    private val aiConfig: suspend () -> JsonElement? = { null },
    /** The registered AI providers, for `--audit --llm-judge`. */
    private val aiProviders: () -> List<AiProviderModule> = { emptyList() },
) : SuspendCliktCommand(name = "stats") {
    override fun help(context: Context) = "Show validation run statistics and rule violation trends"

    private val jsonOutput by option(
        "--json",
        help = "Export full statistics history as JSON to stdout",
    ).flag()

    private val limit by option(
        "--limit",
        "-n",
        help = "Number of recent runs to display (default: 20)",
    ).int().restrictTo(min = 1).default(DEFAULT_LIMIT)

    private val ruleFilter by option(
        "--rule",
        "-r",
        help = "Filter statistics to a specific rule ID",
    )

    private val statsDir by option(
        "--stats-dir",
        help = "Directory where statistics are stored " +
            "(default: ai.statistics.dir from config, else ${StatisticsStore.DEFAULT_STATS_DIR})",
    )

    private val clear by option(
        "--clear",
        help = "Delete all stored statistics",
    ).flag()

    private val audit by option(
        "--audit",
        help = "Audit a frozen review-history corpus (tools/audit/fetch_gitlab_corpus.py) instead of local statistics",
    ).flag()

    private val corpus by option("--corpus", help = "Corpus directory for --audit (<dir>/<project>/<iid>.json)")

    private val auditOut by option("--out", help = "Write the --audit JSON report here instead of stdout")

    private val cutoff by option("--cutoff", help = "--audit: MRs created before this date (YYYY-MM-DD) tune, the rest test")

    private val holdoutProject by option(
        "--holdout-project",
        help = "--audit: project kept out of the tune and test splits (full path or one path segment)"
    )

    private val bots by option("--bot", help = "--audit: bot username (repeatable)").multiple()

    private val seed by option("--seed", help = "--audit: bootstrap seed (default: 0)").int().default(0)

    private val llmJudge by option(
        "--llm-judge",
        help = "--audit: ask the configured AI provider about replies the keyword classifier leaves unknown",
    ).flag()

    private val llmJudgeMaxCalls by option("--llm-judge-max-calls", help = "--audit --llm-judge: model call cap (default: 500)")
        .int()
        .restrictTo(min = 1)
        .default(DEFAULT_JUDGE_CALLS)

    private val diagnostics by option(
        "--diagnostics",
        help = "Digest of diagnostic.json files instead of local statistics: a file, a directory (walked for *.json) " +
            "or dir/pattern*.json (repeatable)",
    ).multiple()

    private val format by option("--format", help = "--diagnostics output: text (default) or markdown")
        .choice("text", "markdown")
        .default("text")

    override suspend fun suspendRun() {
        if (audit) return runAudit()
        if (diagnostics.isNotEmpty()) return runDigest()
        val store = StatisticsStore(statsDir ?: StatisticsStore.resolveDir(aiConfig()))

        if (clear) {
            store.clear()
            echo("Statistics cleared.")
            return
        }

        val records = store.loadAll()

        if (records.isEmpty()) {
            echo("No statistics recorded yet. Run 'karandash validate' to start collecting stats.")
            return
        }

        if (jsonOutput) {
            val json = KarandashJson.Pretty
            echo(json.encodeToString(ListSerializer(RunRecord.serializer()), records))
            return
        }

        val filtered = applyFilter(records)
        val recent = filtered.takeLast(limit)

        printTable(recent)
        echo("")
        printTopRules(filtered)
    }

    private suspend fun runAudit() {
        val dir = corpus ?: throw UsageError("--corpus is required with --audit")
        val date = cutoff
        if (date != null && !isIsoDate(date)) throw UsageError("--cutoff must be YYYY-MM-DD, got '$date'")
        val options = AuditOptions(bots.toSet(), seed, date, holdoutProject)
        val judge = if (llmJudge) replyJudge() else null
        val run = try {
            val judged = judge?.let { StatsAuditRunner.judgeReplies(Path(dir), options, it) }.orEmpty()
            if (judge != null) echoJudge(judge, judged.size)
            StatsAuditRunner.run(Path(dir), options, judged)
        } catch (e: IllegalArgumentException) {
            // also SerializationException: a corpus file that is not valid corpus JSON
            echo("Audit failed: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val out = auditOut
        if (out == null) {
            echo(run.json)
            echo(run.table, err = true)
            echo(run.rootCauseTable, err = true)
            return
        }
        Path(out).safeParent?.let { createDirectories(it) }
        Path(out).writeText(run.json + "\n")
        echo(run.table)
        echo(run.rootCauseTable)
        echo("Audit report written to $out")
    }

    private fun runDigest() {
        val files = diagnostics.flatMap(DiagnosticDigest::files).distinct()
        if (files.isEmpty()) {
            echo("No diagnostic.json files found in ${diagnostics.joinToString(", ")}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val (reports, warnings) = DiagnosticDigest.load(files)
        warnings.forEach { echo("Warning: skipped $it", err = true) }
        val digest = DiagnosticDigest.compute(reports, warnings.size)
        echo(if (format == "markdown") DigestRenderer.markdown(digest) else DigestRenderer.text(digest))
    }

    private fun echoJudge(
        judge: LlmReplyJudge,
        judged: Int,
    ) {
        echo("LLM judge: $judged reply judged, ${judge.modelCalls} model call of $llmJudgeMaxCalls", err = true)
        if (judge.capReached) {
            echo("Warning: --llm-judge-max-calls cap of $llmJudgeMaxCalls model calls reached, later replies stay unknown", err = true)
        }
    }

    /**
     * [LlmReplyJudge] on the provider `ai.provider` names (else the first with its key set), model `ai.model` or a cheap one.
     * Calls the provider module directly, bypassing AiService retry and response cache on purpose: an opt-in offline audit
     * capped by `--llm-judge-max-calls`, where a failed call just leaves the reply unknown.
     */
    private suspend fun replyJudge(): LlmReplyJudge {
        val ai = aiConfig()
        val module =
            pickAiProvider(aiProviders(), Config(ai = ai), warn = { echo("Warning: $it", err = true) }, env = PlatformEnvironment::getEnv)
                ?: throw UsageError("--llm-judge needs an AI provider: set ai.provider or a provider API key")
        val model = ((ai as? JsonObject)?.get("model") as? JsonPrimitive)?.contentOrNull ?: AiDefaultModels.cheapFor(module.providerId)
        val service = object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ) = module.complete(request)
        }
        return LlmReplyJudge(service, model, llmJudgeMaxCalls)
    }

    private fun applyFilter(records: List<RunRecord>): List<RunRecord> {
        val rule = ruleFilter ?: return records
        return records.filter { it.findingsByRule.containsKey(rule) }
    }

    private fun printTable(records: List<RunRecord>) {
        echo("Run History (${records.size} shown):")
        echo("")
        echo(
            "  " + padRight("#", COL_IDX) +
                padRight("Timestamp", COL_TIMESTAMP) +
                padRight("MR ID", COL_MR_ID) +
                padLeft("Total", COL_NUM) +
                padLeft("Errors", COL_NUM) +
                padLeft("Warnings", COL_WARN) +
                padLeft("Info", COL_NUM),
        )
        echo("  " + "-".repeat(SEPARATOR_WIDTH))
        records.reversed().forEachIndexed { idx, r ->
            val ts = formatTimestamp(r.timestamp)
            val mrId = (r.mrId ?: "-").take(COL_MR_ID - 1)
            echo(
                "  " + padRight("${idx + 1}", COL_IDX) +
                    padRight(ts, COL_TIMESTAMP) +
                    padRight(mrId, COL_MR_ID) +
                    padLeft("${r.totalFindings}", COL_NUM) +
                    padLeft("${r.errorCount}", COL_NUM) +
                    padLeft("${r.warningCount}", COL_WARN) +
                    padLeft("${r.infoCount}", COL_NUM),
            )
        }
    }

    private fun printTopRules(records: List<RunRecord>) {
        val ruleTotals = mutableMapOf<String, Int>()
        val ruleRunCounts = mutableMapOf<String, Int>()
        for (r in records) {
            for ((rule, count) in r.findingsByRule) {
                ruleTotals[rule] = (ruleTotals[rule] ?: 0) + count
                ruleRunCounts[rule] = (ruleRunCounts[rule] ?: 0) + 1
            }
        }
        if (ruleTotals.isEmpty()) return
        echo("Top violations by rule:")
        echo("")
        ruleTotals.entries
            .sortedByDescending { it.value }
            .take(TOP_RULES_LIMIT)
            .forEach { (rule, total) ->
                val runs = ruleRunCounts[rule] ?: 0
                echo("  ${padRight(rule, COL_RULE_NAME)}${padLeft("$total", COL_NUM)} finding(s) across $runs run(s)")
            }
    }

    /**
     * Formats epoch seconds as UTC "YYYY-MM-DD HH:MM".
     */
    internal fun formatTimestamp(epochSeconds: Long): String =
        Instant
            .fromEpochSeconds(epochSeconds)
            .toString()
            .take(MINUTE_PRECISION_LENGTH)
            .replace('T', ' ')

    companion object {
        private const val DEFAULT_LIMIT = 20
        private const val DEFAULT_JUDGE_CALLS = 500

        // `YYYY-MM-DDTHH:MM` prefix of an ISO-8601 instant
        private const val MINUTE_PRECISION_LENGTH = 16
        private const val TOP_RULES_LIMIT = 10
        private const val SEPARATOR_WIDTH = 70
        private const val COL_IDX = 6
        private const val COL_TIMESTAMP = 21
        private const val COL_MR_ID = 16
        private const val COL_NUM = 7
        private const val COL_WARN = 10
        private const val COL_RULE_NAME = 42

        private fun isIsoDate(s: String): Boolean =
            s.split('-').map { it.length } == listOf(4, 2, 2) && s.replace("-", "").all { it.isDigit() }

        private fun padRight(
            s: String,
            width: Int
        ): String = s.padEnd(width)

        private fun padLeft(
            s: String,
            width: Int
        ): String = s.padStart(width)
    }
}
