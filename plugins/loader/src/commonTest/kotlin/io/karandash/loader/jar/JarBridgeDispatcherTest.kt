package io.karandash.loader.jar

import io.karandash.api.Module
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextEnricherContext
import io.karandash.api.ai.AiContextEnricherModule
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.ValidationReport
import io.karandash.api.module.FormatModule
import io.karandash.models.MergeRequest
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Discovery reads every property and per-interface priority once; a read that throws is left for a later invoke. */
class JarBridgeDispatcherTest {
    private abstract class Fake(
        override val id: String,
    ) : Module {
        override val name: String get() = id
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true
    }

    private class Redactor :
        Fake("redactor"),
        AiRedactorModule {
        override val redactorPriority = 5

        override suspend fun redact(
            text: String,
            context: AiRedactorContext,
        ) = text
    }

    private class Transformer :
        Fake("transformer"),
        AiResponseTransformerModule {
        override val transformerPriority = 6

        override suspend fun transform(
            response: AiCompletionResponse,
            request: AiCompletionRequest,
            context: AiResponseTransformerContext,
        ) = response
    }

    private class Processor :
        Fake("processor"),
        AiPromptProcessorModule {
        override val processorPriority = 7

        override suspend fun processPrompt(
            prompt: String,
            context: AiPromptProcessorContext,
        ) = prompt
    }

    private class Enricher :
        Fake("enricher"),
        AiContextEnricherModule {
        override val enricherPriority = 8

        override suspend fun enrich(
            mr: MergeRequest,
            context: AiContextEnricherContext,
        ) = emptyMap<String, String>()
    }

    /** Its formatId read throws, as a getter that needs `initialize` would. */
    private class LateFormat :
        Fake("late"),
        FormatModule {
        override val formatId: String get() = error("not initialized")

        override suspend fun format(
            report: ValidationReport,
            config: JsonElement,
        ) = FormattedReport("late", "")
    }

    private suspend fun snapshot(module: Module) = JarBridgeDispatcher.snapshot(module) { TestPluginContext() }

    @Test
    fun `each ai interface reports its own priority alias`() =
        runTest {
            assertEquals("5", snapshot(Redactor())["redactorPriority"])
            assertEquals("6", snapshot(Transformer())["transformerPriority"])
            assertEquals("7", snapshot(Processor())["processorPriority"])
            val enricher = snapshot(Enricher())
            assertEquals("8", enricher["enricherPriority"])
            assertFalse("redactorPriority" in enricher)
            assertTrue("defaultPriority" in enricher)
            assertFalse("messages" in enricher)
        }

    @Test
    fun `a property read that throws is left out of the snapshot`() =
        runTest {
            val values = snapshot(LateFormat())
            assertFalse("formatId" in values)
            assertTrue("minApiLevel" in values, values.toString())
            assertTrue("format" in JarBridgeDispatcher.exports(LateFormat()))
        }

    @Test
    fun `invoke rejects unknown ops and ops of other interfaces`() =
        runTest {
            val call = JarInvocation(emptyList()) { TestPluginContext() }
            assertFailsWith<IllegalArgumentException> { JarBridgeDispatcher.invoke(Redactor(), "no-such-op", call) }
            assertFailsWith<IllegalArgumentException> { JarBridgeDispatcher.invoke(Redactor(), "redactorPriority", call) }
            assertFailsWith<IllegalArgumentException> { JarBridgeDispatcher.invoke(Redactor(), "formatId", call) }
            assertEquals("true", JarBridgeDispatcher.invoke(Redactor(), "isDefaultEnabled", call))
        }
}
