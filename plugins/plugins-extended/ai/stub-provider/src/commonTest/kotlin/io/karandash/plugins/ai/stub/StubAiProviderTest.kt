package io.karandash.plugins.ai.stub

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiProviderException
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StubAiProviderTest {
    private val dir = TestFiles.createTestTempDirectory("stub-provider")

    @AfterTest
    fun cleanup() = TestFiles.deleteRecursively(dir)

    private fun replyFile(text: String): String {
        val path = Path(dir, "replies.txt")
        TestFiles.writeText(path, text)
        return path.toString()
    }

    private fun context(file: String?) =
        TestPluginContext(
            pluginConfig = buildJsonObject {
                if (file != null) put("stub", buildJsonObject { put("replyFile", file) })
            },
        )

    private suspend fun initialized(text: String): StubAiProvider =
        StubAiProvider().also { assertTrue(it.initialize(context(replyFile(text)))) }

    private fun request(
        model: String = "stub",
        systemPrompt: String? = null,
    ) = AiCompletionRequest(prompt = "diff", systemPrompt = systemPrompt, model = model)

    @Test
    fun `plain text file is the reply to every call with usage 0`() =
        runTest {
            val response = initialized("LGTM").complete(request())
            assertEquals("LGTM", response.content)
            assertEquals(0, response.promptTokens)
            assertEquals(0, response.completionTokens)
            assertEquals(0, response.totalTokens)
            assertEquals("stub", response.model)
        }

    @Test
    fun `json map picks the reply by model then system prompt then default`() =
        runTest {
            val provider = initialized(
                """{"m1": "by model", "review-comments": {"comments": []}, "default": "fallback"}""",
            )
            assertEquals("by model", provider.complete(request(model = "m1")).content)
            assertEquals(
                """{"comments":[]}""",
                provider.complete(request(systemPrompt = "You write review-comments for an MR")).content,
            )
            assertEquals("fallback", provider.complete(request(systemPrompt = "other")).content)
        }

    @Test
    fun `json map without a match or default fails the call`() =
        runTest {
            val provider = initialized("""{"m1": "x"}""")
            assertFailsWith<AiProviderException> { provider.complete(request(model = "m2")) }
        }

    @Test
    fun `initialize returns false without replyFile`() =
        runTest {
            assertFalse(StubAiProvider().initialize(context(null)))
        }

    @Test
    fun `initialize returns false and warns on a missing file`() =
        runTest {
            val ctx = context(Path(dir, "absent.txt").toString())
            assertFalse(StubAiProvider().initialize(ctx))
            assertTrue(ctx.warnings.single().contains("cannot read replyFile"))
        }

    @Test
    fun `initialize returns false and warns on a malformed json map`() =
        runTest {
            val ctx = context(replyFile("{not json"))
            assertFalse(StubAiProvider().initialize(ctx))
            assertTrue(ctx.warnings.single().contains("not a JSON object"))
        }

    @Test
    fun `relative replyFile resolves against the working directory`() =
        runTest {
            val ctx = TestPluginContext(
                pluginConfig = buildJsonObject {
                    put("stub", buildJsonObject { put("replyFile", JsonPrimitive("karandash-stub-absent.txt")) })
                },
            )
            assertFalse(StubAiProvider().initialize(ctx))
            assertTrue(ctx.warnings.single().contains("karandash-stub-absent.txt"))
        }

    @Test
    fun `newHandle is a fresh uninitialized provider`() =
        runTest {
            val provider = initialized("x")
            val handle = provider.newHandle(context(null)) as StubAiProvider
            assertTrue(handle !== provider)
            assertFailsWith<IllegalStateException> { handle.complete(request()) }
        }
}
