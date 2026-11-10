package io.karandash.loader.bridge

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.loader.RecordingLogger
import io.karandash.loader.bridge.ai.BridgeAiAnalyzerModule
import io.karandash.loader.bridge.ai.BridgeAiContextEnricherModule
import io.karandash.loader.bridge.ai.BridgeAiInterceptorModule
import io.karandash.loader.bridge.ai.BridgeAiMetricModule
import io.karandash.loader.bridge.ai.BridgeAiPromptProcessorModule
import io.karandash.loader.bridge.ai.BridgeAiProviderModule
import io.karandash.loader.bridge.ai.BridgeAiRedactorModule
import io.karandash.loader.bridge.ai.BridgeAiReportModule
import io.karandash.loader.bridge.ai.BridgeAiResponseTransformerModule
import io.karandash.loader.bridge.ai.BridgeAiTokenAllocatorModule
import io.karandash.loader.bridge.host.FakeAi
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.bridge.host.HostTestContext
import io.karandash.loader.bridge.host.RecordingBudget
import io.karandash.loader.bridge.host.analyzerContext
import io.karandash.loader.bridge.modules.BridgeEnrichedReporterModule
import io.karandash.loader.bridge.modules.BridgeEnrichmentProviderModule
import io.karandash.loader.bridge.modules.BridgeMutatorModule
import io.karandash.loader.bridge.modules.BridgePlatformServiceModule
import io.karandash.loader.bridge.modules.BridgeProfileModule
import io.karandash.loader.bridge.modules.BridgeProviderModule
import io.karandash.loader.bridge.modules.BridgeReporterModule
import io.karandash.loader.bridge.modules.BridgeRuleModule
import io.karandash.loader.bridge.modules.BridgeScriptModule
import io.karandash.loader.bridge.modules.FakeBridgeTransport
import io.karandash.loader.createDirectories
import io.karandash.loader.deleteTestDirectory
import io.karandash.loader.js.JsPluginLoader
import io.karandash.loader.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds

/**
 * One guest contract on every transport: each per-type part reads its identity through the transport and answers the
 * type it serves. Transport-specific parity (JAR, JS, WASM, native) plugs in by swapping [FakeBridgeTransport] for a
 * fake of that transport.
 */
class GuestTransportParityTest {
    private val transport = FakeBridgeTransport(props = FakeBridgeTransport.IDENTITY)

    private val parts: Map<ModuleType, BridgePart> =
        mapOf(
            ModuleType.RULE to BridgeRuleModule(transport),
            ModuleType.REPORTER to BridgeReporterModule(transport),
            ModuleType.PLATFORM_SERVICE to BridgePlatformServiceModule(transport),
            ModuleType.PROFILE to BridgeProfileModule(transport),
            ModuleType.SCRIPT to BridgeScriptModule(transport),
            ModuleType.ENRICHMENT_PROVIDER to BridgeEnrichmentProviderModule(transport),
            ModuleType.AI_ANALYZER to BridgeAiAnalyzerModule(transport),
            ModuleType.AI_CONTEXT_ENRICHER to BridgeAiContextEnricherModule(transport),
            ModuleType.AI_INTERCEPTOR to BridgeAiInterceptorModule(transport),
            ModuleType.AI_METRIC to BridgeAiMetricModule(transport),
            ModuleType.AI_PROMPT_PROCESSOR to BridgeAiPromptProcessorModule(transport),
            ModuleType.AI_PROVIDER to BridgeAiProviderModule(transport),
            ModuleType.AI_REDACTOR to BridgeAiRedactorModule(transport),
            ModuleType.AI_REPORT to BridgeAiReportModule(transport),
            ModuleType.AI_RESPONSE_TRANSFORMER to BridgeAiResponseTransformerModule(transport),
            ModuleType.AI_TOKEN_ALLOCATOR to BridgeAiTokenAllocatorModule(transport),
        )

