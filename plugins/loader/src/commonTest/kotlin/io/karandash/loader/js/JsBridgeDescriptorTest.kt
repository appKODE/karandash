package io.karandash.loader.js

import com.dokar.quickjs.QuickJs
import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ProfileModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.loader.bridge.asRegisteredModules
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds

/**
 * Descriptor parity: SDK properties a JS guest declares reach the module the host registers.
 * Reverting any descriptor read in [JsModuleBridge] / the `BridgeX` proxies to its default fails a test here.
 */
class JsBridgeDescriptorTest {
    private lateinit var engine: QuickJs

    @BeforeTest
    fun setup() {
        engine = QuickJs.create(Dispatchers.Default)
    }

    @AfterTest
    fun teardown() {
        engine.close()
    }

    private suspend fun register(body: String): Module =
        JsModuleBridge.fromSource(engine, "module.exports = { $body };", "guest.js").asRegisteredModules().first()

    @Test
    fun rule_guest_describe_reaches_registered_module() =
        runTest {
            val module = register(
                """
                id: "r", name: "R", version: "1.0.0", type: "RULE",
                describe: function() { return { id: "r", types: ["RULE"], properties: {
                    supportedEnrichment: ["jira"], requiredEnrichment: ["git"],
                    usesExtensionComputedConfig: true, defaultEnabled: false } }; }
                """,
            )
            val rule = assertIs<RuleModule>(module)
            assertEquals(listOf("jira"), rule.supportedEnrichment)
            assertEquals(listOf("git"), rule.requiredEnrichment)
            assertTrue(rule.usesExtensionComputedConfig)
            assertFalse(rule.defaultEnabled)
        }

    @Test
    fun guest_without_describe_uses_plain_fields() =
        runTest {
            val module = register(
                """id: "r", name: "R", version: "1.0.0", type: "RULE",
                supportedEnrichment: ["jira"], usesExtensionComputedConfig: true""",
            )
            val rule = assertIs<RuleModule>(module)
            assertEquals(listOf("jira"), rule.supportedEnrichment)
            assertTrue(rule.usesExtensionComputedConfig)
        }

