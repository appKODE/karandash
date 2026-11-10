package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiMetricModule
import io.karandash.api.ai.AiReportModule
import io.karandash.api.ai.MetricResult
import io.karandash.api.ai.ReportContext
import io.karandash.api.ai.ReportInput
import io.karandash.api.ai.ReportResult
import io.karandash.api.finding.FindingSummary
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.LocationKey
import io.karandash.api.finding.MessageCode
import io.karandash.api.finding.ReportMetadata
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.EnrichmentProviderModule
import io.karandash.api.module.FormatModule
import io.karandash.api.module.OutputModule
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.loader.RecordingLogger
import io.karandash.loader.bridge.modules.BridgeEnrichedReporterModule
import io.karandash.loader.bridge.modules.BridgeEnrichmentProviderModule
import io.karandash.loader.bridge.modules.BridgePlatformServiceModule
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** FORMAT, OUTPUT and PIPELINE_EXTENSION guests reach the host through [bridgeFor]'s proxies. */
@OptIn(ExperimentalKarandashApi::class)
class BridgeHostProxiesTest {
    private class FakeTransport(
        private val results: Map<String, String?> = emptyMap(),
        private val exported: Set<String>? = null,
    ) : AbiTableTransport("fake", RecordingLogger(), "Test") {
        val calls = mutableListOf<Pair<String, List<String>>>()

        override fun exports(op: BridgeOp): Boolean = exported?.contains(op.op) ?: true

        override fun invoke(
            op: BridgeOp,
            args: Array<out String>,
        ): String? {
            calls += op.op to args.toList()
            return results[op.op]
        }
    }

    private class Guest(
        override val type: ModuleType,
    ) : Module {
        override val id: String = "guest"
        override val name: String = "Guest"
        override val version: String = "1.0"

        override suspend fun initialize(context: PluginContext): Boolean = true
    }

    private val emptyDescriptor = BridgeDescriptor(JsonObject(emptyMap()), "guest")
    private val finding = Finding(Severity.WARNING, "w", "r1")

    private fun proxy(
        type: ModuleType,
        transport: BridgeTransport,
        descriptor: BridgeDescriptor = emptyDescriptor,
    ) = bridgeFor(Guest(type), descriptor, transport)

    private fun report() =
        ValidationReport(
            passed = true,
            findings = emptyList(),
            summary = FindingSummary(0, 0, 0, emptyList(), emptyList(), emptyList()),
            groupedByLocation = emptyMap(),
            inlineEligible = emptyList(),
            summaryOnly = emptyList(),
            complianceChecklist = emptyList(),
            explanations = emptyMap(),
            metadata = ReportMetadata(version = "test", timestamp = 0L),
        )

    @Test
    fun `a FORMAT guest becomes a FormatModule that decodes a FormattedReport object`() =
        runTest {
            val transport = FakeTransport(mapOf("formatId" to "sarif", "format" to """{"formatId":"sarif","body":"B"}"""))
            val format = assertIs<FormatModule>(proxy(ModuleType.FORMAT, transport))
            assertEquals("sarif", format.formatId)
            assertEquals("sarif", format.configKey)
            assertEquals(FormattedReport("sarif", "B"), format.format(report(), JsonObject(emptyMap())))
            assertTrue(Json.parseToJsonElement(transport.calls.last().second[0]) is JsonObject)
        }

    @Test
    fun `a plain string format result becomes the report body`() =
        runTest {
            val transport = FakeTransport(mapOf("format" to "plain text"))
            val format = assertIs<FormatModule>(proxy(ModuleType.FORMAT, transport))
            assertEquals(FormattedReport("guest", "plain text"), format.format(report(), JsonObject(emptyMap())))
        }

    @Test
    fun `an OUTPUT guest error message fails the output call and a null result succeeds`() =
        runTest {
            val ok = assertIs<OutputModule>(proxy(ModuleType.OUTPUT, FakeTransport()))
            ok.output(FormattedReport("md", "x"), JsonObject(emptyMap()))
            val failing = assertIs<OutputModule>(proxy(ModuleType.OUTPUT, FakeTransport(mapOf("output" to "disk full"))))
            val error = assertFailsWith<IllegalStateException> { failing.output(FormattedReport("md", "x"), JsonObject(emptyMap())) }
            assertTrue(error.message.orEmpty().contains("disk full"))
        }

    @Test
    fun `output flags round-trip from ModuleDescriptor of to the proxy`() {
        val host = object : OutputModule {
            override val id = "o"
            override val name = "O"
            override val version = "1"
            override val outputId = "o"
            override val acceptedFormats = setOf("md", "json")
            override val requiresPlatformContext = true
            override val supportsFileOutput = true
            override val preferredFormat = "md"
            override val hasExternalSideEffects = false
            override val isDefaultPlatform = true

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun output(
                report: FormattedReport,
                config: JsonElement,
            ) = Unit
        }
        val json = KarandashJson.Default.encodeToString(ModuleDescriptor.serializer(), ModuleDescriptor.of(host))
        val output = assertIs<OutputModule>(proxy(ModuleType.OUTPUT, FakeTransport(), BridgeDescriptor.parse(json, "o")))
        assertEquals(setOf("md", "json"), output.acceptedFormats)
        assertTrue(output.requiresPlatformContext)
        assertTrue(output.supportsFileOutput)
        assertEquals("md", output.preferredFormat)
        assertEquals(false, output.hasExternalSideEffects)
        assertTrue(output.isDefaultPlatform)
        assertEquals(false, output.isDefaultInteractive)
    }

