package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals

/** The Windows child env block drops `unsetEnv` names, case-insensitively, and keeps everything else. */
class WindowsEnvBlockTest {
    @Test
    fun `unset names are dropped case-insensitively and the block is NUL terminated`() {
        val entries = listOf("=C:=C:\\work", "Path=C:\\bin", "KARANDASH_TOKEN=s3cret", "Home=C:\\u", "A=b=c")

        val block = WindowsEnvBlock.of(entries, setOf("karandash_token", "HOME"))

        assertEquals("=C:=C:\\work\u0000Path=C:\\bin\u0000A=b=c\u0000\u0000", block)
    }

    @Test
    fun `nothing kept is a lone terminator`() {
        assertEquals("\u0000", WindowsEnvBlock.of(listOf("X=1"), setOf("x")))
    }
}
