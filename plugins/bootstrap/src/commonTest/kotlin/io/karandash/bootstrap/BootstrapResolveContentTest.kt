@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.bootstrap

import io.karandash.api.exception.ContentResolveException
import io.karandash.sdk.content.ContentSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BootstrapResolveContentTest {
    private fun context() = BootstrapPluginContext(moduleId = null, hostVersion = "1.0", workingDirectory = ".")

    @Test
    fun `inline content resolves without a resolver`() =
        runTest {
            assertEquals("hello", context().resolveContent(ContentSource.Inline("hello")))
        }

    @Test
    fun `file content before config load fails with a clear error`() =
        runTest {
            val error = assertFailsWith<ContentResolveException> {
                context().resolveContent(ContentSource.LocalFile("prompt.md"))
            }
            assertTrue("before the config is loaded" in error.message.orEmpty(), error.message)
        }

    @Test
    fun `run contexts delegate to the run resolver - also through forModule`() =
        runTest {
            val contexts = PluginBootstrap().newRunContexts()
            contexts.contentResolver = { source -> "resolved:${(source as ContentSource.LocalFile).path}" }

            val ctx = (contexts.contextWithConfig("ai") as BootstrapPluginContext).forModule("ai-child")

            assertEquals("resolved:prompt.md", ctx.resolveContent(ContentSource.LocalFile("prompt.md")))
        }
}
