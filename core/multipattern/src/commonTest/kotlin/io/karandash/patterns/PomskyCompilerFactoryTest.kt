package io.karandash.patterns

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PomskyCompilerFactoryTest {
    @Test
    fun `create returns a non-null PomskyCompiler`() {
        val compiler = PomskyCompilerFactory.create()
        assertNotNull(compiler)
    }

    @Test
    fun `created compiler compiles a simple expression successfully`() {
        val compiler = PomskyCompilerFactory.create()
        val result = compiler.compile("^ 'hello' $")
        assertNotNull(result.regex)
        assertNull(result.error)
        assertEquals("^hello$", result.regex)
    }

    @Test
    fun `created compiler returns error for invalid expression`() {
        val compiler = PomskyCompilerFactory.create()
        val result = compiler.compile("@@@ invalid")
        assertNull(result.regex)
        assertNotNull(result.error)
    }

    @Test
    fun `created compiler result has either regex or error but not both`() {
        val compiler = PomskyCompilerFactory.create()
        val result = compiler.compile("^ 'test' $")
        val hasRegex = result.regex != null
        val hasError = result.error != null
        assertTrue(
            hasRegex != hasError,
            "Expected exactly one of regex or error to be set, got: regex=${result.regex}, error=${result.error}",
        )
    }

    @Test
    fun `created compiler handles let bindings`() {
        val compiler = PomskyCompilerFactory.create()
        val result = compiler.compile("let type = 'feat' | 'fix'; ^ type ': '")
        assertNotNull(result.regex)
        assertNull(result.error)
    }

    @Test
    fun `created compiler reports warnings in result`() {
        val compiler = PomskyCompilerFactory.create()
        // Simple expression - verify warnings list exists (may be empty)
        val result = compiler.compile("'hello'")
        assertNotNull(result.warnings)
    }
}
