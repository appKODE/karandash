package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals

class PosixUnsetEnvTest {
    @Test
    fun `exec prefix without variables is plain exec`() {
        assertEquals("exec ", PosixUnsetEnv.execPrefix(emptySet()))
    }

    @Test
    fun `exec prefix unsets each variable quoted`() {
        assertEquals("exec env -u 'HOME' -u 'PATH' ", PosixUnsetEnv.execPrefix(linkedSetOf("HOME", "PATH")))
    }

    @Test
    fun `resolver without variables quotes words and has no prelude`() {
        val resolver = PosixUnsetEnv.Resolver(emptySet(), "/tmp/err")

        assertEquals("'node' 'a b' 'it'\"'\"'s'", resolver.commandLine(listOf("node", "a b", "it's")))
        assertEquals("", resolver.prelude)
    }

    @Test
    fun `resolver with variables resolves each program before env runs`() {
        val resolver = PosixUnsetEnv.Resolver(setOf("PATH"), "/tmp/err")

        val timeout = resolver.program("timeout")
        val line = resolver.commandLine(listOf("node", "-e"))

        assertEquals("\"\$karandash_prog_0\"", timeout)
        assertEquals("\"\$karandash_prog_1\" '-e'", line)
        assertEquals(
            "karandash_prog_0=\$(command -v 'timeout') || { echo 'timeout: not found' >&2; exit 127; } 2> '/tmp/err'; " +
                "karandash_prog_1=\$(command -v 'node') || { echo 'node: not found' >&2; exit 127; } 2> '/tmp/err'; ",
            resolver.prelude,
        )
    }

    @Test
    fun `exec prefix keeps a name with a quote and a newline one quoted word`() {
        val prefix = PosixUnsetEnv.execPrefix(setOf("A'B\nC"))

        assertEquals("exec env -u 'A'\"'\"'B\nC' ", prefix)
    }
}
