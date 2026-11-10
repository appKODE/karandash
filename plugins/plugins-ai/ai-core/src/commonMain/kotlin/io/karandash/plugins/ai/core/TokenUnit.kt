package io.karandash.plugins.ai.core

/**
 * The one token unit of the AI budget: an estimate of [CHARS_PER_TOKEN] characters per token, rounded up
 * so an estimate never undercounts. Provider-reported `usage` stays the source of truth for spend.
 *
 * Text-aware estimates ([tokensOf]) count non-ASCII characters (Cyrillic, CJK, emoji) at
 * [NON_ASCII_CHARS_PER_TOKEN]: BPE tokenizers split them far denser than English, and a bare chars/4
 * would let a Russian prompt overshoot `ai.budget` by about half.
 */
object TokenUnit {
    /** Approximate characters per token for English text and code. */
    const val CHARS_PER_TOKEN = 4

    /** Approximate characters per token for non-ASCII text. */
    const val NON_ASCII_CHARS_PER_TOKEN = 2

    private const val ASCII_MAX = 0x7F

    /** Estimated tokens for [chars] characters, rounded up. */
    fun charsToTokens(chars: Int): Int = (chars + CHARS_PER_TOKEN - 1) / CHARS_PER_TOKEN

    /** Estimated tokens of [text]: ASCII at [CHARS_PER_TOKEN], everything else at [NON_ASCII_CHARS_PER_TOKEN], rounded up. */
    fun tokensOf(text: CharSequence): Int {
        val nonAscii = text.count { it.code > ASCII_MAX }
        val ascii = text.length - nonAscii
        return charsToTokens(ascii) + (nonAscii + NON_ASCII_CHARS_PER_TOKEN - 1) / NON_ASCII_CHARS_PER_TOKEN
    }

    /** Characters that fit in [tokens] tokens. */
    fun tokensToChars(tokens: Int): Int = tokens * CHARS_PER_TOKEN
}
