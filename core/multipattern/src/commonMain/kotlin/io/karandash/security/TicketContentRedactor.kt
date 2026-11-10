package io.karandash.security

/**
 * Redacts PII and secrets from knowledge-system content (Jira tickets, Confluence pages,
 * CI logs, etc.) before injecting into AI prompts.
 *
 * Applies two passes:
 * 1. Secret redaction via [TextRedactor].
 * 2. PII redaction via [PiiPatterns].
 */
object TicketContentRedactor {
    private val secretRedactor = TextRedactor()

    private val piiReplacements: List<Pair<Regex, String>> = listOf(
        PiiPatterns.EMAIL to "[PII:email]",
        PiiPatterns.PHONE_E164 to "[PII:phone]",
        PiiPatterns.PHONE_US to "[PII:phone]",
        PiiPatterns.SSN_LIKE to "[PII:ssn]",
        PiiPatterns.CREDIT_CARD_LIKE to "[PII:cc]",
        PiiPatterns.JIRA_MENTION to "@user",
        PiiPatterns.SHELL_EXPORT to "[REDACTED:export]",
        PiiPatterns.CONNECTION_STRING to "[REDACTED:connection-string]",
        PiiPatterns.AUTH_HEADER to "[REDACTED:auth-header]",
    )

    // A `/`-separated name followed by a file extension (`io/karandash/core/Foo.kt`) is a path, not a token.
    private val fileExtension = Regex("""\.[A-Za-z][A-Za-z0-9]{0,5}(?![A-Za-z0-9])""")

    /** Returns [text] with secrets and PII replaced by placeholder tokens. */
    fun redact(text: String): String {
        if (text.isEmpty()) return text
        var result = secretRedactor.redact(text)
        for ((pattern, replacement) in piiReplacements) {
            result = pattern.replace(result, replacement)
        }
        return PiiPatterns.BASE64_BLOB.replace(result) { blob ->
            val path = '/' in blob.value && fileExtension.matchesAt(result, blob.range.last + 1)
            if (path || !looksEncoded(blob.value)) blob.value else "[REDACTED:token]"
        }
    }

    /**
     * Encoded data mixes at least three of upper case, lower case, digits and `+`/`/`; a git SHA (hex) or a long
     * identifier uses two at most.
     */
    private fun looksEncoded(value: String): Boolean =
        listOf<(Char) -> Boolean>(Char::isUpperCase, Char::isLowerCase, Char::isDigit, { c -> c == '+' || c == '/' })
            .count { charClass -> value.any(charClass) } >= ENCODED_CHAR_CLASSES

    private const val ENCODED_CHAR_CLASSES = 3
}
