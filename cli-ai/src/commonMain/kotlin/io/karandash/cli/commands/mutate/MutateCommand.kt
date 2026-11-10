package io.karandash.cli.commands.mutate

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.int
import com.github.ajalt.clikt.parameters.types.restrictTo
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.commands.ConfiguredRulesSource
import io.karandash.cli.commands.runOrExit
import io.karandash.plugins.ai.core.architecture.MutationTestCase
import io.karandash.plugins.ai.core.architecture.RuleMutationTester

/**
 * CLI command that runs mutation tests against the rule configuration.
 *
 * Validates each synthetic bad MR from [RuleMutationTester] with the configured rules and reports which
 * mutations were caught (a finding from the targeted rule) and which escaped.
 *
 * `--rule` loads only that rule and counts a mutation as caught when it fires, so the score measures how
 * many of the synthetic bad MRs that one rule notices. `--synthetic-mrs N` runs N MRs, cycling through the
 * built-in mutation templates.
 */
class MutateCommand(
    private val rules: ConfiguredRulesSource,
) : SuspendCliktCommand(name = "mutate") {
    override fun help(context: Context) = "Run mutation tests against rule configuration"

    private val configOptions by ConfigFileOptions()
    private val configFile get() = configOptions.path

    private val rule by option("--rule", help = "Test only this rule: a mutation is caught when it fires")

    private val syntheticMrs by option("--synthetic-mrs", help = "Number of synthetic MRs (cycles the mutation templates)")
        .int()
        .restrictTo(min = 1)

    override suspend fun suspendRun() {
        val mutations = mutations()
        val ruleFilter: (String) -> Boolean = rule?.let { only -> { it == only } } ?: { true }
        val result = rules.runOrExit("Mutation testing failed", { echo(it, err = true) }, configFile, ruleFilter) { runner ->
            // runTests takes a non-suspend validator, so validate every mutation MR up front.
            val findingsByMr = mutations.associate { it.mr to runner.validate(it.mr) }
            RuleMutationTester.runTests(mutations) { mr -> findingsByMr[mr].orEmpty() }
        }
        echo(RuleMutationTester.formatReport(result))
    }

    private fun mutations(): List<MutationTestCase> {
        val templates = RuleMutationTester.generateMutations().map { case -> rule?.let { case.copy(targetRule = it) } ?: case }
        val count = syntheticMrs ?: return templates
        return List(count) { i ->
            val template = templates[i % templates.size]
            if (count <= templates.size) template else template.copy(name = "${template.name}#${i / templates.size + 1}")
        }
    }
}