    @Test
    fun `every part reads its identity through the transport and answers its own type`() {
        val all = parts.entries.map { it.key to it.value } +
            listOf(
                ModuleType.REPORTER to BridgeEnrichedReporterModule(transport),
                ModuleType.PLATFORM_SERVICE to BridgeMutatorModule(transport),
                ModuleType.PLATFORM_SERVICE to BridgeProviderModule(transport),
            )
        for ((type, part) in all) {
            assertEquals(type, part.type, part::class.simpleName)
            assertEquals(Triple("g", "Guest", "1.0"), Triple(part.id, part.name, part.version), part::class.simpleName)
        }
    }

    /**
     * One host-call script, two ways in: straight into a [HostServiceDispatcher] and from a JS guest's
     * `__karandash_host_call` inside `analyze`. Same replies, same cache keys, logs and budget spend. The native
     * (`JnaHostChannelJvmTest`, `NativeHostChannelTest`) and WASM transports reach the same dispatcher.
     */
    @Test
    fun `a host call script answers the same through the dispatcher and a JS guest`() =
        runTest {
            val direct = HostTestContext()
            val directAi = FakeAi(reply = "llm")
            val directBudget = RecordingBudget()
            val directSkips = mutableListOf<String>()
            val host = HostServiceDispatcher({ PARITY_ID }, RecordingLogger()) { direct }
            val directContext = analyzerContext(directAi, directBudget).copy(onSkip = { directSkips += it })
            val expected = host.withAnalyzer(directContext) { HOST_SCRIPT.map { (s, r) -> host.call(s, r) } }

            val dir = TestFiles.createTestTempDirectory("parity-host").also { createDirectories(it) }
            try {
                val guest = HostTestContext()
                val guestAi = FakeAi(reply = "llm")
                val guestBudget = RecordingBudget()
                val guestSkips = mutableListOf<String>()
                val replies = jsGuestReplies(dir, guest, analyzerContext(guestAi, guestBudget).copy(onSkip = { guestSkips += it }))

                assertEquals(expected, replies)
                assertEquals(direct.cache.entries, guest.cache.entries)
                assertEquals(direct.logs + direct.warnings, guest.logs + guest.warnings)
                assertEquals(directBudget.spent, guestBudget.spent)
                assertEquals(directAi.requests, guestAi.requests)
                assertEquals(listOf("no diff"), guestSkips)
                assertEquals(directSkips, guestSkips)
            } finally {
                deleteTestDirectory(dir)
            }
        }

    private suspend fun jsGuestReplies(
        dir: Path,
        context: HostTestContext,
        analyze: AiAnalyzerContext,
    ): List<String> {
        val script = JsonArray(HOST_SCRIPT.map { (s, r) -> JsonArray(listOf(JsonPrimitive(s), JsonPrimitive(r))) })
        Path("$dir/$PARITY_ID.js").writeText(
            """
            module.exports = {
                id: "$PARITY_ID", name: "$PARITY_ID", version: "1.0.0", type: "AI_ANALYZER", analyzerId: "$PARITY_ID",
                initialize(c) { return true; },
                analyze(mrJson, budgetJson, model, configJson) {
                    return $script.map(function (s) { return { severity: "INFO", message: __karandash_host_call(s[0], s[1]), rule: "p" }; });
                }
            };
            """.trimIndent(),
        )
        val registry = ModuleRegistryImpl()
        JsPluginLoader(registry, RecordingLogger(), guestCallTimeout = 5.seconds).loadFromDirectories(listOf(dir), context)
        val module = registry.get(PARITY_ID) as AiAnalyzerModule
        return module.analyze(analyze).map { it.message }
    }

    private companion object {
        const val PARITY_ID = "parity"

        val HOST_SCRIPT =
            listOf(
                "cache.put" to """{"cache":"c","key":"k","value":"v"}""",
                "cache.get" to """{"cache":"c","key":"k"}""",
                "cache.get" to """{"cache":"c","key":"missing"}""",
                "log" to """{"level":"info","message":"hello"}""",
                "log" to """{"level":"warn","message":"careful"}""",
                "ai.complete" to """{"prompt":"p","maxTokens":5}""",
                "resolveContent" to """{"source":"text"}""",
                "skip" to """{"reason":"no diff"}""",
                "no.such.service" to "{}",
                "cache.get" to "not json",
            )
    }
}
