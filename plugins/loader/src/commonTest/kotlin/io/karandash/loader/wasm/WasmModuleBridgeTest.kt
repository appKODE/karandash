package io.karandash.loader.wasm

import io.karandash.api.ModuleType
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.module.CliCommandModule
import io.karandash.api.module.ConfigFormatModule
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.loader.bridge.BridgeConfigFormatPart
import io.karandash.loader.bridge.BridgeDescriptor
import io.karandash.loader.bridge.asRegisteredModules
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.wasm.WasmEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class WasmModuleBridgeTest {
    /**
     * A minimal WasmEngine that returns 0 (empty) for all function calls.
     * Used to test that WasmModuleBridge handles empty/missing data gracefully.
     */
    private class MinimalWasmEngine : WasmEngine {
        var throwOnValidate = false

        @Suppress("TooGenericExceptionThrown")
        override fun callI32Array(
            funcName: String,
            args: IntArray
        ): Int {
            if (throwOnValidate && funcName == WasmAbi.VALIDATE) {
                throw RuntimeException("WASM error")
            }
            return 0
        }

        override fun readMemory(
            offset: Int,
            length: Int
        ) = ByteArray(length)

        override fun writeMemory(
            offset: Int,
            data: ByteArray
        ) {}

        override fun close() {}
    }

    /**
     * A WasmEngine that returns pre-configured length-prefixed string responses
     * for specific function names. Used to test WasmModuleBridge with valid module metadata.
     */
    private class ConfigurableWasmEngine(
        private val stringResponses: Map<String, String> = emptyMap(),
        private val intResponses: Map<String, Int> = emptyMap(),
        /** Real engines (Chicory, Chasm) throw on a missing export; `false` answers `0`. */
        private val missingExportsThrow: Boolean = false,
    ) : WasmEngine {
        val memory = ByteArray(8192)
        private var nextAllocPtr = 100

        override fun callI32Array(
            funcName: String,
            args: IntArray
        ): Int {
            if (funcName == WasmAbi.ALLOC) {
                val ptr = nextAllocPtr
                nextAllocPtr += args[0] + 16
                return ptr
            }
            if (funcName == WasmAbi.DEALLOC) return 0

            intResponses[funcName]?.let { return it }

            stringResponses[funcName]?.let { response ->
                val bytes = response.encodeToByteArray()
                val ptr = nextAllocPtr
                nextAllocPtr += 4 + bytes.size + 16
                val lenBytes = byteArrayOf(
                    (bytes.size and 0xFF).toByte(),
                    ((bytes.size shr 8) and 0xFF).toByte(),
                    ((bytes.size shr 16) and 0xFF).toByte(),
                    ((bytes.size shr 24) and 0xFF).toByte(),
                )
                lenBytes.copyInto(memory, ptr)
                bytes.copyInto(memory, ptr + 4)
                return ptr
            }

            check(!missingExportsThrow) { "missing export: $funcName" }
            return 0
        }

        override fun readMemory(
            offset: Int,
            length: Int
        ): ByteArray = memory.copyOfRange(offset, offset + length)

        override fun writeMemory(
            offset: Int,
            data: ByteArray
        ) {
            data.copyInto(memory, offset)
        }

        override fun close() {}
    }

    @Test
    fun bridge_returns_empty_findings_on_callI32_exception() =
        runTest {
            val engine = MinimalWasmEngine()
            engine.throwOnValidate = true
            // Module type returns 0 (empty string) which will throw IllegalArgumentException
            // So we use the configurable engine for a valid bridge
            val validEngine = ConfigurableWasmEngine(
                stringResponses = mapOf(
                    WasmAbi.MODULE_ID to "test-module",
                    WasmAbi.MODULE_NAME to "Test Module",
                    WasmAbi.MODULE_VERSION to "1.0.0",
                    WasmAbi.MODULE_TYPE to "RULE",
                ),
            )
            // Create a wrapper that throws on validate
            val throwingEngine = object : WasmEngine by validEngine {
                @Suppress("TooGenericExceptionThrown")
                override fun callI32Array(
                    funcName: String,
                    args: IntArray
                ): Int {
                    if (funcName == WasmAbi.VALIDATE) throw RuntimeException("WASM error")
                    return validEngine.callI32Array(funcName, args)
                }
            }
            val bridge = WasmModuleBridge(throwingEngine)
            val findings = bridge.validate(
                "rule-id",
                createTestMergeRequest(),
                JsonObject(emptyMap()),
            )
            assertEquals(emptyList(), findings)
        }

    @Test
    fun bridge_reads_module_metadata_from_engine() {
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_ID to "my-wasm-plugin",
                WasmAbi.MODULE_NAME to "My WASM Plugin",
                WasmAbi.MODULE_VERSION to "2.0.0",
                WasmAbi.MODULE_TYPE to "RULE",
            ),
        )
        val bridge = WasmModuleBridge(engine)
        assertEquals("my-wasm-plugin", bridge.id)
        assertEquals("My WASM Plugin", bridge.name)
        assertEquals("2.0.0", bridge.version)
    }

    @Test
    fun bridge_returns_empty_string_for_missing_metadata() {
        // Engine that returns 0 (null ptr) for all calls -- module type will throw
        // but id/name/version will be empty
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_TYPE to "REPORTER",
            ),
        )
        val bridge = WasmModuleBridge(engine)
        assertEquals("", bridge.id)
        assertEquals("", bridge.name)
        assertEquals("", bridge.version)
    }

    @Test
    fun bridge_throws_for_unsupported_module_type() {
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_TYPE to "UNKNOWN_TYPE",
            ),
        )
        val exception = assertFailsWith<IllegalArgumentException> {
            WasmModuleBridge(engine)
        }
        assertTrue(exception.message!!.contains("Unsupported WASM module type"))
    }

    @Test
    fun bridge_provider_operations_error_on_null_result() =
        runTest {
            val engine = ConfigurableWasmEngine(
                stringResponses = mapOf(
                    WasmAbi.MODULE_TYPE to "RULE",
                ),
            )
            val bridge = WasmModuleBridge(engine)
            assertFailsWith<IllegalStateException> {
                bridge.fetchMergeRequest("proj", "1")
            }
            assertFailsWith<IllegalStateException> {
                bridge.fetchMergeRequestByUrl("http://example.com")
            }
            assertEquals("", bridge.postComment("proj", "1", "body"))
            // Void-returning mutator operations succeed even when engine returns 0
            bridge.addLabels("proj", "1", listOf("label"))
            bridge.removeLabels("proj", "1", listOf("label"))
            bridge.setDraft("proj", "1", true)
            bridge.updateTitle("proj", "1", "New Title")
            bridge.updateDescription("proj", "1", "New Description")
            // findExistingComment returns null when engine returns 0
            val result = bridge.findExistingComment("proj", "1", "marker")
            assertNull(result)
        }

    @Test
    fun bridge_default_priority_returns_100_on_failure() {
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_TYPE to "RULE",
            ),
        )
        val bridge = WasmModuleBridge(engine)
        // DEFAULT_PRIORITY returns 0 from the engine, but that's a valid value (not an exception)
        // The default of 100 only applies on exception
        assertEquals(0, bridge.defaultPriority)
    }

    @Test
    fun bridge_contributed_rules_returns_empty_set_on_null_ptr() {
        runTest {
            val engine = ConfigurableWasmEngine(
                stringResponses = mapOf(
                    WasmAbi.MODULE_TYPE to "RULE",
                ),
            )
            val bridge = WasmModuleBridge(engine)
            assertEquals(emptySet(), bridge.contributedRules())
        }
    }

    @Test
    fun bridge_format_id_returns_empty_on_null_ptr() {
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_TYPE to "REPORTER",
            ),
        )
        val bridge = WasmModuleBridge(engine)
        assertEquals("", bridge.formatId)
    }

    @Test
    fun bridge_parses_reporter_type() {
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_TYPE to "REPORTER",
            ),
        )
        val bridge = WasmModuleBridge(engine)
        assertEquals(io.karandash.api.ModuleType.REPORTER, bridge.type)
    }

    @Test
    fun bridge_validate_decodes_suggestion_field() =
        runTest {
            val findingsJson = """[{"severity":"WARNING","message":"Too short","rule":"title-format","suggestion":"Add a scope prefix"}]"""
            val engine = ConfigurableWasmEngine(
                stringResponses = mapOf(
                    WasmAbi.MODULE_ID to "test-module",
                    WasmAbi.MODULE_NAME to "Test Module",
                    WasmAbi.MODULE_VERSION to "1.0.0",
                    WasmAbi.MODULE_TYPE to "RULE",
                    WasmAbi.VALIDATE to findingsJson,
                ),
            )
            val bridge = WasmModuleBridge(engine, exportNames = setOf(WasmAbi.VALIDATE))
            val findings = bridge.validate("title-format", createTestMergeRequest(), JsonObject(emptyMap()))
            assertEquals(1, findings.size)
            assertEquals("Add a scope prefix", findings[0].suggestion)
        }

    // --- AI Analyzer Continuation Pattern Tests ---

    @Test
    fun analyze_returns_findings_directly_when_result_is_array() =
        runTest {
            val findingsJson = """[{"severity":"WARNING","message":"Issue found","rule":"ai-test"}]"""
            val engine = ConfigurableWasmEngine(
                stringResponses = mapOf(
                    WasmAbi.MODULE_ID to "ai-analyzer",
                    WasmAbi.MODULE_NAME to "AI Analyzer",
                    WasmAbi.MODULE_VERSION to "1.0.0",
                    WasmAbi.MODULE_TYPE to "AI_ANALYZER",
                    WasmAbi.AI_ANALYZE to findingsJson,
                ),
            )
            val bridge = WasmModuleBridge(engine, exportNames = setOf(WasmAbi.AI_ANALYZE))
            val aiService = StubAiService(isAvailable = true)
            val findings = bridge.analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = aiService,
                    budget = StubAiTokenBudget(10000),
                    model = "test-model",
                    config = JsonObject(emptyMap()),
                ),
            )
            assertEquals(1, findings.size)
            assertEquals("Issue found", findings[0].message)
        }

    @Test
    fun analyze_handles_continuation_when_result_has_aiRequest() =
        runTest {
            val aiRequestJson = """{"aiRequest":{"prompt":"Analyze this code","model":"gpt-4"}}"""
            val continuationFindings = """[{"severity":"INFO","message":"AI says ok","rule":"ai-test"}]"""

            var continuationCalled = false
            val engine = object : WasmEngine {
                private val inner = ConfigurableWasmEngine(
                    stringResponses = mapOf(
                        WasmAbi.MODULE_ID to "ai-analyzer",
                        WasmAbi.MODULE_NAME to "AI Analyzer",
                        WasmAbi.MODULE_VERSION to "1.0.0",
                        WasmAbi.MODULE_TYPE to "AI_ANALYZER",
                        WasmAbi.AI_ANALYZE to aiRequestJson,
                    ),
                )

                override fun callI32Array(
                    funcName: String,
                    args: IntArray
                ): Int {
                    if (funcName == WasmAbi.AI_ANALYZE_CONTINUE) {
                        continuationCalled = true
                        val bytes = continuationFindings.encodeToByteArray()
                        val ptr = 4000
                        val lenBytes = byteArrayOf(
                            (bytes.size and 0xFF).toByte(),
                            ((bytes.size shr 8) and 0xFF).toByte(),
                            ((bytes.size shr 16) and 0xFF).toByte(),
                            ((bytes.size shr 24) and 0xFF).toByte(),
                        )
                        lenBytes.copyInto(inner.memory, ptr)
                        bytes.copyInto(inner.memory, ptr + 4)
                        return ptr
                    }
                    return inner.callI32Array(funcName, args)
                }

                override fun readMemory(
                    offset: Int,
                    length: Int
                ) = inner.readMemory(offset, length)

                override fun writeMemory(
                    offset: Int,
                    data: ByteArray
                ) = inner.writeMemory(offset, data)

                override fun close() = inner.close()
            }

            val aiService = StubAiService(isAvailable = true, response = "AI response content")
            val bridge = WasmModuleBridge(engine, exportNames = setOf(WasmAbi.AI_ANALYZE, WasmAbi.AI_ANALYZE_CONTINUE))
            // host services (the aiRequest loop among them) run once the guest is initialized
            assertTrue(bridge.initialize(TestPluginContext()))
            val findings = bridge.analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = aiService,
                    budget = StubAiTokenBudget(10000),
                    model = "test-model",
                    config = JsonObject(emptyMap()),
                ),
            )
            assertTrue(continuationCalled, "AI_ANALYZE_CONTINUE should have been called")
            assertEquals(1, findings.size)
            assertEquals("AI says ok", findings[0].message)
            assertTrue(aiService.completeCalled, "aiService.complete() should have been called")
        }

    @Test
    fun analyze_returns_empty_when_engine_returns_zero() =
        runTest {
            val engine = ConfigurableWasmEngine(
                stringResponses = mapOf(
                    WasmAbi.MODULE_ID to "ai-analyzer",
                    WasmAbi.MODULE_NAME to "AI Analyzer",
                    WasmAbi.MODULE_VERSION to "1.0.0",
                    WasmAbi.MODULE_TYPE to "AI_ANALYZER",
                ),
            )
            val bridge = WasmModuleBridge(engine)
            val findings = bridge.analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = StubAiService(isAvailable = false),
                    budget = StubAiTokenBudget(10000),
                    model = "test-model",
                    config = JsonObject(emptyMap()),
                ),
            )
            assertEquals(emptyList(), findings)
        }

    @Test
    fun isDefaultEnabled_applies_to_reporter_type() {
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_TYPE to "REPORTER",
            ),
            intResponses = mapOf(
                WasmAbi.IS_DEFAULT_ENABLED to 1,
            ),
        )
        val bridge = WasmModuleBridge(engine)
        assertTrue(bridge.defaultEnabled, "REPORTER modules should support defaultEnabled")
    }

    @Test
    fun priority_properties_read_from_default_priority_export() {
        val engine = ConfigurableWasmEngine(
            stringResponses = mapOf(
                WasmAbi.MODULE_TYPE to "RULE",
            ),
            intResponses = mapOf(
                WasmAbi.DEFAULT_PRIORITY to 42,
            ),
        )
        val bridge = WasmModuleBridge(engine, exportNames = setOf(WasmAbi.DEFAULT_PRIORITY))
        assertEquals(42, bridge.defaultPriority)
        assertEquals(42, bridge.interceptorPriority)
        assertEquals(42, bridge.processorPriority)
        assertEquals(42, bridge.transformerPriority)
        assertEquals(42, bridge.enricherPriority)
        assertEquals(42, bridge.redactorPriority)
    }

    @Test
    fun export_names_answer_capabilities_and_cli_commands_decode() =
        runTest {
            val bridge = WasmModuleBridge(
                ConfigurableWasmEngine(
                    stringResponses = mapOf(
                        WasmAbi.MODULE_ID to "guest",
                        WasmAbi.MODULE_TYPE to "CLI_COMMAND",
                        WasmAbi.CLI_COMMANDS to """[{"name":"greet","help":"says hi"}]""",
                    ),
                ),
                exportNames = setOf(WasmAbi.REPORT, WasmAbi.CLI_COMMANDS),
            )

            assertTrue(bridge.transport.hasCapability("report"))
            // a plain reporter: the host then falls back from reportEnriched to report
            assertFalse(bridge.transport.hasCapability("reportEnriched"))
            assertEquals(listOf("greet" to "says hi"), BridgeConfigFormatPart(bridge.transport).cliCommands().map { it.name to it.help })
        }

    // --- descriptor parity: reverting any descriptor read to its default fails one of these ---

    private fun describedBridge(
        type: String,
        describeJson: String?,
    ): WasmModuleBridge =
        WasmModuleBridge(
            ConfigurableWasmEngine(
                stringResponses = buildMap {
                    put(WasmAbi.MODULE_ID, "guest")
                    put(WasmAbi.MODULE_TYPE, type)
                    describeJson?.let { put(WasmAbi.DESCRIBE, it) }
                },
                missingExportsThrow = true,
            ),
        )

    @Test
    fun rule_describe_reaches_registered_module() {
        val module = describedBridge(
            "RULE",
            """{"id":"guest","types":["RULE"],"properties":{"supportedEnrichment":["jira"],""" +
                """"requiredEnrichment":["git"],"usesExtensionComputedConfig":true,"defaultEnabled":false}}""",
        ).asRegisteredModules().first()
        val rule = assertIs<RuleModule>(module)
        assertEquals(listOf("jira"), rule.supportedEnrichment)
        assertEquals(listOf("git"), rule.requiredEnrichment)
        assertTrue(rule.usesExtensionComputedConfig)
        assertFalse(rule.defaultEnabled)
    }

    @Test
    fun analyzer_describe_reaches_registered_module() {
        val module = describedBridge(
            "AI_ANALYZER",
            """{"id":"guest","types":["AI_ANALYZER"],"properties":{"needsScopeDetection":true,"enabledByDefault":true}}""",
        ).asRegisteredModules().first()
        val analyzer = assertIs<AiAnalyzerModule>(module)
        assertTrue(analyzer.needsScopeDetection)
        assertTrue(analyzer.defaultEnabled)
        assertTrue(analyzer.defaultEnabled)
        assertFalse(module is RuleModule)
    }

    @Test
    fun guest_without_describe_gets_defaults_and_no_phantom_capabilities() {
        val rule = assertIs<RuleModule>(describedBridge("RULE", null).asRegisteredModules().first())
        assertEquals(emptyList(), rule.supportedEnrichment)
        assertFalse(rule.usesExtensionComputedConfig)
        assertTrue(rule.defaultEnabled, "bridged rule must default to enabled")

        val reporter = describedBridge("REPORTER", null).asRegisteredModules().first()
        assertTrue(reporter.defaultEnabled)
        assertFalse(reporter is RuleModule)
        assertFalse(reporter is PlatformServiceModule)
    }

    @Test
    fun composite_guest_serves_every_declared_type_except_the_unserved_ones() {
        val all = ModuleType.entries.joinToString(",") { "\"${it.name}\"" }
        val parts = describedBridge("RULE", """{"id":"guest","types":[$all],"properties":{}}""").asRegisteredModules()

        val expected = listOf(ModuleType.RULE) + (ModuleType.entries - ModuleType.RULE - BridgeDescriptor.UNSERVED_TYPES.keys)
        assertEquals(expected, parts.map { it.type })
        assertTrue(parts.all { it.id == "guest" })
        assertIs<ReporterModule>(parts.single { it.type == ModuleType.REPORTER })
        assertIs<PipelineExtension>(parts.single { it.type == ModuleType.PIPELINE_EXTENSION })
        assertIs<CliCommandModule>(parts.single { it.type == ModuleType.CLI_COMMAND })
        assertIs<ConfigFormatModule>(parts.single { it.type == ModuleType.CONFIG_FORMAT })
        assertFalse(parts.single { it.type == ModuleType.REPORTER } is RuleModule)
    }

    // --- Re-initialize after shutdown (the run shuts rule modules down; watch re-runs) ---

    /** Like a real engine: unusable once closed. */
    private class ClosableWasmEngine(
        private val delegate: WasmEngine,
    ) : WasmEngine by delegate {
        var closed = false

        override fun callI32Array(
            funcName: String,
            args: IntArray
        ): Int {
            check(!closed) { "engine closed" }
            return delegate.callI32Array(funcName, args)
        }

        override fun close() {
            closed = true
        }
    }

    private fun closableEngine() =
        ClosableWasmEngine(
            ConfigurableWasmEngine(
                stringResponses = mapOf(WasmAbi.MODULE_ID to "re", WasmAbi.MODULE_TYPE to "RULE"),
                intResponses = mapOf(WasmAbi.INITIALIZE to 1),
            ),
        )

    @Test
    fun initialize_after_shutdown_recreates_the_engine() =
        runTest {
            val first = closableEngine()
            val created = mutableListOf<ClosableWasmEngine>()
            val bridge = WasmModuleBridge(first) { closableEngine().also { created += it } }
            assertTrue(bridge.initialize(TestPluginContext()))
            bridge.shutdown()
            bridge.shutdown() // idempotent: closes once, no second SHUTDOWN on a closed engine
            assertTrue(first.closed)

            assertTrue(bridge.initialize(TestPluginContext()), "re-init must build a fresh engine")
            assertEquals(1, created.size)
            assertFalse(created.single().closed)
            bridge.shutdown()
            assertTrue(created.single().closed)
        }

    @Test
    fun initialize_after_shutdown_without_factory_fails_cleanly() =
        runTest {
            val bridge = WasmModuleBridge(closableEngine())
            bridge.shutdown()
            assertFalse(bridge.initialize(TestPluginContext()))
        }

    // --- Stubs for AI tests ---

    private class StubAiTokenBudget(
        private var tokens: Int
    ) : io.karandash.api.ai.AiTokenBudget {
        override fun canSpend(estimatedTokens: Int) = tokens >= estimatedTokens

        override fun recordSpend(tokens: Int) {
            this.tokens -= tokens
        }

        override fun remaining() = tokens

        override fun isExhausted() = tokens <= 0
    }

    private class StubAiService(
        override val isAvailable: Boolean,
        private val response: String = "stub response",
    ) : io.karandash.api.ai.AiService {
        var completeCalled = false

        override suspend fun complete(
            request: io.karandash.api.ai.AiCompletionRequest,
            providerId: String?,
        ): io.karandash.api.ai.AiCompletionResponse {
            completeCalled = true
            return io.karandash.api.ai.AiCompletionResponse(
                content = response,
                promptTokens = 10,
                completionTokens = 20,
                totalTokens = 30,
                model = request.model,
            )
        }
    }
}