    @Test
    fun old_guest_gets_sdk_defaults_and_is_enabled() =
        runTest {
            val rule = assertIs<RuleModule>(register("""id: "r", name: "R", version: "1.0.0", type: "RULE""""))
            assertEquals(emptyList(), rule.supportedEnrichment)
            assertEquals(emptyList(), rule.requiredEnrichment)
            assertFalse(rule.usesExtensionComputedConfig)
            assertTrue(rule.defaultEnabled, "bridged rule must default to enabled")
        }

    @Test
    fun analyzer_guest_flags_reach_registered_module() =
        runTest {
            val module = register(
                """id: "a", name: "A", version: "1.0.0", type: "AI_ANALYZER",
                needsScopeDetection: true, enabledByDefault: true, mrLevel: true, supportedEnrichment: ["jira"]""",
            )
            val analyzer = assertIs<AiAnalyzerModule>(module)
            assertTrue(analyzer.needsScopeDetection)
            assertTrue(analyzer.mrLevel)
            assertTrue(analyzer.defaultEnabled)
            assertEquals(listOf("jira"), analyzer.supportedEnrichment)
        }

    @Test
    fun registered_module_has_no_phantom_capabilities() =
        runTest {
            val module = register("""id: "x", name: "X", version: "1.0.0", type: "REPORTER"""")
            assertIs<ReporterModule>(module)
            assertFalse(module is RuleModule)
            assertFalse(module is PlatformServiceModule)
            assertFalse(module is AiAnalyzerModule)
            assertTrue(module.defaultEnabled)
        }

    @Test
    fun console_routes_to_context_logger() =
        runTest {
            val logged = mutableListOf<String>()
            val context = object : PluginContext {
                override val pluginConfig: JsonElement = JsonObject(emptyMap())

                override fun log(message: String) {
                    logged += message
                }

                override fun warn(message: String) {
                    logged += "warn:$message"
                }

                override fun env(name: String): String? = null

                override val hostVersion: String = "test"
                override val workingDirectory: String = "."
            }
            JsContextInjector(engine, context).inject()
            val module = register(
                """id: "c", name: "C", version: "1.0.0", type: "RULE",
                initialize: function(ctx) { console.log("hi", 1); console.error("e"); return true; }""",
            )
            assertTrue(module.initialize(context))
            assertEquals(listOf("hi 1", "warn:e"), logged)
        }

    @Test
    fun composite_guest_registers_one_part_per_declared_type() =
        runTest {
            val parts = JsModuleBridge
                .fromSource(
                    engine,
                    """module.exports = {
                    id: "multi", name: "Multi", version: "1.0.0", type: "RULE",
                    formatId: "multi-report", configFormatId: "multi-config", fileExtensions: ["multi"],
                    profileId: "multi-profile",
                    describe: function() { return { id: "multi",
                        types: ["RULE", "REPORTER", "PROFILE", "CLI_COMMAND", "CONFIG_FORMAT", "AI_PIPELINE_STAGE", "NOT_A_TYPE"],
                        properties: {} }; },
                    contributedRules: function() { return ["multi-rule"]; },
                    report: function(findings, passed, config) { return "report:" + passed; },
                    ruleConfigs: function() { return { multiRule: { on: true } }; },
                    cliCommands: function() { return [{ name: "multi-cmd", help: "h", subcommands: [] }]; },
                    parseToMap: function(content) { return { parsed: content }; }
                    };""",
                    "multi.js",
                ).asRegisteredModules()

            assertEquals(
                listOf(ModuleType.RULE, ModuleType.REPORTER, ModuleType.PROFILE, ModuleType.CLI_COMMAND, ModuleType.CONFIG_FORMAT),
                parts.map { it.type },
            )
            assertTrue(parts.all { it.id == "multi" })
            assertEquals(setOf("multi-rule"), assertIs<RuleModule>(parts[0]).contributedRules())
            val reporter = assertIs<ReporterModule>(parts[1])
            assertEquals("multi-report", reporter.formatId)
            assertEquals("report:true", reporter.report(emptyList(), true, JsonObject(emptyMap())))
            assertEquals("multi-profile", assertIs<ProfileModule>(parts[2]).profileId)
            assertEquals("multi-cmd", assertIs<CliCommandModule>(parts[3]).cliCommands().single().name)
            val format = assertIs<ConfigFormatModule>(parts[4])
            assertEquals("multi-config", format.formatId)
            assertEquals(JsonPrimitive("x"), (format.parseToJsonElement("x") as JsonObject)["parsed"])
            assertFalse(parts[1] is RuleModule, "each part answers only as its own type")
        }

    /** Scalar members a guest declares must be bridged, not left at the SDK default. */
    @Test
    fun `scalar members a guest declares reach its registered parts`() =
        runTest {
            val parts = JsModuleBridge
                .fromSource(
                    engine,
                    """module.exports = {
                    id: "scalars", name: "Scalars", version: "1.0.0", type: "RULE", configFormatId: "scalars-config",
                    describe: function() { return { id: "scalars",
                        types: ["RULE", "AI_ANALYZER", "AI_PROVIDER", "AI_CONTEXT_ENRICHER", "CONFIG_FORMAT"],
                        properties: { executionTimeout: 1500, estimatedTokens: 1234, explanation: "why",
                            responseFormatSuffix: "as json", supportsSemiFormalReasoning: true, requiresApiKey: true,
                            handledEnrichmentIds: ["jira"], priority: 5 } }; }
                    };""",
                    "scalars.js",
                ).asRegisteredModules()

            assertEquals(1500.milliseconds, assertIs<RuleModule>(parts[0]).executionTimeout)
            val analyzer = assertIs<AiAnalyzerModule>(parts[1])
            assertEquals(1234, analyzer.estimatedTokens)
            assertEquals("why", analyzer.explanation)
            assertEquals("as json", analyzer.responseFormatSuffix)
            assertTrue(analyzer.supportsSemiFormalReasoning)
            assertTrue(assertIs<AiProviderModule>(parts[2]).requiresApiKey)
            assertEquals(listOf("jira"), assertIs<AiContextEnricherModule>(parts[3]).handledEnrichmentIds())
            assertEquals(5, assertIs<ConfigFormatModule>(parts[4]).priority)
        }

    /** `dslName` and `hoverDocumentation` must be bridged, not replaced by the ID and no docs. */
    @Test
    fun `dslName and hoverDocumentation a guest declares reach its registered parts`() =
        runTest {
            val parts = JsModuleBridge
                .fromSource(
                    engine,
                    """module.exports = {
                    id: "named", name: "Named", version: "1.0.0", type: "RULE", configFormatId: "named-config",
                    describe: function() { return { id: "named", types: ["RULE", "REPORTER", "CONFIG_FORMAT"],
                        properties: { dslName: "namedRule", hoverDocumentation: "Checks names" } }; }
                    };""",
                    "named.js",
                ).asRegisteredModules()

            assertEquals(listOf("namedRule"), parts.map { it.dslName }.distinct())
            assertEquals(listOf("Checks names"), parts.map { it.hoverDocumentation }.distinct())
            val plain = register("""id: "plain", name: "P", version: "1.0.0", type: "RULE"""")
            assertEquals("plain", plain.dslName)
            assertEquals(null, plain.hoverDocumentation)
        }

    @Test
    fun single_type_guest_registers_one_part() =
        runTest {
            val parts = JsModuleBridge
                .fromSource(engine, """module.exports = { id: "r", name: "R", version: "1.0.0", type: "RULE" };""", "r.js")
                .asRegisteredModules()
            assertEquals(listOf(ModuleType.RULE), parts.map { it.type })
        }
}
