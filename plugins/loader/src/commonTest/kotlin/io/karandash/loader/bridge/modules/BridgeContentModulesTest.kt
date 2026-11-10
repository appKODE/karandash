package io.karandash.loader.bridge.modules

import io.karandash.api.ModuleType
import io.karandash.api.context.EnrichmentContext
import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.bridge.modules.FakeBridgeTransport.Companion.IDENTITY
import io.karandash.loader.bridge.modules.FakeBridgeTransport.Companion.bare
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.testfixtures.createTestReport
import io.karandash.testfixtures.createTestWarning
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Rule, script, reporter, enrichment and profile bridges: absent capability, guest reply, bad reply and guest failure. */
class BridgeContentModulesTest {
    private val mr = createTestMergeRequest()
    private val config = JsonObject(mapOf("k" to JsonPrimitive(1)))
    private val findingsJson =
        KarandashJson.Default.encodeToString(ListSerializer(Finding.serializer()), listOf(createTestWarning(ruleId = "r1")))

    @Test
    fun `rule bridge reads identity rules findings and explanations`() =
        runTest {
            val guest = FakeBridgeTransport(
                results = mapOf("contributedRules" to """["a","b"]""", "validate" to findingsJson, "ruleExplanation" to "why"),
                props = IDENTITY,
            )
            val rule = BridgeRuleModule(guest)
            assertEquals(Triple("g", "Guest", "1.0"), Triple(rule.id, rule.name, rule.version))
            assertEquals(ModuleType.RULE, rule.type)
            assertEquals(setOf("a", "b"), rule.contributedRules())
            assertEquals(listOf("r1"), rule.validate("r1", mr, config).map { it.rule })
            assertEquals(
                listOf("r1", "{\"k\":1}"),
                guest.calls
                    .single { it.first == "validate" }
                    .second
                    .let { listOf(it[0], it[2]) }
            )
            assertEquals("why", rule.ruleExplanation("r1"))
            assertTrue(rule.initialize(RecordingPluginContext()))
            rule.shutdown()
        }

    @Test
    fun `rule bridge degrades on missing capability null bad and failing replies`() =
        runTest {
            BridgeRuleModule(bare()).let {
                assertEquals(emptySet(), it.contributedRules())
                assertEquals(emptyList(), it.validate("r", mr, config))
                assertNull(it.ruleExplanation("r"))
            }
            BridgeRuleModule(FakeBridgeTransport()).let {
                assertEquals(emptySet(), it.contributedRules())
                assertEquals(emptyList(), it.validate("r", mr, config))
                assertNull(it.ruleExplanation("r"))
            }
            val bad = FakeBridgeTransport(mapOf("contributedRules" to "{", "validate" to "{", "ruleExplanation" to "null"))
            BridgeRuleModule(bad).let {
                assertEquals(emptySet(), it.contributedRules())
                assertEquals(emptyList(), it.validate("r", mr, config))
                assertNull(it.ruleExplanation("r"))
            }
            assertEquals(listOf("validate"), bad.errors)
        }

    @Test
    fun `script bridge returns guest findings and nothing on failure`() =
        runTest {
            val ctx = RecordingPluginContext()
            val script = BridgeScriptModule(FakeBridgeTransport(mapOf("execute" to findingsJson), props = IDENTITY))
            assertEquals(ModuleType.SCRIPT to "g", script.type to script.id)
            assertEquals(1, script.execute(listOf(JsonPrimitive("s")), mr, ctx).size)
            assertEquals(emptyList(), BridgeScriptModule(bare()).execute(emptyList(), mr, ctx))
            assertEquals(emptyList(), BridgeScriptModule(FakeBridgeTransport()).execute(emptyList(), mr, ctx))
            val failing = FakeBridgeTransport(fail = true)
            assertEquals(emptyList(), BridgeScriptModule(failing).execute(emptyList(), mr, ctx))
            assertEquals(listOf("execute"), failing.errors)
            assertTrue(script.initialize(ctx))
            script.shutdown()
        }

