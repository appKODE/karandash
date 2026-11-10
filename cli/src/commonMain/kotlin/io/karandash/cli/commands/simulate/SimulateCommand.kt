package io.karandash.cli.commands.simulate

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.api.exception.KarandashException
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.ConfiguredRuleRunner
import io.karandash.cli.errors.ExitCodes
import io.karandash.config.keys.ModuleIds
import io.karandash.models.Approval
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.hasErrors

/**
 * Simulates approval policy evaluation against MR state.
 *
 * Loads the config like `validate`, runs only the `approval` rule (including its `policy:` tree) on the
 * MR at `--mr-url` or on a synthetic MR, and prints each finding, so users can see what is blocking an MR
 * before running the full validation pipeline. `--approvers` adds hypothetical APPROVED reviews.
 *
 * Usage:
 * ```
 * karandash simulate --approvers "a,b"  # synthetic MR approved by a and b
 * karandash simulate --mr-url <url>     # a real MR/PR (plus any --approvers)
 * ```
 */
class SimulateCommand(
    private val sharedBootstrap: PluginBootstrap? = null,
) : SuspendCliktCommand(name = "simulate") {
    override fun help(context: Context) = "Simulate approval policy evaluation against MR state"

    private val mrUrl by option("--mr-url", help = "MR/PR URL to simulate against")
    private val approvers by option("--approvers", help = "Comma-separated hypothetical approvers")
    private val configOptions by ConfigFileOptions()
    private val configFile get() = configOptions.path

    override suspend fun suspendRun() {
        echo("Approval Policy Simulation")
        echo("=".repeat(SEPARATOR_WIDTH))
        val names = approvers
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            .orEmpty()
        val passed = try {
            ConfiguredRuleRunner.withBootstrap(sharedBootstrap) { bootstrap ->
                val runner = ConfiguredRuleRunner.load(bootstrap, configFile) { it == APPROVAL_RULE_ID }
                if (runner.config.rules.ruleConfigs
                        .isEmpty()
                ) {
                    echo("No 'rules.approval' block in the config; nothing to simulate.")
                    return@withBootstrap true
                }
                val url = mrUrl
                val base = if (url != null) runner.fetchMergeRequest(url) else syntheticMr()
                echo(if (url != null) "MR: $url" else "MR: synthetic (no approvals besides --approvers)")
                if (names.isNotEmpty()) echo("Hypothetical approvers: ${names.joinToString(", ")}")
                val mr = base.copy(approvals = base.approvals + names.map { Approval(user = User(username = it, name = it)) })
                report(runner.validate(mr).filter { it.rule.startsWith(APPROVAL_RULE_ID) })
            }
        } catch (e: KarandashException) {
            echo("Simulation failed: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        if (!passed) throw ProgramResult(ExitCodes.VALIDATION_FAILED)
    }

    private fun report(findings: List<Finding>): Boolean {
        echo("")
        if (findings.isEmpty()) {
            echo("Result: approval policy satisfied")
            return true
        }
        for (finding in findings) echo("  [${finding.severity}] ${finding.message}")
        val blocked = findings.hasErrors()
        echo("Result: ${if (blocked) "blocked" else "passes with warnings"}")
        return !blocked
    }

    private fun syntheticMr(): MergeRequest =
        MergeRequest(
            id = "simulated",
            iid = 1,
            title = "Simulated MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User(username = "author", name = "Author"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )

    private companion object {
        const val SEPARATOR_WIDTH = 40
        const val APPROVAL_RULE_ID = ModuleIds.APPROVAL
    }
}
