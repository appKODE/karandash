package io.karandash.loader.bridge.ai

import io.karandash.api.ai.AiContextBlock
import io.karandash.loader.bridge.BridgeTransport
import io.karandash.models.FileChange
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for [BridgeAiContextEnricherModule.provideBlock] wiring through [BridgeTransport].
 *
 * Covers:
 * - Happy path: transport returns valid AiContextBlock JSON
 * - Capability absent: transport.hasCapability returns false -> null
 * - Transport returns null -> null
 * - Transport returns blank -> null
 * - Transport throws -> null (boundary catch)
 */
class BridgeProvideBlockTest {
    private val json = KarandashJson.Default

    private val testBlock = AiContextBlock(
        heading = "#### Test heading",
        content = "Test content body",
        priority = 200,
        source = "test-enricher",
    )

    @Test
    fun provideBlock_returns_parsed_block_when_transport_succeeds() =
        runTest {
            val blockJson = json.encodeToString(AiContextBlock.serializer(), testBlock)
            val transport = StubTransport(
                capabilities = setOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK),
                callResults = mapOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK to blockJson),
            )
            val module = BridgeAiContextEnricherModule(transport)

            val result = module.provideBlock(createTestMergeRequest(), emptyList(), JsonNull)

            assertEquals(testBlock, result)
            assertEquals(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK, transport.lastCalledMethod)
        }

    @Test
    fun provideBlock_returns_null_when_capability_absent() =
        runTest {
            val transport = StubTransport(
                capabilities = emptySet(),
                callResults = emptyMap(),
            )
            val module = BridgeAiContextEnricherModule(transport)

            val result = module.provideBlock(createTestMergeRequest(), emptyList(), JsonNull)

            assertNull(result)
            assertNull(transport.lastCalledMethod)
        }

    @Test
    fun provideBlock_returns_null_when_transport_returns_null() =
        runTest {
            val transport = StubTransport(
                capabilities = setOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK),
                callResults = mapOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK to null),
            )
            val module = BridgeAiContextEnricherModule(transport)

            val result = module.provideBlock(createTestMergeRequest(), emptyList(), JsonNull)

            assertNull(result)
        }

    @Test
    fun provideBlock_returns_null_when_transport_returns_blank() =
        runTest {
            val transport = StubTransport(
                capabilities = setOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK),
                callResults = mapOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK to "   "),
            )
            val module = BridgeAiContextEnricherModule(transport)

            val result = module.provideBlock(createTestMergeRequest(), emptyList(), JsonNull)

            assertNull(result)
        }

    @Test
    fun provideBlock_returns_null_when_transport_throws() =
        runTest {
            val transport = ThrowingTransport(
                capabilities = setOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK),
            )
            val module = BridgeAiContextEnricherModule(transport)

            val result = module.provideBlock(createTestMergeRequest(), emptyList(), JsonNull)

            assertNull(result)
        }

    @Test
    fun provideBlock_passes_serialized_changes_to_transport() =
        runTest {
            val changes = listOf(
                FileChange(
                    path = "a.kt",
                    changeType = io.karandash.models.ChangeType.MODIFIED,
                    additions = 1,
                    deletions = 1,
                    diffContent = "@@ -1 +1 @@\n-old\n+new",
                ),
            )
            val blockJson = json.encodeToString(AiContextBlock.serializer(), testBlock)
            val transport = StubTransport(
                capabilities = setOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK),
                callResults = mapOf(BridgeAiContextEnricherModule.METHOD_PROVIDE_BLOCK to blockJson),
            )
            val module = BridgeAiContextEnricherModule(transport)

            val result = module.provideBlock(createTestMergeRequest(), changes, JsonNull)

            assertEquals(testBlock, result)
            // Verify the transport received 4 args (mr, changes, config, budget)
            assertEquals(4, transport.lastCallArgs?.size)
        }

    /**
     * Stub [BridgeTransport] that records calls and returns pre-programmed results.
     */
    private class StubTransport(
        private val capabilities: Set<String>,
        private val callResults: Map<String, String?>,
    ) : BridgeTransport {
        var lastCalledMethod: String? = null
        var lastCallArgs: Array<out String>? = null

        override suspend fun call(
            method: String,
            vararg args: String
        ): String? {
            lastCalledMethod = method
            lastCallArgs = args
            return callResults[method]
        }

        override suspend fun callVoid(
            method: String,
            vararg args: String
        ) {}

        override fun getProperty(name: String): String = ""

        override fun getIntProperty(
            name: String,
            default: Int
        ): Int = default

        override fun getBoolProperty(
            name: String,
            default: Boolean
        ): Boolean = default

        override fun hasCapability(name: String): Boolean = name in capabilities

        override fun logError(
            method: String,
            exception: Exception
        ) {}
    }

    /**
     * [BridgeTransport] that throws on [call] to test boundary catch.
     */
    private class ThrowingTransport(
        private val capabilities: Set<String>,
    ) : BridgeTransport {
        override suspend fun call(
            method: String,
            vararg args: String
        ): String? {
            error("Simulated transport failure")
        }

        override suspend fun callVoid(
            method: String,
            vararg args: String
        ) {}

        override fun getProperty(name: String): String = ""

        override fun getIntProperty(
            name: String,
            default: Int
        ): Int = default

        override fun getBoolProperty(
            name: String,
            default: Boolean
        ): Boolean = default

        override fun hasCapability(name: String): Boolean = name in capabilities

        override fun logError(
            method: String,
            exception: Exception
        ) {}
    }
}
