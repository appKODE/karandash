package io.karandash.sdk

import io.karandash.models.DiffRefs
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class TypedPlatformServiceDefaultsTest {
    @Serializable
    data class Cfg(
        val x: Int = 0,
    )

    private class Module : AbstractTypedPlatformServiceModule<Cfg>(Cfg.serializer()) {
        override val id = "io.karandash.test.defaults"
        override val name = "Defaults"
        override val version = "0.0.1"

        override suspend fun initializeTyped(
            context: PluginContext,
            config: Cfg,
        ): Boolean = true

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ): String = "c"

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ): String = "i"

        suspend fun <R> withClient(
            client: String?,
            block: suspend (String) -> R,
        ): R = withInitializedClient(client, block = block)

        suspend fun <T> safely(
            default: T,
            log: ((String) -> Unit)?,
            block: suspend () -> T,
        ): T = bestEffort("blame", default, log, block)
    }

    @Test
    fun `an uninitialized client names the platform`() =
        runTest {
            val module = Module()
            val failure = assertFailsWith<IllegalStateException> { module.withClient(null) { it } }
            assertEquals("Defaults not initialized", failure.message)
            assertEquals("ok", module.withClient("ok") { it })
        }

    @Test
    fun `best effort returns the default and logs the failure`() =
        runTest {
            val logged = mutableListOf<String>()
            val result = Module().safely(7, logged::add) { error("boom") }
            assertEquals(7, result)
            assertEquals(listOf("blame: boom"), logged)
            assertEquals(7, Module().safely(7, null) { error("quiet") })
            assertEquals(1, Module().safely(7, null) { 1 })
        }

    @Test
    fun `best effort rethrows cancellation`() =
        runTest {
            assertFailsWith<CancellationException> { Module().safely(7, null) { throw CancellationException("stop") } }
        }

    @Test
    fun `marked comment resolution defaults to nothing and an unparsable url is unsupported`() =
        runTest {
            val module = Module()
            assertEquals(0, module.resolveMarkedInlineComments("p", "1", "m"))
            assertEquals(0, module.resolveMarkedInlineCommentsForFiles("p", "1", "m", setOf("a.kt")))
            val failure = assertFailsWith<IllegalStateException> { module.fetchMergeRequestByUrl("http://x/1") }
            assertEquals("io.karandash.test.defaults does not support fetching merge requests", failure.message)
        }
}
