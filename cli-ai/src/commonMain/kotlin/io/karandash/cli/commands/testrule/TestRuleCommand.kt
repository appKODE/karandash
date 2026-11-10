package io.karandash.cli.commands.testrule

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.ConfiguredRules
import io.karandash.cli.commands.ConfiguredRulesSource
import io.karandash.cli.commands.MergeRequestSnapshot
import io.karandash.cli.commands.StatsHistoryOptions
import io.karandash.cli.commands.runOrExit
import io.karandash.cli.errors.ExitCodes
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiPluginConfig
import io.karandash.plugins.ai.core.RuleSandbox
import io.karandash.plugins.ai.core.SandboxResult
import io.karandash.plugins.ai.core.config.SandboxConfig
import io.karandash.plugins.ai.statistics.StatisticsStore
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.math.roundToInt

/**
 * CLI command to test a rule configuration against real MRs.
 *
 * With `--mr-url` (fetched via the configured platform service) and/or `--snapshot` (a `MergeRequest`
 * JSON file), the rule as configured in the config file is re-run on each MR and [RuleSandbox] reports
 * its impact. Without either, it summarizes the rule's recorded fire rate from the statistics history
 * (`--stats-dir`, or explicitly `--against-history`, optionally limited to the last `--window` days); that
 * summary does not re-evaluate the rule.
 *
 * Usage: `karandash test-rule <rule-id> [--mr-url URL]... [--snapshot mr.json]... [--file config]`
 * or `karandash test-rule <rule-id> --against-history [--window DAYS] [--replay-count N]`
 */
class TestRuleCommand(
    private val rules: ConfiguredRulesSource,
    /** Supplies the project's merged `ai:` block for the `ai.sandbox` defaults; `null` when there is none. */
    private val aiConfig: suspend () -> JsonElement? = { null },
) : SuspendCliktCommand(name = "test-rule") {
    override fun help(context: Context) = "Test a rule configuration against historical MR data"

    private val ruleId by argument(help = "Rule ID to test")

    private val replayCount by option(
        "--replay-count",
        help = "Number of MR snapshots to replay (default: ai.sandbox.historyWindow, else 50)"
    ).int()

    private val history by StatsHistoryOptions(windowHelp = "History mode: only runs recorded in the last N days")

    private val mrUrls by option("--mr-url", help = "MR/PR URL to replay the rule on (repeatable)").multiple()
    private val snapshots by option("--snapshot", help = "MergeRequest JSON file to replay the rule on (repeatable)")
        .multiple()
    private val configOptions by ConfigFileOptions()
    private val configFile get() = configOptions.path

    private val againstHistory by option(
        "--against-history",
        help = "Summarize the rule's recorded history (the default without --mr-url/--snapshot)",
    ).flag()

    override suspend fun suspendRun() {
        if (againstHistory && (mrUrls.isNotEmpty() || snapshots.isNotEmpty())) {
            echo("--against-history cannot be combined with --mr-url or --snapshot", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val sandbox = sandboxConfig()
        if (mrUrls.isEmpty() && snapshots.isEmpty()) {
            summarizeHistory(replayCount ?: sandbox.historyWindow ?: DEFAULT_REPLAY_COUNT, sandbox.reportMetrics)
            return
        }
        val result = try {
            rules.runOrExit("Rule replay failed", { echo(it, err = true) }, configFile, { it == ruleId }) { runner -> replay(runner) }
        } catch (e: SerializationException) {
            echo("Rule replay failed: invalid snapshot JSON: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        printResult(result, sandbox.reportMetrics)
    }

    private suspend fun sandboxConfig(): SandboxConfig {
        val ai = aiConfig() as? JsonObject ?: return SandboxConfig()
        if (ai[SANDBOX_KEY] == null) return SandboxConfig()
        return try {
            AiPluginConfig.fromPluginConfig(AiPluginConfig.topLevelAiBlockToPluginConfigElement(ai)).sandbox ?: SandboxConfig()
        } catch (e: IllegalArgumentException) {
            echo("Ignoring invalid ai.sandbox: ${e.message}", err = true)
            SandboxConfig()
        }
    }

    private suspend fun replay(runner: ConfiguredRules): SandboxResult {
        if (runner.config.rules.ruleConfigs
                .isEmpty()
        ) {
            echo("No 'rules.$ruleId' block in the config; nothing to replay.", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        val mrs = snapshots.map(MergeRequestSnapshot::read) +
            mrUrls.map { runner.fetchMergeRequest(it) }
        // RuleSandbox takes a non-suspend validator, so validate every MR up front.
        val findings = mrs.map { runner.validate(it) }
        return RuleSandbox().replay(mrs, ruleId) { mr -> findings[mrs.indexOf(mr)] }
    }

    private suspend fun summarizeHistory(
        replayCount: Int,
        reportMetrics: Boolean,
    ) {
        val cutoff = history.cutoff()
        val statsDir = history.dir(aiConfig)
        val records = StatisticsStore(statsDir).loadAll().filter { cutoff == null || it.timestamp >= cutoff }

        if (records.isEmpty()) {
            echo("No validation history found in $statsDir" + history.windowDays?.let { " for the last $it days" }.orEmpty())
            echo("Run 'karandash validate' with persistence enabled to build history.")
            return
        }

        val sandbox = RuleSandbox()

        val mockSnapshots = records.take(replayCount).mapIndexed { i, record ->
            MergeRequest(
                id = record.mrId ?: "mr-$i",
                iid = i + 1,
                title = "Historical MR ${record.mrId ?: i}",
                description = null,
                sourceBranch = "feature",
                targetBranch = "main",
                author = User(username = "dev", name = "Dev"),
                state = MergeRequestState.OPENED,
                draft = false,
                labels = emptyList(),
                commits = emptyList(),
                changes = emptyList(),
            )
        }

        val result = sandbox.replay(mockSnapshots, ruleId) { mr ->
            val recordIdx = mockSnapshots.indexOf(mr)
            val record = records.getOrNull(recordIdx) ?: return@replay emptyList()
            val count = record.findingsByRule[ruleId] ?: 0
            if (count > 0) {
                listOf(
                    Finding(
                        severity = Severity.WARNING,
                        message = "Historical finding ($count occurrence(s))",
                        rule = ruleId,
                    ),
                )
            } else {
                emptyList()
            }
        }

        echo("Recorded history only (no --mr-url/--snapshot given); the rule is not re-evaluated.")
        printResult(result, reportMetrics)
    }

    private fun printResult(
        result: SandboxResult,
        reportMetrics: Boolean,
    ) {
        echo("Rule Sandbox: $ruleId")
        echo("=".repeat(SEPARATOR_LENGTH))
        echo("Replayed: ${result.snapshotsReplayed} MR(s)")
        echo("Fire rate: ${(result.impactSummary.fireRate * PERCENT).roundToInt()}%")
        echo("Pass: ${result.impactSummary.passCount} | Fail: ${result.impactSummary.failCount}")
        if (reportMetrics) {
            echo("Affected MRs: ${result.impactSummary.affectedMrCount}")
            echo(
                "Severity: " + result.impactSummary.severityDistribution.entries
                    .joinToString { "${it.key}=${it.value}" }
            )
        }
        echo("")
        echo(result.impactSummary.recommendation)
    }

    companion object {
        private const val DEFAULT_REPLAY_COUNT = 50
        private const val SANDBOX_KEY = "sandbox"
        private const val SEPARATOR_LENGTH = 50
        private const val PERCENT = 100
    }
}
