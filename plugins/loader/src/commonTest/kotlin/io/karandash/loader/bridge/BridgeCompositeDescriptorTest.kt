package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.ModuleDescriptor
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.ReporterModule
import io.karandash.loader.RecordingLogger
import io.karandash.loader.bridge.modules.BridgeEnrichedReporterModule
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** A composite module (several module interfaces on one class) describes every type; the host warns on the extra ones. */
@OptIn(ExperimentalKarandashApi::class)
class BridgeCompositeDescriptorTest {
    private class CompositeReporter :
        ReporterModule,
        PipelineExtension {
        override val id = "composite"
        override val name = id
        override val version = "1"
        override val type = ModuleType.REPORTER
        override val formatId = id
        override val isDefaultCI = true

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String? = null
    }

    private val transport = AbiTableTransportStub()

    @Test
    fun `ModuleDescriptor of lists every implemented type with the primary first and keeps its flags`() {
        val descriptor = ModuleDescriptor.of(CompositeReporter())

        assertEquals(listOf("REPORTER", "PIPELINE_EXTENSION"), descriptor.types)
        assertEquals(JsonPrimitive(true), descriptor.properties[ModuleDescriptor.IS_DEFAULT_CI])
    }

    @Test
    fun `a composite guest registers as its own type and warns about the others`() {
        val logger = RecordingLogger()
        val json = KarandashJson.Default.encodeToString(ModuleDescriptor.serializer(), ModuleDescriptor.of(CompositeReporter()))
        val descriptor = BridgeDescriptor.parse(json, "composite", logger)

        val reporter = assertIs<ReporterModule>(bridgeFor(BridgeEnrichedReporterModule(transport), descriptor, transport))

        assertTrue(reporter.isDefaultCI)
        val warning = logger.warnings.single()
        assertTrue("declares types PIPELINE_EXTENSION besides REPORTER" in warning, warning)
    }

    @Test
    fun `a single type guest registers without a warning`() {
        val logger = RecordingLogger()
        val descriptor = BridgeDescriptor.parse("""{"id":"r","types":["REPORTER"]}""", "r", logger)

        bridgeFor(BridgeEnrichedReporterModule(transport), descriptor, transport)

        assertEquals(emptyList(), logger.warnings)
    }

    @Test
    fun `servedTypes keeps the primary first and drops unknown and unservable types with a warning`() {
        val logger = RecordingLogger()
        val descriptor =
            BridgeDescriptor.parse(
                """{"id":"m","types":["RULE","AI_PIPELINE_STAGE","PROFILE","NOT_A_TYPE","RULE","script"]}""",
                "m",
                logger,
            )

        assertEquals(listOf(ModuleType.RULE, ModuleType.PROFILE, ModuleType.SCRIPT), descriptor.servedTypes(ModuleType.RULE))
        assertEquals(2, logger.warnings.size, logger.warnings.toString())
        assertTrue(logger.warnings.any { "AI_PIPELINE_STAGE" in it && "no SDK interface" in it })
        assertTrue(logger.warnings.any { "NOT_A_TYPE" in it })
    }

    @Test
    fun `a composite guest yields one part per served type and each reports its own type`() {
        val json = KarandashJson.Default.encodeToString(ModuleDescriptor.serializer(), ModuleDescriptor.of(CompositeReporter()))
        val descriptor = BridgeDescriptor.parse(json, "composite")
        val guest = TransportModuleBridge(transport, "composite", "composite", "1", ModuleType.REPORTER, descriptor)

        val parts = guest.asRegisteredModules()

        assertEquals(listOf(ModuleType.REPORTER, ModuleType.PIPELINE_EXTENSION), parts.map { it.type })
        assertTrue(assertIs<ReporterModule>(parts[0]).isDefaultCI)
        assertIs<PipelineExtension>(parts[1])
    }
}

/** A transport that exports nothing: every guest property falls back to its default. */
internal class AbiTableTransportStub : AbiTableTransport("stub", RecordingLogger(), "Test") {
    override fun exports(op: BridgeOp) = false

    override fun invoke(
        op: BridgeOp,
        args: Array<out String>,
    ): String? = null
}
