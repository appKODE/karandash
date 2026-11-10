package io.karandash.cli.commands.dependencies

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.multiple
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.required
import io.karandash.api.exception.KarandashException
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.ai.AiHostBindings
import io.karandash.cli.commands.ConfiguredRuleRunner
import io.karandash.cli.errors.ExitCodes

/**
 * CLI command that analyzes cross-MR dependencies between an MR and its sibling MRs.
 *
 * Fetches the MR at `--mr-url` and every `--sibling` URL with the configured platform service, then reports
 * file conflicts, ordering constraints and circular dependencies via [AiHostBindings.dependencyReport].
 * Siblings are passed explicitly: platform services expose no "list open MRs" call. With `--analyze-cross-mr`
 * the siblings are also compared with each other (the full pairwise matrix), so conflicts and cycles between two
 * siblings are reported too.
 */
class DepsCommand(
    private val sharedBootstrap: PluginBootstrap? = null,
) : SuspendCliktCommand(name = "deps") {
    override fun help(context: Context) = "Analyze cross-MR dependencies between an MR and its siblings"

    private val mrUrl by option("--mr-url", help = "MR/PR URL to analyze").required()
    private val siblings by option("--sibling", help = "Sibling MR/PR URL (repeatable)").multiple()
    private val configOptions by ConfigFileOptions()
    private val configFile get() = configOptions.path
    private val crossMr by option("--analyze-cross-mr", help = "Also compare the siblings with each other (pairwise matrix)")
        .flag()

    override suspend fun suspendRun() {
        val report = try {
            ConfiguredRuleRunner.withBootstrap(sharedBootstrap) { bootstrap ->
                val runner = ConfiguredRuleRunner.load(bootstrap, configFile) { false }
                val current = runner.fetchMergeRequest(mrUrl)
                val others = siblings.map { runner.fetchMergeRequest(it) }
                if (crossMr) {
                    AiHostBindings.crossMrDependencyReport(listOf(current) + others)
                } else {
                    AiHostBindings.dependencyReport(current, others)
                }
            }
        } catch (e: KarandashException) {
            echo("Dependency analysis failed: ${e.message}", err = true)
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        echo(report)
    }
}
