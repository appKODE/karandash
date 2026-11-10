package io.karandash.loader.bridge

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.context.ValidationContext
import io.karandash.api.module.FileGranularRuleModule
import io.karandash.api.module.FindingsAwareRuleModule
import io.karandash.api.module.ModuleUsage
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.MergeRequestField
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.loader.bridge.modules.BridgeProviderModule
import io.karandash.loader.bridge.modules.BridgeRuleModule
import io.karandash.loader.bridge.modules.FakeBridgeTransport
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The data-only ops: the `{ok|err}` envelope, the platform and rule proxies, and the missing-export fallbacks. */
@OptIn(ExperimentalKarandashApi::class)
class BridgeDataOpsTest {
    private val descriptor = BridgeDescriptor(JsonObject(emptyMap()), "guest")
    private val mr = createTestMergeRequest()

    private fun platform(transport: FakeBridgeTransport) = BridgePlatformServiceProxy(BridgeProviderModule(transport), transport)

    private fun rule(transport: FakeBridgeTransport) = bridgeRuleProxy(BridgeRuleModule(transport), descriptor, transport)

    @Test
    fun `the envelope round-trips success failure and unsupported`() {
        assertTrue(BridgeResultEnvelope.decode("op", BridgeResultEnvelope.encode(Result.success(Unit))).isSuccess)
        val failed = BridgeResultEnvelope.decode("op", BridgeResultEnvelope.encode(Result.failure(IllegalStateException("boom"))))
        assertEquals("boom", failed.exceptionOrNull()?.message)
        val unsupported = BridgeResultEnvelope.encode(Result.failure(UnsupportedPlatformOperationException("mergeMr")))
        assertIs<UnsupportedPlatformOperationException>(BridgeResultEnvelope.decode("mergeMr", unsupported).exceptionOrNull())
        listOf(null, "null", "{", "{}").forEach { raw ->
            assertTrue(BridgeResultEnvelope.decode("op", raw).isFailure, "$raw")
        }
    }

    @Test
    fun `platform result ops send their arguments and read the envelope`() =
        runTest {
            val transport = FakeBridgeTransport(mapOf("mergeMr" to """{"ok":true}""", "closeMr" to """{"err":"locked"}"""))
            val proxy = platform(transport)
            assertTrue(proxy.mergeMr("p", "7", MergeOptions(squash = true)).isSuccess)
            assertEquals("locked", proxy.closeMr("p", "7").exceptionOrNull()?.message)
            proxy.resolveThread("p", "7", "t", null)
            assertEquals(listOf("p", "7", "t", ""), transport.calls.last().second)
            assertTrue(
                transport.calls
                    .first()
                    .second[2]
                    .contains("\"squash\":true")
            )
        }

    @Test
    fun `a missing result export is unsupported and warned once per op`() =
        runTest {
            val transport = FakeBridgeTransport.bare()
            val proxy = platform(transport)
            repeat(2) { assertIs<UnsupportedPlatformOperationException>(proxy.addReviewers("p", "7", listOf("a")).exceptionOrNull()) }
            assertIs<UnsupportedPlatformOperationException>(proxy.reopenMr("p", "7").exceptionOrNull())
            assertEquals(listOf("addReviewers", "reopenMr"), transport.errors)
        }

    @Test
    fun `platform queries decode answers and missing exports keep the SDK default`() =
        runTest {
            val answers =
                mapOf(
                    "checkAuth" to "true",
                    "currentUsername" to "bot",
                    "isProjectMember" to "false",
                    "supports" to "true",
                    "permissionScope" to "api",
                    "unavailableMergeRequestFields" to """["CI_STATUS","FUTURE_FIELD"]""",
                    "resolveMarkedInlineCommentsForFiles" to "3",
                    "fetchFileContent" to "null",
                )
            val proxy = platform(FakeBridgeTransport(answers))
            assertEquals(true, proxy.checkAuth())
            assertEquals("bot", proxy.currentUsername())
            assertEquals(false, proxy.isProjectMember("p", "u"))
            assertTrue(proxy.supports(PlatformOperation.MERGE))
            assertEquals("api", proxy.permissionScope())
            assertEquals(setOf(MergeRequestField.CI_STATUS), proxy.unavailableMergeRequestFields())
            assertEquals(3, proxy.resolveMarkedInlineCommentsForFiles("p", "7", "m", setOf("a")))
            assertNull(proxy.fetchFileContent("p", "r", "a"))

            val bare = platform(FakeBridgeTransport.bare())
            assertNull(bare.checkAuth())
            assertFalse(bare.supports(PlatformOperation.MERGE))
            assertEquals(emptyList(), bare.fetchFileBlame("p", "a", "r"))
            assertNull(bare.tokenLocationHint())
            assertEquals(0, bare.resolveMarkedInlineCommentsForFiles("p", "7", "m", setOf("a")))
        }

