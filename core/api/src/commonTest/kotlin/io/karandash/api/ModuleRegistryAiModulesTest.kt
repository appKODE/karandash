package io.karandash.api

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
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
import io.karandash.api.ai.AiTokenAllocatorModule
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModuleRegistryAiModulesTest {
    private fun registry(): ModuleRegistryImpl = ModuleRegistryImpl()

    // -- Stub implementations --

    private class StubAnalyzer(
        override val analyzerId: String,
        override val id: String = "analyzer-$analyzerId",
        override val defaultPriority: Int = 100,
    ) : AiAnalyzerModule {
        override val name = "Stub Analyzer"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = emptyList()
    }

    private class StubPromptProcessor(
        override val id: String,
        override val processorPriority: Int = 100,
    ) : AiPromptProcessorModule {
        override val name = "Stub Processor"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun processPrompt(
            prompt: String,
            context: AiPromptProcessorContext
        ) = prompt
    }

    private class StubResponseTransformer(
        override val id: String,
        override val transformerPriority: Int = 100,
    ) : AiResponseTransformerModule {
        override val name = "Stub Transformer"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun transform(
            response: AiCompletionResponse,
            request: AiCompletionRequest,
            context: AiResponseTransformerContext,
        ) = response
    }

    private class StubContextEnricher(
        override val id: String,
        override val enricherPriority: Int = 100,
    ) : AiContextEnricherModule {
        override val name = "Stub Enricher"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun enrich(
            mr: MergeRequest,
            context: AiContextEnricherContext
        ) = emptyMap<String, String>()
    }

    private class StubRedactor(
        override val id: String,
        override val redactorPriority: Int = 100,
    ) : AiRedactorModule {
        override val name = "Stub Redactor"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun redact(
            text: String,
            context: AiRedactorContext
        ) = text
    }

    private class StubTokenAllocator(
        override val id: String,
        override val defaultPriority: Int = 100,
    ) : AiTokenAllocatorModule {
        override val name = "Stub Allocator"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun allocate(
            rules: Set<String>,
            totalBudget: Int,
            priorities: Map<String, Int>
        ) = rules.associateWith { totalBudget / rules.size }
    }

    // -- Tests --

    @Test
    fun registerAndRetrieveAiAnalyzers() =
        runTest {
            val reg = registry()
            val a1 = StubAnalyzer("custom-check-a", defaultPriority = 50)
            val a2 = StubAnalyzer("custom-check-b", defaultPriority = 200)
            reg.register(a1)
            reg.register(a2)

            val analyzers = reg.aiAnalyzers()
            assertEquals(2, analyzers.size)
            assertEquals("custom-check-a", analyzers[0].analyzerId, "Lower priority should come first")
            assertEquals("custom-check-b", analyzers[1].analyzerId)
        }

    @Test
    fun findAiAnalyzerByIdReturnsMatch() =
        runTest {
            val reg = registry()
            reg.register(StubAnalyzer("my-analyzer"))

            assertNotNull(reg.findAiAnalyzer("my-analyzer"))
            assertNull(reg.findAiAnalyzer("nonexistent"))
        }

    @Test
    fun aiPromptProcessorsSortedByPriority() =
        runTest {
            val reg = registry()
            reg.register(StubPromptProcessor("proc-high", processorPriority = 200))
            reg.register(StubPromptProcessor("proc-low", processorPriority = 10))

            val processors = reg.aiPromptProcessors()
            assertEquals(2, processors.size)
            assertEquals("proc-low", processors[0].id)
            assertEquals("proc-high", processors[1].id)
        }

    @Test
    fun aiResponseTransformersSortedByPriority() =
        runTest {
            val reg = registry()
            reg.register(StubResponseTransformer("tx-b", transformerPriority = 300))
            reg.register(StubResponseTransformer("tx-a", transformerPriority = 50))

            val transformers = reg.aiResponseTransformers()
            assertEquals("tx-a", transformers[0].id)
            assertEquals("tx-b", transformers[1].id)
        }

    @Test
    fun aiContextEnrichersSortedByPriority() =
        runTest {
            val reg = registry()
            reg.register(StubContextEnricher("enrich-2", enricherPriority = 200))
            reg.register(StubContextEnricher("enrich-1", enricherPriority = 50))

            val enrichers = reg.aiContextEnrichers()
            assertEquals("enrich-1", enrichers[0].id)
        }

    @Test
    fun aiRedactorsSortedByPriority() =
        runTest {
            val reg = registry()
            reg.register(StubRedactor("redact-z", redactorPriority = 999))
            reg.register(StubRedactor("redact-a", redactorPriority = 1))

            val redactors = reg.aiRedactors()
            assertEquals("redact-a", redactors[0].id)
        }

    @Test
    fun aiTokenAllocatorReturnsHighestPriority() =
        runTest {
            val reg = registry()
            reg.register(StubTokenAllocator("alloc-low", defaultPriority = 200))
            reg.register(StubTokenAllocator("alloc-high", defaultPriority = 10))

            val allocator = reg.aiTokenAllocator()
            assertNotNull(allocator)
            assertEquals("alloc-high", allocator.id)
        }

    @Test
    fun aiTokenAllocatorReturnsNullWhenNoneRegistered() =
        runTest {
            val reg = registry()
            assertNull(reg.aiTokenAllocator())
        }

    @Test
    fun unregisterCleansUpAiModules() =
        runTest {
            val reg = registry()
            val analyzer = StubAnalyzer("to-remove")
            reg.register(analyzer)
            assertNotNull(reg.findAiAnalyzer("to-remove"))

            reg.unregister(analyzer.id)
            assertNull(reg.findAiAnalyzer("to-remove"))
            assertTrue(reg.aiAnalyzers().isEmpty())
        }

    @Test
    fun getByTypeReturnsCorrectModuleTypes() =
        runTest {
            val reg = registry()
            reg.register(StubAnalyzer("a"))
            reg.register(StubPromptProcessor("p"))
            reg.register(StubRedactor("r"))

            assertEquals(1, reg.getByType(ModuleType.AI_ANALYZER).size)
            assertEquals(1, reg.getByType(ModuleType.AI_PROMPT_PROCESSOR).size)
            assertEquals(1, reg.getByType(ModuleType.AI_REDACTOR).size)
            assertEquals(0, reg.getByType(ModuleType.AI_TOKEN_ALLOCATOR).size)
        }
}
