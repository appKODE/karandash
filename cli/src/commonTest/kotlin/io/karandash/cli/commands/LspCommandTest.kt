package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.lsp.LspCommand
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LspCommandTest {
    @Test
    fun `lsp serves the bootstrap through the bundled launcher`() {
        val bootstrap = PluginBootstrap()
        val served = mutableListOf<PluginBootstrap>()

        val result = LspCommand(bootstrap) { served += it }.test(emptyList())

        assertEquals(0, result.statusCode, result.output)
        assertSame(bootstrap, served.single())
    }

    @Test
    fun `lsp without a bundled launcher exits with the execution error code`() {
        val result = LspCommand(PluginBootstrap(), serve = null).test(emptyList())

        assertEquals(6, result.statusCode)
        assertTrue("JVM distribution" in result.stderr, result.stderr)
    }
}
