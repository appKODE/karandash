package io.karandash.loader.bridge

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiContextBlock
import io.karandash.api.ai.AiService
import io.karandash.api.ai.AiTokenBudget
import io.karandash.loader.RecordingLogger
import io.karandash.loader.RecordingPluginContext
import io.karandash.loader.bridge.ai.BridgeAiAnalyzerModule
import io.karandash.loader.bridge.modules.BridgePlatformServiceModule
import io.karandash.loader.bridge.modules.BridgeProviderModule
import io.karandash.loader.bridge.modules.BridgeScriptModule
import io.karandash.models.DiffRefs
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The bridge modules must call every op with exactly the arguments the [BridgeAbi] row declares;
 * [AbiTableTransport] refuses (warns, never invokes) a call with the wrong arity.
 */
class BridgeCallShapeTest {
    private class RecordingTransport(
        logger: RecordingLogger,
        private val results: Map<String, String?> = emptyMap(),
    ) : AbiTableTransport("shape", logger, "Test") {
        val calls = mutableListOf<Pair<String, List<String>>>()

        override fun exports(op: BridgeOp): Boolean = true

        override fun invoke(
            op: BridgeOp,
            args: Array<out String>,
        ): String? {
            calls += op.op to args.toList()
            return results[op.op] ?: when (op.returns) {
                CType.STR -> null
                CType.INT, CType.BOOL -> "1"
                CType.VOID -> null
            }
        }

        fun argsOf(op: String): List<String> = calls.single { it.first == op }.second
    }

    private val logger = RecordingLogger()

