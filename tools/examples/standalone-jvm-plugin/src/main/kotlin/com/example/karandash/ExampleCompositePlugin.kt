package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * One module, several types: a rule, a reporter, a profile and a CLI command from a single class.
 *
 * The host registers one entry per implemented interface under the same id ([type] is the primary one). The native
 * CLI serves every one of them over the JAR bridge subprocess, the JVM CLI in process.
 */
class ExampleCompositePlugin : RuleModule, ReporterModule, ProfileModule, CliCommandModule {
    override val id: String = "example-jvm-composite"
    override val name: String = "Example JVM Composite Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.RULE

    override suspend fun initialize(context: PluginContext): Boolean = true

    // RULE
    override suspend fun contributedRules(): Set<String> = setOf(RULE_ID)

    override suspend fun validate(
        ruleId: String,
        mr: MergeRequest,
        configElement: JsonElement
    ): List<Finding> =
        if (ruleId == RULE_ID && mr.title.isBlank()) {
            listOf(Finding(rule = RULE_ID, message = "Title is empty", severity = Severity.ERROR, location = null))
        } else {
            emptyList()
        }

    // REPORTER
    override val formatId: String = "example-jvm-composite-report"

    override suspend fun report(
        findings: List<Finding>,
        passed: Boolean,
        config: JsonElement
    ): String = "JVM-COMPOSITE-REPORT passed=$passed findings=${findings.size}"

    // PROFILE
    override val profileId: String = "example-jvm-composite-profile"
    override val summary: String = "Profile served by the composite JVM example"

    override suspend fun tags(): Set<String> = setOf("jvm", "composite")

    override suspend fun ruleConfigs(): Map<String, JsonElement> =
        mapOf(RULE_ID to JsonObject(mapOf("enabled" to JsonPrimitive(true))))

    override suspend fun reporterConfigs(): Map<String, JsonElement> = emptyMap()

    // CLI_COMMAND
    override suspend fun cliCommands(): List<PluginCommandSpec> =
        listOf(
            PluginCommandSpec(
                name = "jvm-composite",
                help = "Command served by the composite JVM example",
                subcommands = listOf(PluginCommandSpec(name = "hello", help = "Print a greeting", run = ::hello)),
            )
        )

    private suspend fun hello(context: PluginCommandContext): Int {
        context.echo("JVM-COMPOSITE-RAN hello ${context.rawArgs.joinToString(" ")}")
        return 0
    }

    private companion object {
        const val RULE_ID = "exampleCompositeTitle"
    }
}