    @Test
    fun `reporter bridge returns output and runtime config`() =
        runTest {
            val guest = FakeBridgeTransport(mapOf("report" to "out", "runtimeReporterConfig" to """{"a":1}"""), props = IDENTITY)
            val reporter = BridgeReporterModule(guest)
            assertEquals("unknown" to ModuleType.REPORTER, reporter.formatId to reporter.type)
            assertEquals(Triple("g", "Guest", "1.0"), Triple(reporter.id, reporter.name, reporter.version))
            assertEquals("out", reporter.report(emptyList(), passed = true, config))
            assertEquals("true", guest.calls.first().second[1])
            assertEquals(JsonObject(mapOf("a" to JsonPrimitive(1))), reporter.runtimeReporterConfig(config, config))
            assertEquals("md", BridgeReporterModule(FakeBridgeTransport(props = mapOf("formatId" to "md"))).formatId)
            assertTrue(reporter.initialize(RecordingPluginContext()))
            reporter.shutdown()
        }

    @Test
    fun `reporter bridge degrades on missing capability sentinel bad and failing replies`() =
        runTest {
            val empty = JsonObject(emptyMap())
            BridgeReporterModule(bare()).let {
                assertNull(it.report(emptyList(), true, config))
                assertEquals(empty, it.runtimeReporterConfig(config, config))
            }
            BridgeReporterModule(FakeBridgeTransport(mapOf("report" to "null", "runtimeReporterConfig" to "{"))).let {
                assertNull(it.report(emptyList(), true, config))
                assertEquals(empty, it.runtimeReporterConfig(config, config))
            }
            assertEquals(empty, BridgeReporterModule(FakeBridgeTransport()).runtimeReporterConfig(config, config))
            val failing = FakeBridgeTransport(fail = true)
            BridgeReporterModule(failing).let {
                assertNull(it.report(emptyList(), true, config))
                assertEquals(empty, it.runtimeReporterConfig(config, config))
            }
            assertEquals(listOf("report", "runtimeReporterConfig"), failing.errors)
        }

    @Test
    fun `enriched reporter sends the report or falls back to the plain report`() =
        runTest {
            val report = createTestReport()
            val guest = FakeBridgeTransport(mapOf("reportEnriched" to "rich", "report" to "plain"), props = IDENTITY)
            val reporter = BridgeEnrichedReporterModule(guest)
            assertEquals(Triple("g", "Guest", "1.0"), Triple(reporter.id, reporter.name, reporter.version))
            assertEquals("unknown" to ModuleType.REPORTER, reporter.formatId to reporter.type)
            assertEquals("rich", reporter.reportEnriched(report, config))
            assertEquals("plain", reporter.report(report.findings, report.passed, config))
            val plainOnly = FakeBridgeTransport(mapOf("report" to "plain"), capabilities = setOf("report"))
            assertEquals("plain", BridgeEnrichedReporterModule(plainOnly).reportEnriched(report, config))
            assertNull(BridgeEnrichedReporterModule(FakeBridgeTransport(mapOf("reportEnriched" to "null"))).reportEnriched(report, config))
            assertTrue(reporter.initialize(RecordingPluginContext()))
            reporter.shutdown()
        }

    @Test
    fun `enriched reporter degrades on missing capability and guest failure`() =
        runTest {
            val empty = JsonObject(emptyMap())
            BridgeEnrichedReporterModule(bare()).let {
                assertNull(it.reportEnriched(createTestReport(), config))
                assertEquals(empty, it.runtimeReporterConfig(config, config))
            }
            val runtime = FakeBridgeTransport(mapOf("runtimeReporterConfig" to """{"a":1}"""))
            assertEquals(
                JsonObject(mapOf("a" to JsonPrimitive(1))),
                BridgeEnrichedReporterModule(runtime).runtimeReporterConfig(config, config)
            )
            val failing = FakeBridgeTransport(fail = true)
            BridgeEnrichedReporterModule(failing).let {
                assertNull(it.reportEnriched(createTestReport(), config))
                assertNull(it.report(emptyList(), true, config))
                assertEquals(empty, it.runtimeReporterConfig(config, config))
            }
            assertEquals(listOf("reportEnriched", "report", "runtimeReporterConfig"), failing.errors)
        }

