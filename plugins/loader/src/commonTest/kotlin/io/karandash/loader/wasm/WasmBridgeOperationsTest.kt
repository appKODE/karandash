package io.karandash.loader.wasm

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.loader.bridge.host.HostTestContext
import io.karandash.loader.bridge.transport.WasmTransport
import io.karandash.logger.NoOpLogger
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.wasm.WasmEngine
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The direct WASM operations treat a null or empty guest answer and a throwing engine as "no answer". */
class WasmBridgeOperationsTest {
    /** Answers each export from a queue: a string is written length-prefixed, null answers pointer 0. */
    private class ScriptedEngine(
        vararg answers: Pair<String, List<String?>>,
    ) : WasmEngine {
        private val memory = ByteArray(16384)
        private var next = 64
        private val queues = answers.associate { (name, list) -> name to ArrayDeque(list) }
        val calls = mutableListOf<String>()

        override fun callI32Array(
            funcName: String,
            args: IntArray,
        ): Int {
            calls += funcName
            return when (funcName) {
                WasmAbi.ALLOC -> {
                    next.also { next += args[0] + 8 }
                }

                WasmAbi.DEALLOC -> {
                    0
                }

                else -> {
                    val queue = queues[funcName] ?: error("missing export: $funcName")
                    queue.removeFirstOrNull()?.let(::store) ?: 0
                }
            }
        }

        private fun store(text: String): Int {
            val bytes = text.encodeToByteArray()
            val ptr = next
            next += bytes.size + 8
            for (i in 0 until 4) memory[ptr + i] = (bytes.size shr (8 * i)).toByte()
            bytes.copyInto(memory, ptr + 4)
            return ptr
        }

        override fun readMemory(
            offset: Int,
            length: Int,
        ) = memory.copyOfRange(offset, offset + length)

        override fun writeMemory(
            offset: Int,
            data: ByteArray,
        ) {
            data.copyInto(memory, offset)
        }

        override fun close() = Unit
    }

    private class RecordingAi : AiService {
        override val isAvailable = true
        val requests = mutableListOf<Pair<AiCompletionRequest, String?>>()

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request to providerId
            return AiCompletionResponse(content = "ok", promptTokens = 1, completionTokens = 2, totalTokens = 3, model = request.model)
        }
    }

    private object Budget : AiTokenBudget {
        override fun canSpend(estimatedTokens: Int) = true

        override fun recordSpend(tokens: Int) = Unit

        override fun remaining() = 100

        override fun isExhausted() = false
    }

    private fun context(
        ai: AiService = RecordingAi(),
        config: JsonElement = JsonObject(mapOf("k" to JsonPrimitive(1))),
    ) = AiAnalyzerContext(createTestMergeRequest(), ai, Budget, "host-model", config)

    private val host = HostServiceDispatcher({ "m" }, NoOpLogger, { HostTestContext() })

    private val mrJson = KarandashJson.Default.encodeToString(MergeRequest.serializer(), createTestMergeRequest(title = "T"))

    @Test
    fun `provider fetches fail on a null or empty answer and a null comment id is empty`() {
        val engine = ScriptedEngine(
            WasmAbi.FETCH_MERGE_REQUEST to listOf(null, "", mrJson),
            WasmAbi.POST_COMMENT to listOf(null, "c1"),
        )
        repeat(2) {
            assertFailsWith<IllegalStateException> { WasmBridgeOperations.fetchMergeRequest(WasmTransport({ engine }, "m"), "m", "p", "1") }
        }
        assertEquals("T", WasmBridgeOperations.fetchMergeRequest(WasmTransport({ engine }, "m"), "m", "p", "1").title)
        assertEquals("", WasmBridgeOperations.postComment(WasmTransport({ engine }, "m"), "p", "1", "b"))
        assertEquals("c1", WasmBridgeOperations.postComment(WasmTransport({ engine }, "m"), "p", "1", "b"))
    }

    @Test
    fun `analyze answers no findings for a null or empty first answer`() =
        runTest {
            val engine = ScriptedEngine(WasmAbi.AI_ANALYZE to listOf(null, ""))
            assertTrue(
                WasmBridgeOperations
                    .analyze(
                        WasmTransport({
                            engine
                        }, "m"),
                        "m",
                        NoOpLogger,
                        context(config = JsonArray(emptyList())),
                        host
                    ).isEmpty()
            )
            assertTrue(WasmBridgeOperations.analyze(WasmTransport({ engine }, "m"), "m", NoOpLogger, context(), host).isEmpty())
        }

    @Test
    fun `an ai request uses its own fields or the defaults and a null continuation ends it`() =
        runTest {
            val ai = RecordingAi()
            val full = """{"aiRequest":{"prompt":"p","systemPrompt":"s","model":"g","maxTokens":5,"temperature":"0.5","providerId":"x"}}"""
            val engine = ScriptedEngine(
                WasmAbi.AI_ANALYZE to listOf(full, """{"aiRequest":{}}""", """{"aiRequest":{}}"""),
                WasmAbi.AI_ANALYZE_CONTINUE to listOf("""{"aiRequest":{}}""", "[]", null, ""),
            )
            assertTrue(WasmBridgeOperations.analyze(WasmTransport({ engine }, "m"), "m", NoOpLogger, context(ai), host).isEmpty())
            val (first, provider) = ai.requests[0]
            assertEquals(AiCompletionRequest("p", "s", "g", 5, 0.5), first)
            assertNull(provider)
            val (sparse, noProvider) = ai.requests[1]
            assertEquals(AiCompletionRequest("", null, "host-model", 2000, 0.2), sparse)
            assertNull(noProvider)
            assertTrue(WasmBridgeOperations.analyze(WasmTransport({ engine }, "m"), "m", NoOpLogger, context(ai), host).isEmpty())
            assertTrue(WasmBridgeOperations.analyze(WasmTransport({ engine }, "m"), "m", NoOpLogger, context(ai), host).isEmpty())
        }

    @Test
    fun `an object without an ai request decodes as findings`() =
        runTest {
            val engine = ScriptedEngine(WasmAbi.AI_ANALYZE to listOf("""{"other":1}"""))
            assertFailsWith<IllegalArgumentException> {
                WasmBridgeOperations.analyze(
                    WasmTransport({ engine }, "m"),
                    "m",
                    NoOpLogger,
                    context(),
                    host
                )
            }
        }

    @Test
    fun `analyze stops after the maximum continuation rounds`() =
        runTest {
            val again = """{"aiRequest":{}}"""
            val engine = ScriptedEngine(
                WasmAbi.AI_ANALYZE to listOf(again),
                WasmAbi.AI_ANALYZE_CONTINUE to List(10) { again },
            )
            assertFailsWith<IllegalArgumentException> {
                WasmBridgeOperations.analyze(
                    WasmTransport({ engine }, "m"),
                    "m",
                    NoOpLogger,
                    context(),
                    host
                )
            }
            assertEquals(10, engine.calls.count { it == WasmAbi.AI_ANALYZE_CONTINUE })
        }
}