    @Test
    fun `queries whose SDK default throws stay unsupported across the bridge`() =
        runTest {
            val bare = platform(FakeBridgeTransport.bare())
            assertFailsWith<UnsupportedPlatformOperationException> { bare.fetchFileContent("p", "r", "a") }
            assertFailsWith<UnsupportedPlatformOperationException> { bare.fetchApprovalSignals("p", "7") }
            assertFailsWith<UnsupportedPlatformOperationException> { bare.searchMergeRequests("p", "x", null, 5) }
            assertFailsWith<UnsupportedPlatformOperationException> { bare.listReviewThreads("p", "7") }

            val marker = BridgeResultEnvelope.encode(Result.failure(UnsupportedPlatformOperationException("guest")))
            val declined = platform(FakeBridgeTransport(mapOf("fetchFileContent" to marker, "listReviewThreads" to marker)))
            val error = assertFailsWith<UnsupportedPlatformOperationException> { declined.fetchFileContent("p", "r", "a") }
            assertEquals("fetchFileContent", error.operation)
            assertFailsWith<UnsupportedPlatformOperationException> { declined.listReviewThreads("p", "7") }

            val answers =
                mapOf(
                    "fetchFileContent" to """{"err":"not a marker"}""",
                    "fetchApprovalSignals" to "[]",
                    "searchMergeRequests" to "null",
                    "listReviewThreads" to "[]",
                )
            val answering = platform(FakeBridgeTransport(answers))
            assertEquals("""{"err":"not a marker"}""", answering.fetchFileContent("p", "r", "a"))
            assertEquals(emptyList(), answering.fetchApprovalSignals("p", "7"))
            assertNull(answering.searchMergeRequests("p", "x", null, 5))
            assertEquals(emptyList(), answering.listReviewThreads("p", "7"))
            assertNull(platform(FakeBridgeTransport(fail = true)).listReviewThreads("p", "7"))
        }

    @Test
    fun `the rule proxy serves the optional interfaces only for exported ops`() {
        assertIs<FileGranularRuleModule>(rule(FakeBridgeTransport(capabilities = setOf("validateFile"))))
        assertFalse(rule(FakeBridgeTransport(capabilities = setOf("validateFile"))) is FindingsAwareRuleModule)
        assertIs<FindingsAwareRuleModule>(rule(FakeBridgeTransport(capabilities = setOf("validateWithFindings"))))
        val bare = rule(FakeBridgeTransport.bare())
        assertFalse(bare is FileGranularRuleModule || bare is FindingsAwareRuleModule)
    }

    @Test
    fun `rule data ops send JSON and decode the accounting answers`() =
        runTest {
            val answers =
                mapOf(
                    "validateFile" to "[]",
                    "validateWithFindings" to "[]",
                    "usage" to """{"tokens":5,"requests":1}""",
                    "requiredMergeRequestFields" to """["CI_STATUS"]""",
                    "tokensConsumed" to "5",
                    "logCostSummary" to "spent 5 tokens",
                )
            val transport = FakeBridgeTransport(answers)
            val proxy = rule(transport)
            val file = FileChange(path = "a", changeType = ChangeType.MODIFIED)
            assertEquals(emptyList(), (proxy as FileGranularRuleModule).validateFile("r", file, mr, JsonNull))
            assertEquals(emptyList(), (proxy as FindingsAwareRuleModule).validateWithFindings("r", mr, JsonNull, ValidationContext()))
            assertEquals(ModuleUsage(tokens = 5, requests = 1), proxy.usage)
            assertEquals(setOf(MergeRequestField.CI_STATUS), proxy.requiredMergeRequestFields("r"))
            assertEquals(5, proxy.tokensConsumed())
            proxy.logCostSummary()
            assertEquals(listOf("r", "null"), transport.calls.single { it.first == "requiredMergeRequestFields" }.second)
            assertEquals("""{"priorFindings":[]}""", transport.calls.single { it.first == "validateWithFindings" }.second[3])

            val bare = rule(FakeBridgeTransport.bare())
            assertNull(bare.usage)
            assertNull(bare.tokensConsumed())
            assertEquals(emptySet(), bare.requiredMergeRequestFields("r"))
        }
}