    @Test
    fun `reporter flags round-trip from ModuleDescriptor of to the proxy`() {
        val host = object : ReporterModule {
            override val id = "r"
            override val name = "R"
            override val version = "1"
            override val formatId = "r"
            override val requiresPlatformContext = true
            override val supportsLiveMode = true
            override val hasExternalSideEffects = false
            override val isDefaultReporter = true
            override val isDefaultInteractive = true
            override val isDefaultCI = true
            override val supportsFileOutput = true

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun report(
                findings: List<Finding>,
                passed: Boolean,
                config: JsonElement,
            ): String? = null
        }
        val json = KarandashJson.Default.encodeToString(ModuleDescriptor.serializer(), ModuleDescriptor.of(host))
        val transport = FakeTransport()
        val reporter =
            assertIs<ReporterModule>(bridgeFor(BridgeEnrichedReporterModule(transport), BridgeDescriptor.parse(json, "r"), transport))
        assertTrue(reporter.supportsLiveMode)
        assertTrue(reporter.requiresPlatformContext)
        assertEquals(false, reporter.hasExternalSideEffects)
        assertTrue(reporter.isDefaultReporter)
        assertTrue(reporter.isDefaultInteractive)
        assertTrue(reporter.isDefaultCI)
        assertTrue(reporter.supportsFileOutput)
        val plain = assertIs<ReporterModule>(bridgeFor(BridgeEnrichedReporterModule(transport), emptyDescriptor, transport))
        assertEquals(false, plain.isDefaultCI)
        assertTrue(plain.hasExternalSideEffects)
    }

    @Test
    fun `enrichment provider dependencies come from the descriptor`() {
        val descriptor = BridgeDescriptor.parse(
            """{"id":"e","types":["ENRICHMENT_PROVIDER"],"properties":{"requiredEnrichment":["blame"]}}""",
            "e"
        )
        val transport = FakeTransport()
        val provider = assertIs<EnrichmentProviderModule>(bridgeFor(BridgeEnrichmentProviderModule(transport), descriptor, transport))
        assertEquals(listOf("blame"), provider.requiredEnrichment())
        val plain = assertIs<EnrichmentProviderModule>(bridgeFor(BridgeEnrichmentProviderModule(transport), emptyDescriptor, transport))
        assertTrue(plain.requiredEnrichment().isEmpty())
    }

    @Test
    fun `an output guest without a describe export keeps the SDK defaults`() {
        val output = assertIs<OutputModule>(proxy(ModuleType.OUTPUT, FakeTransport()))
        assertTrue(output.hasExternalSideEffects)
        assertEquals(null, output.preferredFormat)
        assertTrue(output.acceptedFormats.isEmpty())
    }

    @Test
    fun `pipeline extension blame and head content flags round-trip from ModuleDescriptor of to the proxy`() {
        val host =
            object : PipelineExtension {
                override val id = "p"
                override val type = ModuleType.PIPELINE_EXTENSION
                override val name = "P"
                override val version = "1"

                override suspend fun initialize(context: PluginContext) = true

                override fun needsBlame(config: JsonElement?) = true

                override fun needsHeadContent(config: JsonElement?) = true
            }
        val json = KarandashJson.Default.encodeToString(ModuleDescriptor.serializer(), ModuleDescriptor.of(host))
        val ext = assertIs<PipelineExtension>(proxy(ModuleType.PIPELINE_EXTENSION, FakeTransport(), BridgeDescriptor.parse(json, "p")))
        assertTrue(ext.needsBlame(null))
        assertTrue(ext.needsHeadContent(null))
        val plain = assertIs<PipelineExtension>(proxy(ModuleType.PIPELINE_EXTENSION, FakeTransport()))
        assertEquals(false, plain.needsHeadContent(null))
    }

