@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.ai.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiInterceptContext
import io.karandash.api.ai.AiInterceptorModule
import io.karandash.api.ai.AiPromptProcessorContext
import io.karandash.api.ai.AiPromptProcessorModule
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiProviderModule
import io.karandash.api.ai.AiRedactorContext
import io.karandash.api.ai.AiRedactorModule
import io.karandash.api.ai.AiResponseTransformerContext
import io.karandash.api.ai.AiResponseTransformerModule
import io.karandash.api.run.RunDump
import io.karandash.api.run.RunServiceKeys
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.sdk.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiServiceImplTest {
    private val stubResponse = AiCompletionResponse(
        content = "response",
        promptTokens = 10,
        completionTokens = 5,
        totalTokens = 15,
        model = "test-model",
    )

    private fun stubProvider(providerId: String = "test"): AiProviderModule =
        object : AiProviderModule {
            override val id = "provider-$providerId"
            override val name = "Test Provider"
            override val version = "1.0"
            override val providerId = providerId

            override suspend fun initialize(context: PluginContext) = true

            override suspend fun complete(request: AiCompletionRequest) = stubResponse
        }

    private fun request(
        prompt: String = "hello",
        systemPrompt: String? = null
    ) = AiCompletionRequest(
        prompt = prompt,
        systemPrompt = systemPrompt,
        model = "test-model",
    )

    @Test
    fun localProviderResolvesWithoutRegistryEntry() =
        runTest {
            val service = AiServiceImpl(
                ModuleRegistryImpl(),
                defaultProviderId = "custom",
                localProviders = mapOf("custom" to stubProvider("custom")),
            )

            assertEquals("response", service.complete(request()).content)
        }

    @Test
    fun completionPassthroughWithNoExtensions() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubProvider())
            val service = AiServiceImpl(registry)

            val result = service.complete(request())
            assertEquals("response", result.content)
        }

    @Test
    fun promptProcessorsAppliedInPriorityOrder() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubProvider())

            registry.register(object : AiPromptProcessorModule {
                override val id = "proc-b"
                override val name = "B"
                override val version = "1.0"
                override val processorPriority = 200

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun processPrompt(
                    prompt: String,
                    context: AiPromptProcessorContext
                ) = "$prompt->B"
            })
            registry.register(object : AiPromptProcessorModule {
                override val id = "proc-a"
                override val name = "A"
                override val version = "1.0"
                override val processorPriority = 50

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun processPrompt(
                    prompt: String,
                    context: AiPromptProcessorContext
                ) = "$prompt->A"
            })

            var capturedPrompt = ""
            registry.unregister("provider-test")
            registry.register(object : AiProviderModule {
                override val id = "provider-capture"
                override val name = "Capture"
                override val version = "1.0"
                override val providerId = "test"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
                    capturedPrompt = request.prompt
                    return stubResponse
                }
            })

            val service = AiServiceImpl(registry)
            service.complete(request("start"))

            assertEquals("start->A->B", capturedPrompt, "Processor A (priority 50) should run before B (priority 200)")
        }

    @Test
    fun redactorsAppliedToPromptAndSystemPrompt() =
        runTest {
            val registry = ModuleRegistryImpl()

            var capturedPrompt = ""
            var capturedSystem: String? = null
            registry.register(object : AiProviderModule {
                override val id = "provider-cap"
                override val name = "Cap"
                override val version = "1.0"
                override val providerId = "test"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
                    capturedPrompt = request.prompt
                    capturedSystem = request.systemPrompt
                    return stubResponse
                }
            })

            registry.register(object : AiRedactorModule {
                override val id = "redact-secret"
                override val name = "Secret"
                override val version = "1.0"
                override val redactorPriority = 10

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun redact(
                    text: String,
                    context: AiRedactorContext
                ) = text.replace("SECRET", "[REDACTED]")
            })

            val service = AiServiceImpl(registry)
            service.complete(request("has SECRET here", "system SECRET prompt"))

            assertEquals("has [REDACTED] here", capturedPrompt)
            assertEquals("system [REDACTED] prompt", capturedSystem)
        }

    @Test
    fun contextBlocksAreRedactedAndFlattenedOnlyForProvidersThatDoNotReadThem() =
        runTest {
            val received = mutableListOf<AiCompletionRequest>()

            fun provider(native: Boolean) =
                object : AiProviderModule {
                    override val id = "provider-$native"
                    override val name = "Cap"
                    override val version = "1.0"
                    override val providerId = "p-$native"
                    override val supportsContextBlocks = native

                    override suspend fun initialize(context: PluginContext) = true

                    override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
                        received += request
                        return stubResponse
                    }
                }
            val registry = ModuleRegistryImpl()
            registry.register(provider(native = true))
            registry.register(provider(native = false))
            registry.register(object : AiRedactorModule {
                override val id = "redact-secret"
                override val name = "Secret"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun redact(
                    text: String,
                    context: AiRedactorContext
                ) = text.replace("SECRET", "[REDACTED]")
            })
            val service = AiServiceImpl(registry)
            val request = request("diff").copy(contextBlocks = listOf("title SECRET"))

            service.complete(request, "p-true")
            service.complete(request, "p-false")

            val (native, legacy) = received
            assertEquals(listOf("title [REDACTED]"), native.contextBlocks)
            assertEquals("diff", native.prompt)
            assertEquals(emptyList(), legacy.contextBlocks)
            assertEquals("title [REDACTED]\n\ndiff", legacy.prompt)
        }

    @Test
    fun responseTransformersAppliedInPriorityOrder() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubProvider())

            registry.register(object : AiResponseTransformerModule {
                override val id = "tx-first"
                override val name = "First"
                override val version = "1.0"
                override val transformerPriority = 10

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun transform(
                    response: AiCompletionResponse,
                    request: AiCompletionRequest,
                    context: AiResponseTransformerContext,
                ) = response.copy(content = response.content + "+T1")
            })
            registry.register(object : AiResponseTransformerModule {
                override val id = "tx-second"
                override val name = "Second"
                override val version = "1.0"
                override val transformerPriority = 200

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun transform(
                    response: AiCompletionResponse,
                    request: AiCompletionRequest,
                    context: AiResponseTransformerContext,
                ) = response.copy(content = response.content + "+T2")
            })

            val service = AiServiceImpl(registry)
            val result = service.complete(request())

            assertEquals("response+T1+T2", result.content, "T1 (priority 10) before T2 (priority 200)")
        }

    @Test
    fun fullPipelineProcessorsRedactorsInterceptorsTransformers() =
        runTest {
            val registry = ModuleRegistryImpl()
            val order = mutableListOf<String>()

            registry.register(object : AiProviderModule {
                override val id = "provider-log"
                override val name = "Log"
                override val version = "1.0"
                override val providerId = "test"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
                    order.add("provider")
                    return stubResponse.copy(content = request.prompt)
                }
            })

            registry.register(object : AiPromptProcessorModule {
                override val id = "proc-log"
                override val name = "LogProc"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun processPrompt(
                    prompt: String,
                    context: AiPromptProcessorContext
                ): String {
                    order.add("processor")
                    return "$prompt|proc"
                }
            })

            registry.register(object : AiRedactorModule {
                override val id = "redact-log"
                override val name = "LogRedact"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun redact(
                    text: String,
                    context: AiRedactorContext
                ): String {
                    order.add("redactor")
                    return "$text|redact"
                }
            })

            registry.register(object : AiInterceptorModule {
                override val id = "intercept-log"
                override val name = "LogIntercept"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun intercept(
                    request: AiCompletionRequest,
                    context: AiInterceptContext,
                    chain: AiInterceptChain,
                ): AiCompletionResponse {
                    order.add("interceptor")
                    return chain.proceed(request)
                }
            })

            registry.register(object : AiResponseTransformerModule {
                override val id = "tx-log"
                override val name = "LogTx"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun transform(
                    response: AiCompletionResponse,
                    request: AiCompletionRequest,
                    context: AiResponseTransformerContext,
                ): AiCompletionResponse {
                    order.add("transformer")
                    return response
                }
            })

            val service = AiServiceImpl(registry)
            service.complete(request("input"))

            assertEquals(
                listOf("processor", "redactor", "interceptor", "provider", "transformer"),
                order,
                "Pipeline order: processor -> redactor -> interceptor -> provider -> transformer"
            )
        }

    private class CapturingInterceptor : AiInterceptorModule {
        override val id = "intercept-capture"
        override val name = "Capture"
        override val version = "1.0"
        val contexts = mutableListOf<AiInterceptContext>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun intercept(
            request: AiCompletionRequest,
            context: AiInterceptContext,
            chain: AiInterceptChain,
        ): AiCompletionResponse {
            contexts.add(context)
            return chain.proceed(request)
        }
    }

    @Test
    fun forRulePassesSourceModuleIdToInterceptContext() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubProvider())
            val interceptor = CapturingInterceptor()
            registry.register(interceptor)
            val service = AiServiceImpl(registry)

            service.forRule("security-scan", null, sourceModuleId = "ai-analysis").complete(request())
            service.complete(request())

            assertEquals("security-scan", interceptor.contexts[0].ruleId)
            assertEquals("ai-analysis", interceptor.contexts[0].sourceModuleId)
            assertEquals(null, interceptor.contexts[1].sourceModuleId)
        }

    @Test
    fun emptyExtensionListsShortCircuit() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubProvider())

            assertTrue(registry.aiPromptProcessors().isEmpty())
            assertTrue(registry.aiRedactors().isEmpty())
            assertTrue(registry.aiResponseTransformers().isEmpty())

            val service = AiServiceImpl(registry)
            val result = service.complete(request("plain"))
            assertEquals("response", result.content, "Should pass through unchanged with no extensions")
        }

    @Test
    fun debugLoggedResponseContentIsRedacted() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(object : AiProviderModule by stubProvider() {
                override suspend fun complete(request: AiCompletionRequest) =
                    stubResponse.copy(content = "echo api_key=leakedkey and https://u:leakedpass@h/x")
            })
            val logged = mutableListOf<String>()
            AiServiceImpl(registry, debugLogger = { logged += it }).complete(request())

            val output = logged.joinToString("\n")
            assertTrue("leakedkey" !in output && "leakedpass" !in output, "secret in debug output: $output")
        }

    @Test
    fun providerSelectionIsLoggedAtInfo() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubProvider())
            val logger = RecordingLogger()
            AiServiceImpl(registry, logger = logger).complete(request())

            assertEquals(listOf("INFO [ai] provider=test model=test-model rule=- (requested=default)"), logger.lines)
        }

    @Test
    fun forRuleRoutesToTheRuleProviderAndStampsRuleIdOnHookContexts() =
        runTest {
            val registry = ModuleRegistryImpl()
            registry.register(stubProvider("main"))
            registry.register(object : AiProviderModule by stubProvider("fast") {
                override suspend fun complete(request: AiCompletionRequest) = stubResponse.copy(content = "fast")
            })
            val seenRuleIds = mutableListOf<String?>()
            registry.register(object : AiRedactorModule {
                override val id = "redactor"
                override val name = "R"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun redact(
                    text: String,
                    context: AiRedactorContext,
                ): String {
                    seenRuleIds += context.ruleId
                    return text
                }
            })
            val service = AiServiceImpl(registry, defaultProviderId = "main")

            assertEquals("fast", service.forRule("security", "fast").complete(request()).content)
            assertEquals("response", service.forRule("style", null).complete(request()).content)
            assertEquals(listOf<String?>("security", "style"), seenRuleIds)
        }

    @Test
    fun isAvailableWithOnlyALocalOrRunProvider() {
        val empty = ModuleRegistryImpl()

        assertFalse(AiServiceImpl(empty).isAvailable)
        assertTrue(AiServiceImpl(empty, localProviders = mapOf("custom" to stubProvider("custom"))).isAvailable)
        assertTrue(AiServiceImpl(empty, providerSource = { stubProvider() }).isAvailable)
    }

    @Test
    fun providerInitContextHidesDenylistedEnvVars() =
        runTest {
            val seen = mutableMapOf<String, String?>()
            val registry = ModuleRegistryImpl()
            registry.register(object : AiProviderModule by stubProvider() {
                override suspend fun initialize(context: PluginContext): Boolean {
                    KarandashEnvSecurity.DENYLIST.forEach { seen[it] = context.env(it) }
                    return true
                }
            })

            AiServiceImpl(registry).complete(request())

            assertEquals(KarandashEnvSecurity.DENYLIST, seen.keys)
            assertTrue(seen.values.all { it == null }, "denylisted env leaked: ${seen.filterValues { it != null }.keys}")
        }

    private class RecordingLogger : Logger {
        val lines = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            lines += "DEBUG $message"
        }

        override fun info(message: String) {
            lines += "INFO $message"
        }

        override fun warn(message: String) {
            lines += "WARN $message"
        }

        override fun error(message: String) {
            lines += "ERROR $message"
        }
    }

    private class CountingProvider(
        override val providerId: String = "counting",
        private val initResult: Boolean = true,
    ) : AiProviderModule {
        override val id = "provider-$providerId"
        override val name = "Counting"
        override val version = "1.0"
        val initCount = mutableListOf<Unit>()
        val shutdownCount = mutableListOf<Unit>()
        val prompts = mutableListOf<String?>()

        override suspend fun initialize(context: PluginContext): Boolean {
            initCount += Unit
            return initResult
        }

        override suspend fun shutdown() {
            shutdownCount += Unit
        }

        override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
            prompts += request.prompt
            prompts += request.systemPrompt
            return AiCompletionResponse(content = "ok", promptTokens = 1, completionTokens = 1, totalTokens = 2, model = "m")
        }
    }

    @Test
    fun fallbackProviderInitializedOnceAndShutDownByService() =
        runTest {
            val provider = CountingProvider()
            val registry = ModuleRegistryImpl().apply { register(provider) }
            val service = AiServiceImpl(registry)

            service.complete(request())
            service.forRule("rule-a", providerId = null).complete(request())
            service.forRule("rule-b", providerId = "counting").complete(request())
            assertEquals(2, provider.initCount.size, "one init per requested id (default + 'counting'), reused across calls and views")

            service.forRule("rule-a", providerId = null).shutdown()
            assertEquals(1, provider.shutdownCount.size, "the shared provider is shut down once")
            service.shutdown()
            assertEquals(1, provider.shutdownCount.size, "shutdown is idempotent")
        }

    @Test
    fun fallbackShutsDownRegistryProvidersWhoseInitializeFailed() =
        runTest {
            val unavailable = CountingProvider(providerId = "unavailable", initResult = false)
            val available = CountingProvider(providerId = "available")
            val registry = ModuleRegistryImpl().apply {
                register(unavailable)
                register(available)
            }
            val service = AiServiceImpl(registry)

            service.complete(request())
            assertEquals(1, unavailable.shutdownCount.size, "a provider that failed initialize is shut down right away")
            assertEquals(0, available.shutdownCount.size)
            assertFailsWith<AiProviderException> { service.forRule("r", providerId = "unavailable").complete(request()) }
            assertEquals(2, unavailable.shutdownCount.size, "an explicitly requested provider that fails initialize too")

            service.shutdown()
            assertEquals(1, available.shutdownCount.size, "the provider the fallback path picked is shut down by the service")
            assertEquals(2, unavailable.shutdownCount.size)
        }

    @Test
    fun localProvidersAreNotShutDownByService() =
        runTest {
            val provider = CountingProvider()
            val service =
                AiServiceImpl(ModuleRegistryImpl(), defaultProviderId = "counting", localProviders = mapOf("counting" to provider))

            service.complete(request())
            service.shutdown()
            assertEquals(0, provider.initCount.size)
            assertEquals(0, provider.shutdownCount.size)
        }

    @Test
    fun builtInSecretPatternsRedactTheAssembledPrompt() =
        runTest {
            val provider = CountingProvider()
            val registry = ModuleRegistryImpl().apply { register(provider) }
            registry.register(object : AiPromptProcessorModule {
                override val id = "proc-enrich"
                override val name = "Enrich"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun processPrompt(
                    prompt: String,
                    context: AiPromptProcessorContext,
                ) = "$prompt\nticket says api_key=abc123"
            })

            AiServiceImpl(registry).complete(request("diff", "system password: hunter2"))

            val sentPrompt = checkNotNull(provider.prompts[0])
            val sentSystem = checkNotNull(provider.prompts[1])
            assertFalse("abc123" in sentPrompt, sentPrompt)
            assertTrue("[REDACTED]" in sentPrompt, sentPrompt)
            assertFalse("hunter2" in sentSystem, sentSystem)
        }

    @Test
    fun dryRunSinkGetsAssembledRequestAndProviderIsNeverResolved() =
        runTest {
            val provider = CountingProvider()
            val registry = ModuleRegistryImpl().apply { register(provider) }
            val sent = mutableListOf<AiCompletionRequest>()

            val response = AiServiceImpl(registry)
                .forRule("rule-a", providerId = null, dryRunSink = { sent += it })
                .complete(request("token: abc123 diff"))

            assertEquals("", response.content)
            assertEquals(0, response.totalTokens)
            assertEquals(0, provider.initCount.size, "dry run must not initialize a provider")
            assertTrue(provider.prompts.isEmpty(), "dry run must not call a provider")
            assertFalse("abc123" in sent.single().prompt, "the sink sees the redacted prompt")
        }

    @Test
    fun promptSinkSeesExactlyWhatTheProviderGetsAfterRedactionAndInterceptors() =
        runTest {
            val received = mutableListOf<AiCompletionRequest>()
            val registry = ModuleRegistryImpl()
            registry.register(object : AiProviderModule {
                override val id = "provider-cap"
                override val name = "Cap"
                override val version = "1.0"
                override val providerId = "test"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun complete(request: AiCompletionRequest): AiCompletionResponse {
                    received += request
                    return stubResponse
                }
            })
            registry.register(object : AiInterceptorModule {
                override val id = "suffix"
                override val name = "Suffix"
                override val version = "1.0"

                override suspend fun initialize(context: PluginContext) = true

                override suspend fun intercept(
                    request: AiCompletionRequest,
                    context: AiInterceptContext,
                    chain: AiInterceptChain,
                ) = chain.proceed(request.copy(prompt = request.prompt + "\n-- intercepted"))
            })
            val dumped = mutableListOf<AiCompletionRequest>()

            AiServiceImpl(registry)
                .forRule("rule-a", providerId = null, promptSink = { dumped += it })
                .complete(request("diff with api_key=abc123", "system ü"))

            assertEquals(received, dumped, "the dump is the request the provider got")
            assertTrue(dumped.single().prompt.endsWith("-- intercepted"), "taken after interceptors")
            assertFalse("abc123" in dumped.single().prompt, "taken after redaction")
        }

    @Test
    fun promptSinkSeesTheDryRunRequest() =
        runTest {
            val recorded = mutableListOf<AiCompletionRequest>()
            val dumped = mutableListOf<AiCompletionRequest>()

            AiServiceImpl(ModuleRegistryImpl())
                .forRule("rule-a", providerId = null, dryRunSink = { recorded += it }, promptSink = { dumped += it })
                .complete(request("password: hunter2 diff"))

            assertEquals(recorded, dumped)
            assertFalse("hunter2" in dumped.single().prompt)
        }

    @Test
    fun `a dumping run writes each request and response per rule call live and in a dry run`() =
        runTest {
            val written = MutableStateFlow(emptyList<String>())
            val registry = ModuleRegistryImpl().also { it.register(stubProvider()) }
            val service = AiServiceImpl(registry)
            val session = registry.forRun()
            session.provide(RunServiceKeys.DUMP, RunDump { name, _ -> written.update { it + name } })

            withContext(session.services) {
                service.forRule("live", providerId = null).complete(request())
                service.forRule("dry", providerId = null, dryRunSink = {}).complete(request())
            }
            service.complete(request())

            assertEquals(
                listOf(
                    "ai/live/call-1.request.json",
                    "ai/live/call-1.response.json",
                    "ai/dry/call-1.request.json",
                    "ai/dry/call-1.response.json"
                ),
                written.value,
            )
        }
}
