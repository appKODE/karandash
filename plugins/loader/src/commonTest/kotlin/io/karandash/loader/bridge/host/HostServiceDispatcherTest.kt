package io.karandash.loader.bridge.host

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiInterceptChain
import io.karandash.api.ai.AiProviderException
import io.karandash.loader.RecordingLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HostServiceDispatcherTest {
    private val logger = RecordingLogger()

    private fun dispatcher(context: HostTestContext = HostTestContext()) = HostServiceDispatcher({ "mod" }, logger) { context }

    private fun String.obj(): JsonObject = Json.parseToJsonElement(this).jsonObject

    private fun JsonObject.str(key: String): String? = this[key]?.jsonPrimitive?.content

    @Test
    fun `unknown service answers unsupported`() {
        assertEquals("unsupported", dispatcher().call("nope", "{}").obj().str("err"))
    }

    @Test
    fun `a call before initialize answers an error`() {
        val host = HostServiceDispatcher({ "mod" }, logger) { null }
        assertEquals("not initialized", host.call("log", "{}").obj().str("err"))
    }

    @Test
    fun `malformed request answers an error`() {
        assertTrue(
            dispatcher()
                .call("log", "{oops")
                .obj()
                .str("err")
                .orEmpty()
                .startsWith("bad request")
        )
    }

    @Test
    fun `log routes warn and error to warn and the rest to log`() {
        val context = HostTestContext()
        val host = dispatcher(context)
        host.call("log", """{"level":"warn","message":"w"}""")
        host.call("log", """{"level":"info","message":"i"}""")
        assertEquals(listOf("w"), context.warnings)
        assertEquals(listOf("i"), context.logs)
    }

    @Test
    fun `cache keys are prefixed with the module id`() {
        val context = HostTestContext()
        val host = dispatcher(context)
        assertEquals("{}", host.call("cache.put", """{"cache":"c","key":"k","value":"v"}"""))
        assertEquals(mapOf("mod:k" to "v"), context.cache.entries)
        assertEquals("v", host.call("cache.get", """{"cache":"c","key":"k"}""").obj().str("value"))
        assertEquals("""{"value":null}""", host.call("cache.get", """{"cache":"c","key":"missing"}"""))
    }

    @Test
    fun `missing field answers an error`() {
        assertEquals("missing 'key'", dispatcher().call("cache.get", """{"cache":"c"}""").obj().str("err"))
    }

    @Test
    fun `resolvePattern returns the pattern and its options`() {
        val reply = dispatcher().call("resolvePattern", """{"pattern":"^feat"}""").obj()
        // The test context matches literally, so the host compiles the quoted pattern.
        assertEquals("\\Q^feat\\E", reply.str("pattern"))
        assertTrue("options" in reply)
    }

    @Test
    fun `resolveContent serves inline content and refuses remote files`() {
        val host = dispatcher()
        assertEquals("text", host.call("resolveContent", """{"source":"text"}""").obj().str("content"))
        assertTrue("err" in host.call("resolveContent", """{"source":{"url":"https://example.com/x"}}""").obj())
    }

    @Test
    fun `ai complete without an ai service answers unavailable`() {
        assertEquals("ai unavailable", dispatcher().call("ai.complete", """{"prompt":"p"}""").obj().str("err"))
    }

    @Test
    fun `ai complete outside analyze uses the context ai service`() {
        val ai = FakeAi()
        val reply = dispatcher(HostTestContext(ai)).call("ai.complete", """{"prompt":"p","model":"m","maxTokens":5}""").obj()
        assertEquals("answer", reply.str("content"))
        assertEquals("10", reply["usage"]?.jsonObject?.str("totalTokens"))
        assertEquals(AiCompletionRequest(prompt = "p", model = "m", maxTokens = 5), ai.requests.single())
    }

    @Test
    fun `ai complete inside analyze uses that call's service and budget`() =
        runTest {
            val contextAi = FakeAi(reply = "context")
            val callAi = FakeAi(reply = "per-call")
            val budget = RecordingBudget()
            val host = dispatcher(HostTestContext(contextAi))
            val reply = host.withAnalyzer(analyzerContext(callAi, budget)) { host.dispatch("ai.complete", """{"prompt":"p"}""") }
            assertEquals("per-call", reply.obj().str("content"))
            assertEquals("analyzer-model", callAi.requests.single().model)
            assertEquals(listOf(10), budget.spent)
            assertTrue(contextAi.requests.isEmpty())
            // The slot is cleared on exit.
            assertEquals("context", host.dispatch("ai.complete", """{"prompt":"p"}""").obj().str("content"))
        }

    @Test
    fun `skip reports the reason on the analyze call and is refused outside it`() =
        runTest {
            val skipped = mutableListOf<String>()
            val host = dispatcher()
            val reply = host.withAnalyzer(analyzerContext(FakeAi()).copy(onSkip = { skipped += it })) {
                host.dispatch("skip", """{"reason":"no diff"}""")
            }
            assertEquals("{}", reply)
            assertEquals(listOf("no diff"), skipped)
            assertEquals("skip outside analyze", host.dispatch("skip", """{"reason":"x"}""").obj().str("err"))
        }

    @Test
    fun `ai complete refuses an exhausted budget`() =
        runTest {
            val ai = FakeAi()
            val host = dispatcher()
            val reply = host.withAnalyzer(
                analyzerContext(ai, RecordingBudget(allow = false))
            ) { host.dispatch("ai.complete", """{"prompt":"p"}""") }
            assertEquals("budget exhausted", reply.obj().str("err"))
            assertTrue(ai.requests.isEmpty())
        }

    @Test
    fun `provider failure answers error json`() {
        val ai = FakeAi { throw AiProviderException("rate limited", statusCode = 429, retryable = true) }
        val reply = dispatcher(HostTestContext(ai)).call("ai.complete", """{"prompt":"p"}""").obj()
        assertEquals("rate limited", reply.str("err"))
        assertEquals("true", reply.str("retryable"))
    }

    @Test
    fun `ai proceed runs the bound intercept chain`() =
        runTest {
            val host = dispatcher()
            val chain = AiInterceptChain { AiCompletionResponse("proceeded:${it.prompt}", 1, 1, 2, it.model) }
            val request = """{"prompt":"p","model":"m"}"""
            assertEquals("ai.proceed outside intercept", host.dispatch("ai.proceed", request).obj().str("err"))
            val reply = host.withInterceptChain(chain) { host.dispatch("ai.proceed", request) }
            assertEquals("proceeded:p", reply.obj().str("content"))
        }

    @Test
    fun `a nested host call answers reentrant and the host sees it is reentrant`() {
        lateinit var host: HostServiceDispatcher
        var nested = ""
        var reentrant = false
        val ai =
            FakeAi {
                nested = host.call("log", """{"message":"x"}""")
                reentrant = host.isReentrant()
            }
        host = dispatcher(HostTestContext(ai))
        host.call("ai.complete", """{"prompt":"p"}""")
        assertEquals("reentrant", nested.obj().str("err"))
        assertTrue(reentrant)
        assertTrue(logger.warnings.any { "reentrant" in it })
    }

    @Test
    fun `host time grows with every host call`() {
        val host = dispatcher()
        val before = host.hostTime
        host.call("log", """{"message":"x"}""")
        assertTrue(host.hostTime > before)
    }
}