    @Test
    fun `postInlineComment passes seven positional arguments with int line numbers`() =
        runTest {
            val transport = RecordingTransport(logger)
            BridgePlatformServiceModule(transport).postInlineComment(
                "p",
                "7",
                "body",
                "src/A.kt",
                12,
                DiffRefs(baseSha = "b", headSha = "h"),
                10,
            )
            val args = transport.argsOf("postInlineComment")
            assertEquals(listOf("p", "7", "body", "src/A.kt", "12"), args.take(5))
            assertEquals(
                "h",
                Json
                    .parseToJsonElement(args[5])
                    .jsonObject["head_sha"]
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals("10", args[6])
            assertTrue(logger.warnings.isEmpty(), logger.warnings.toString())
        }

    @Test
    fun `postInlineComment sends start line zero for a single-line comment`() =
        runTest {
            val transport = RecordingTransport(logger)
            BridgePlatformServiceModule(transport).postInlineComment("p", "7", "b", "f", 3, DiffRefs("b", "h"), null)
            assertEquals("0", transport.argsOf("postInlineComment")[6])
        }

    @Test
    fun `script execute passes the plugin context as third argument`() =
        runTest {
            val transport = RecordingTransport(logger, mapOf("execute" to "[]"))
            BridgeScriptModule(transport).execute(emptyList(), createTestMergeRequest(), RecordingPluginContext())
            val args = transport.argsOf("execute")
            assertEquals(3, args.size)
            assertTrue(Json.parseToJsonElement(args[2]) is JsonObject)
            assertTrue(logger.warnings.isEmpty(), logger.warnings.toString())
        }

    @Test
    fun `detectCiEnvironment passes only the plugin supported env vars that are set`() =
        runTest {
            val transport = RecordingTransport(logger, mapOf("supportedEnvVars" to """["CI_A","CI_B"]"""))
            BridgeProviderModule(transport).detectCiEnvironment { name -> if (name == "CI_A") "1" else null }
            val env = Json.parseToJsonElement(transport.argsOf("detectCiEnvironment").single()).jsonObject
            assertEquals(mapOf("CI_A" to JsonPrimitive("1")), env.toMap())
        }

    @Test
    fun `aiAnalyze passes mr budget model and config with the prompt pipeline output`() =
        runTest {
            val transport = RecordingTransport(logger, mapOf("aiAnalyze" to "[]"))
            BridgeAiAnalyzerModule(transport).analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = UnavailableAiService,
                    budget = FixedBudget,
                    model = "m-1",
                    config = JsonObject(mapOf("threshold" to JsonPrimitive(3))),
                    resolvedPrompt = "prompt",
                ),
            )
            val args = transport.argsOf("aiAnalyze")
            assertEquals(4, args.size)
            assertEquals(
                "42",
                Json
                    .parseToJsonElement(args[1])
                    .jsonObject["remaining"]
                    ?.jsonPrimitive
                    ?.content
            )
            assertEquals("m-1", args[2])
            val config = Json.parseToJsonElement(args[3]).jsonObject
            assertEquals("3", config["threshold"]?.jsonPrimitive?.content)
            assertEquals("prompt", config["resolvedPrompt"]?.jsonPrimitive?.content)
            assertEquals("false", config["aiServiceAvailable"]?.jsonPrimitive?.content)
        }

    @Test
    fun `aiAnalyze sends the shared context blocks once inside the extra instructions`() =
        runTest {
            val transport = RecordingTransport(logger, mapOf("aiAnalyze" to "[]"))
            BridgeAiAnalyzerModule(transport).analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(),
                    aiService = UnavailableAiService,
                    budget = FixedBudget,
                    model = "m-1",
                    config = JsonObject(emptyMap()),
                    extraInstructions = "EXTRA",
                    contextBlocks = listOf(AiContextBlock("Tickets", "SHARED-BLOCK", 1, "ticket")),
                ),
            )
            val args = transport.argsOf("aiAnalyze")
            val config = Json.parseToJsonElement(args[3]).jsonObject
            assertEquals("SHARED-BLOCK\n\nEXTRA", config["extraInstructions"]?.jsonPrimitive?.content)
            assertEquals(1, args.sumOf { it.split("SHARED-BLOCK").size - 1 }, "the block reaches the guest once")
        }

    @Test
    fun `a call with the wrong arity is refused with a warning and never reaches the guest`() =
        runTest {
            val transport = RecordingTransport(logger)
            transport.call("postComment", "only-one")
            assertTrue(transport.calls.isEmpty())
            assertTrue(logger.warnings.single().contains("ABI expects 3"))
        }

    @Test
    fun `BOOL results decode to true only for status one`() =
        runTest {
            val ok = RecordingTransport(logger)
            val failed = RecordingTransport(logger, mapOf("approve" to "0"))
            assertEquals("true", ok.call("approve", "p", "1"))
            assertEquals("false", failed.call("approve", "p", "1"))
            failed.callVoid("approve", "p", "1")
            assertTrue(logger.warnings.single().contains("approve reported failure"))
        }

    @Test
    fun `listOpenMergeRequestIds passes the limit as an int and decodes the ids`() =
        runTest {
            val transport = RecordingTransport(logger, mapOf("listOpenMergeRequestIds" to """["3","5"]"""))
            val ids = BridgePlatformServiceModule(transport).listOpenMergeRequestIds("p", "main", 20)

            assertEquals(listOf("3", "5"), ids)
            assertEquals(listOf("p", "main", "20"), transport.argsOf("listOpenMergeRequestIds"))
        }

    @Test
    fun `listDismissedInlineComments decodes the bodies and stays null without a guest signal`() =
        runTest {
            val answering = RecordingTransport(logger, mapOf("listDismissedInlineComments" to """["a <!-- m -->"]"""))
            val silent = RecordingTransport(logger)

            assertEquals(listOf("a <!-- m -->"), BridgePlatformServiceModule(answering).listDismissedInlineComments("p", "7", "m"))
            assertEquals(listOf("p", "7", "m"), answering.argsOf("listDismissedInlineComments"))
            assertNull(BridgePlatformServiceModule(silent).listDismissedInlineComments("p", "7", "m"))
            assertNull(BridgePlatformServiceModule(silent).listOpenMergeRequestIds("p", "main", 5))
        }

    private object FixedBudget : AiTokenBudget {
        override fun canSpend(estimatedTokens: Int) = true

        override fun recordSpend(tokens: Int) = Unit

        override fun remaining() = 42

        override fun isExhausted() = false
    }

    private object UnavailableAiService : AiService {
        override val isAvailable: Boolean = false

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse = error("unused")
    }
}
