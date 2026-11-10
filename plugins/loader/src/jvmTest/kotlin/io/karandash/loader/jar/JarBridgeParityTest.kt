package io.karandash.loader.jar

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.facet.PluginCommandContext
import io.karandash.api.facet.PluginCommandSpec
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.module.ScriptModule
import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.bridge.BridgeDescriptor
import io.karandash.loader.bridge.TransportModuleBridge
import io.karandash.loader.bridge.asRegisteredModules
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A multi-type JVM module answers the same through the JAR bridge (the native host's `TransportModuleBridge` over
 * [JarBridgeTransport], looped back into [JarBridgeServer.handleRequest]) as it does when called in process.
 */
class JarBridgeParityTest {
    private class ParityPlugin :
        RuleModule,
        ReporterModule,
        ProfileModule,
        CliCommandModule,
        EnrichmentProviderModule,
        ScriptModule {
        override val id = "jar-parity"
        override val name = "Jar Parity"
        override val version = "1.0.0"
        override val type get() = ModuleType.RULE
        override val defaultEnabled get() = false

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun contributedRules() = setOf(RULE)

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = listOf(Finding(rule = ruleId, message = "title=${mr.title}", severity = Severity.WARNING))

        override suspend fun ruleExplanation(ruleId: String) = "explains $ruleId"

        override val formatId = "jar-parity-report"

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ) = "passed=$passed findings=${findings.size}"

        override val profileId = "jar-parity-profile"
        override val summary = "parity profile"

        override suspend fun tags() = setOf("jar", "parity")

        override suspend fun ruleConfigs(): Map<String, JsonElement> = mapOf(RULE to JsonObject(mapOf("enabled" to JsonPrimitive(true))))

        override suspend fun cliCommands() =
            listOf(
                PluginCommandSpec(
                    name = "parity",
                    help = "group",
                    subcommands = listOf(PluginCommandSpec(name = "echo", help = "echo args", run = ::echo)),
                ),
            )

        private suspend fun echo(context: PluginCommandContext): Int {
            context.echo("ran ${context.rawArgs.joinToString(" ")}")
            context.echoError("warned")
            return EXIT_CODE
        }

        override val providedEnrichment = setOf("parity.size")

        override suspend fun enrich(
            mr: MergeRequest,
            config: JsonElement,
            upstreamContext: EnrichmentContext,
        ): Map<String, JsonElement> = mapOf("parity.size" to JsonPrimitive(mr.title.length))

        override suspend fun execute(
            scripts: List<JsonElement>,
            mr: MergeRequest,
            context: PluginContext,
        ) = listOf(Finding(rule = "script", message = "scripts=${scripts.size}", severity = Severity.INFO))
    }

    private val direct = ParityPlugin()
    private val mr = createTestMergeRequest(title = "parity")
    private val config = JsonObject(emptyMap())

    @AfterTest
    fun clear() = JarBridgeServer.moduleCache.clear()

    private suspend fun bridged(): List<Module> {
        JarBridgeServer.moduleCache[direct.id] = direct
        val info = JarBridgeServer.describe(direct)
        val bridge = TransportModuleBridge(
            transport = JarBridgeTransport({ JarBridgeServer.handleRequest(it) }, info),
            id = info.id,
            name = info.name,
            version = info.version,
            type = ModuleType.valueOf(info.type),
            descriptor = BridgeDescriptor(info.properties, info.id, types = info.types),
        )
        return bridge.asRegisteredModules().onEach { it.initialize(RecordingPluginContext()) }
    }

    private inline fun <reified T> List<Module>.part(type: ModuleType): T = first { it.type == type } as T

    @Test
    fun `every implemented type registers as a part`() =
        runTest {
            assertEquals(
                setOf(
                    ModuleType.RULE,
                    ModuleType.REPORTER,
                    ModuleType.PROFILE,
                    ModuleType.CLI_COMMAND,
                    ModuleType.ENRICHMENT_PROVIDER,
                    ModuleType.SCRIPT,
                ),
                bridged().map { it.type }.toSet(),
            )
        }

    @Test
    fun `rule part matches the module`() =
        runTest {
            val rule = bridged().part<RuleModule>(ModuleType.RULE)

            assertEquals(direct.contributedRules(), rule.contributedRules())
            assertEquals(direct.validate(RULE, mr, config), rule.validate(RULE, mr, config))
            assertEquals(direct.ruleExplanation(RULE), rule.ruleExplanation(RULE))
        }

    @Test
    fun `reporter part matches the module`() =
        runTest {
            val reporter = bridged().part<ReporterModule>(ModuleType.REPORTER)
            val findings = direct.validate(RULE, mr, config)

            assertEquals(direct.formatId, reporter.formatId)
            assertEquals(direct.report(findings, false, config), reporter.report(findings, false, config))
        }

    @Test
    fun `profile part matches the module`() =
        runTest {
            val profile = bridged().part<ProfileModule>(ModuleType.PROFILE)

            assertEquals(direct.profileId, profile.profileId)
            assertEquals(direct.summary, profile.summary)
            assertEquals(direct.tags(), profile.tags())
            assertEquals(direct.ruleConfigs(), profile.ruleConfigs())
        }

    @Test
    fun `enrichment provider part matches the module`() =
        runTest {
            val provider = bridged().part<EnrichmentProviderModule>(ModuleType.ENRICHMENT_PROVIDER)

            assertEquals(direct.providedEnrichment, provider.providedEnrichment)
            assertEquals(direct.enrich(mr, config), provider.enrich(mr, config))
        }

    @Test
    fun `script part matches the module`() =
        runTest {
            val script = bridged().part<ScriptModule>(ModuleType.SCRIPT)
            val scripts = listOf<JsonElement>(JsonPrimitive("a"), JsonPrimitive("b"))
            val context = RecordingPluginContext()

            assertEquals(direct.execute(scripts, mr, context), script.execute(scripts, mr, context))
        }

    @Test
    fun `cli command part lists and runs the subcommand`() =
        runTest {
            val commands = bridged().part<CliCommandModule>(ModuleType.CLI_COMMAND).cliCommands()
            val leaf = commands.single().subcommands.single()
            val out = mutableListOf<String>()
            val err = mutableListOf<String>()

            val exitCode = checkNotNull(leaf.run)(
                PluginCommandContext(listOf("a", "b"), RecordingPluginContext(), { out += it }, {
                    err +=
                        it
                })
            )

            assertEquals(listOf("parity" to "echo"), commands.map { it.name to leaf.name })
            assertEquals(EXIT_CODE, exitCode)
            assertEquals(listOf("ran a b"), out)
            assertEquals(listOf("warned"), err)
        }

    private companion object {
        const val RULE = "parityRule"
        const val EXIT_CODE = 3
    }
}