    @Test
    fun `pipeline findings hooks return the guest findings`() =
        runTest {
            val replaced = Finding(Severity.ERROR, "e", "r2")
            val encoded = KarandashJson.Default.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(Finding.serializer()),
                listOf(replaced)
            )
            val transport = FakeTransport(mapOf("afterValidation" to encoded, "beforeReporting" to encoded))
            val ext = assertIs<PipelineExtension>(proxy(ModuleType.PIPELINE_EXTENSION, transport))
            assertEquals(listOf(replaced), ext.afterValidation(listOf(finding), createTestMergeRequest(), null))
            assertEquals(listOf(replaced), ext.beforeReporting(listOf(finding), createTestMergeRequest()))
            assertEquals(
                3,
                transport.calls
                    .first { it.first == "afterValidation" }
                    .second.size
            )
        }

    @Test
    fun `a malformed findings hook result keeps the input findings`() =
        runTest {
            val ext = assertIs<PipelineExtension>(proxy(ModuleType.PIPELINE_EXTENSION, FakeTransport(mapOf("afterValidation" to "nope"))))
            assertEquals(listOf(finding), ext.afterValidation(listOf(finding), createTestMergeRequest(), null))
        }

    @Test
    fun `pipeline hooks the guest does not export are never called`() =
        runTest {
            val transport = FakeTransport(exported = emptySet())
            val ext = assertIs<PipelineExtension>(proxy(ModuleType.PIPELINE_EXTENSION, transport))
            ext.onConfigured(PipelineEnvironment(null, null, "stats"))
            ext.onPostFetch(createTestMergeRequest(), null)
            assertEquals(listOf(finding), ext.beforeReporting(listOf(finding), createTestMergeRequest()))
            ext.onPipelineComplete()
            ext.invalidateRules(setOf("r1"))
            assertTrue(transport.calls.isEmpty())
        }

    @Test
    fun `onConfigured and invalidateRules send JSON payloads`() =
        runTest {
            val transport = FakeTransport()
            val ext = assertIs<PipelineExtension>(proxy(ModuleType.PIPELINE_EXTENSION, transport))
            ext.onConfigured(PipelineEnvironment(null, JsonObject(emptyMap()), "/tmp/stats", "gitlab"))
            ext.invalidateRules(setOf("r1"))
            ext.onPipelineComplete()
            val env = Json.parseToJsonElement(transport.calls[0].second.single()).jsonObject
            assertEquals("/tmp/stats", env["statsDir"]?.jsonPrimitive?.content)
            assertEquals("false", env["aiServiceAvailable"]?.jsonPrimitive?.content)
            assertEquals("gitlab", env["platformId"]?.jsonPrimitive?.content)
            assertEquals("""["r1"]""", transport.calls[1].second.single())
            assertEquals("onPipelineComplete", transport.calls[2].first)
        }

    @Test
    fun `AI report requiredInputs come from the descriptor`() {
        val guest = object : AiReportModule {
            override val id = "rep"
            override val name = "Rep"
            override val version = "1"
            override val reportId = "rep"
            override val reportName = "Rep"

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun generate(context: ReportContext): ReportResult = error("unused")
        }
        val descriptor = BridgeDescriptor.parse(
            """{"id":"rep","types":["AI_REPORT"],"properties":{"requiredInputs":["HISTORY","FUTURE"]}}""",
            "rep"
        )
        val report = assertIs<AiReportModule>(bridgeFor(guest, descriptor, FakeTransport()))
        assertEquals(setOf(ReportInput.HISTORY), report.requiredInputs)
        assertEquals(
            setOf(ReportInput.FINDINGS),
            assertIs<AiReportModule>(bridgeFor(guest, emptyDescriptor, FakeTransport())).requiredInputs
        )
    }

    @Test
    fun `AI metric requiredInputs come from the descriptor`() {
        val guest = object : AiMetricModule {
            override val id = "met"
            override val name = "Met"
            override val version = "1"
            override val metricId = "met"
            override val metricName = "Met"
            override val unit = "count"

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun compute(context: ReportContext): MetricResult = error("unused")
        }
        val descriptor = BridgeDescriptor.parse(
            """{"id":"met","types":["AI_METRIC"],"properties":{"requiredInputs":["FINDINGS","HISTORY"]}}""",
            "met"
        )
        val metric = assertIs<AiMetricModule>(bridgeFor(guest, descriptor, FakeTransport()))
        assertEquals(setOf(ReportInput.FINDINGS, ReportInput.HISTORY), metric.requiredInputs)
        assertEquals(
            setOf(ReportInput.FINDINGS),
            assertIs<AiMetricModule>(bridgeFor(guest, emptyDescriptor, FakeTransport())).requiredInputs
        )
    }

    @Test
    fun `reportEnriched encodes the report with its location map and returns the guest output`() =
        runTest {
            val transport = FakeTransport(mapOf("reportEnriched" to "rendered"))
            val located = report().copy(groupedByLocation = mapOf(LocationKey("a.kt", 3) to listOf(finding)))
            assertEquals("rendered", BridgeEnrichedReporterModule(transport).reportEnriched(located, JsonObject(emptyMap())))
            assertTrue(
                transport.calls
                    .single()
                    .second[0]
                    .contains("a.kt")
            )
        }

    @Test
    fun `a guest without report_enriched is reported through report`() =
        runTest {
            val transport = FakeTransport(mapOf("report" to "plain"), exported = setOf("report"))
            assertEquals("plain", BridgeEnrichedReporterModule(transport).reportEnriched(report(), JsonObject(emptyMap())))
            assertEquals(listOf("report"), transport.calls.map { it.first })
        }

    @Test
    fun `guest messages are decoded and a malformed export yields none`() =
        runTest {
            val messages = FakeTransport(mapOf("messages" to """[{"code":"CUSTOM","text":"hi"}]""")).readMessages()
            assertEquals(MessageCode.CUSTOM, messages.single().code)
            assertEquals("hi", messages.single().text)
            assertTrue(FakeTransport(mapOf("messages" to "{")).readMessages().isEmpty())
        }
}