    @Test
    fun `enrichment provider bridge reads ids and enrichment data`() =
        runTest {
            val ctx = EnrichmentContext(emptyMap())
            val guest = FakeBridgeTransport(
                mapOf("enrich" to """{"jira":{"key":"A-1"}}"""),
                props =
                    IDENTITY + ("enrichmentIds" to """["jira"]""")
            )
            val provider = BridgeEnrichmentProviderModule(guest)
            assertEquals(ModuleType.ENRICHMENT_PROVIDER to "g", provider.type to provider.id)
            assertEquals("Guest" to "1.0", provider.name to provider.version)
            assertEquals(setOf("jira"), provider.providedEnrichment)
            assertEquals(setOf("jira"), provider.enrich(mr, config, ctx).keys)
            assertTrue(provider.initialize(RecordingPluginContext()))
            provider.shutdown()

            assertEquals(emptySet(), BridgeEnrichmentProviderModule(bare()).providedEnrichment)
            assertEquals(emptyMap(), BridgeEnrichmentProviderModule(bare()).enrich(mr, config, ctx))
            assertEquals(emptySet(), BridgeEnrichmentProviderModule(FakeBridgeTransport()).providedEnrichment)
            assertEquals(emptyMap(), BridgeEnrichmentProviderModule(FakeBridgeTransport()).enrich(mr, config, ctx))
            val bad = FakeBridgeTransport(props = mapOf("enrichmentIds" to "{"))
            assertEquals(emptySet(), BridgeEnrichmentProviderModule(bad).providedEnrichment)
            val failing = FakeBridgeTransport(fail = true)
            assertEquals(emptyMap(), BridgeEnrichmentProviderModule(failing).enrich(mr, config, ctx))
            assertEquals(listOf("enrich"), failing.errors)
        }

    @Test
    fun `profile bridge reads its properties tags and configs`() =
        runTest {
            val props = IDENTITY + mapOf("summary" to "S", "isDefaultZeroConfigProfile" to "true", "defaultSeverity" to "error")
            val results = mapOf("tags" to """["kmp"]""", "ruleConfigs" to """{"r":{}}""", "reporterConfigs" to """{"md":{}}""")
            val profile = BridgeProfileModule(FakeBridgeTransport(results, props))
            assertEquals(ModuleType.PROFILE to "g", profile.type to profile.profileId)
            assertEquals(Triple("Guest", "1.0", "S"), Triple(profile.name, profile.version, profile.summary))
            assertTrue(profile.isDefaultZeroConfigProfile)
            assertEquals(Severity.ERROR, profile.defaultSeverity)
            assertEquals(setOf("kmp"), profile.tags())
            assertEquals(setOf("r"), profile.ruleConfigs().keys)
            assertEquals(setOf("md"), profile.reporterConfigs().keys)
            assertEquals("p", BridgeProfileModule(FakeBridgeTransport(props = mapOf("profileId" to "p"))).profileId)
            assertTrue(profile.initialize(RecordingPluginContext()))
            profile.shutdown()
        }

    @Test
    fun `profile bridge degrades on missing capability and unusable replies`() =
        runTest {
            BridgeProfileModule(bare()).let {
                assertEquals(Severity.WARNING, it.defaultSeverity)
                assertEquals(emptySet(), it.tags())
                assertEquals(emptyMap(), it.ruleConfigs())
                assertEquals(emptyMap(), it.reporterConfigs())
            }
            BridgeProfileModule(FakeBridgeTransport()).let {
                assertEquals(emptySet(), it.tags())
                assertEquals(emptyMap(), it.ruleConfigs())
                assertEquals(emptyMap(), it.reporterConfigs())
            }
            val arrays = FakeBridgeTransport(mapOf("tags" to "{", "ruleConfigs" to "[]", "reporterConfigs" to "[]"))
            BridgeProfileModule(arrays).let {
                assertEquals(emptySet(), it.tags())
                assertEquals(emptyMap(), it.ruleConfigs())
                assertEquals(emptyMap(), it.reporterConfigs())
            }
            val broken = FakeBridgeTransport(mapOf("ruleConfigs" to "{", "reporterConfigs" to "{"))
            BridgeProfileModule(broken).let {
                assertEquals(emptyMap(), it.ruleConfigs())
                assertEquals(emptyMap(), it.reporterConfigs())
            }
            assertEquals(listOf("ruleConfigs", "reporterConfigs"), broken.errors)
        }
}
