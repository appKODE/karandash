package io.karandash.lsp

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import kotlinx.coroutines.runBlocking

/**
 * Creates a [ModuleRegistry] pre-populated with stub modules matching
 * the built-in set, for use in LSP server tests.
 */
object TestRegistryFactory {
    fun createRegistry(): ModuleRegistryImpl {
        val registry = ModuleRegistryImpl()
        runBlocking {
            stubRules().forEach { registry.register(it) }
            stubProviders().forEach { registry.register(it) }
            stubReporters().forEach { registry.register(it) }
        }
        return registry
    }

    /** A bootstrap with the built-in modules (format modules included), as `karandash lsp` serves it. */
    fun loadedBootstrap(): PluginBootstrap = PluginBootstrap().also { runBlocking { it.loadPlugins() } }

    private fun stubRules(): List<RuleModule> = StubRulesPartOne.rules + StubRulesPartTwo.rules

    private fun stubProviders(): List<PlatformServiceModule> =
        listOf(
            stubProvider("gitlab", "GitLab integration"),
            stubProvider("github", "GitHub integration"),
            stubProvider("bitbucket", "Bitbucket integration"),
            stubProvider("azureDevOps", "Azure DevOps integration"),
            stubProvider("gerrit", "Gerrit integration"),
            stubProvider("gitea", "Gitea integration"),
            stubProvider("awsCodeCommit", "AWS CodeCommit integration"),
            stubProvider("jenkins", "Jenkins integration"),
        )

    private fun stubReporters(): List<ReporterModule> =
        listOf(
            stubReporter("console", "Human-readable console output", hasBlockConfig = false),
            stubReporter("markdown", "Markdown reporter", hasBlockConfig = false),
            stubReporter("slack", "Slack Block Kit reporter", hasBlockConfig = true),
            stubReporter("sarif", "SARIF 2.1.0 reporter", hasBlockConfig = true),
            stubReporter("reviewdog", "Reviewdog format reporter", hasBlockConfig = true),
            stubReporter("json", "JSON format reporter", hasBlockConfig = true),
            stubReporter("email", "SMTP email reporter", hasBlockConfig = true),
            stubReporter("webhook", "HTTP POST reporter", hasBlockConfig = true),
        )
}
