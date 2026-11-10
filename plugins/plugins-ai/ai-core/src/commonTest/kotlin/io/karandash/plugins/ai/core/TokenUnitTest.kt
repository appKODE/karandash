package io.karandash.plugins.ai.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TokenUnitTest {
    @Test
    fun `chars to tokens rounds up so an estimate never undercounts`() {
        assertEquals(0, TokenUnit.charsToTokens(0))
        assertEquals(1, TokenUnit.charsToTokens(1))
        assertEquals(1, TokenUnit.charsToTokens(4))
        assertEquals(2, TokenUnit.charsToTokens(5))
    }

    @Test
    fun `tokens to chars is the inverse at the unit`() {
        assertEquals(400, TokenUnit.tokensToChars(100))
        assertEquals(100, TokenUnit.charsToTokens(TokenUnit.tokensToChars(100)))
    }

    @Test
    fun `Cyrillic text estimates higher than ASCII text of the same length`() {
        assertEquals(TokenUnit.charsToTokens(8), TokenUnit.tokensOf("abcdefgh"))
        assertEquals(4, TokenUnit.tokensOf("абвгдежз"))
        assertTrue(TokenUnit.tokensOf("абвгдежз") > TokenUnit.tokensOf("abcdefgh"))
    }

    @Test
    fun `mixed text counts each part at its own rate and rounds up`() {
        assertEquals(0, TokenUnit.tokensOf(""))
        assertEquals(2, TokenUnit.tokensOf("ab я"))
    }
}
