package io.karandash.security

import io.karandash.multipattern.MultiPatternMatcher

/**
 * Redacts potential secrets from arbitrary text.
 *
 * Uses a fast [MultiPatternMatcher] (Aho-Corasick) pre-filter to skip
 * expensive regex application when no keywords are present. Generic and
 * reusable - not tied to any specific domain model.
 *
 * @param additionalPatterns Extra regex patterns applied on top of [DEFAULT_PATTERNS].
 */
class TextRedactor(
    additionalPatterns: List<Regex> = emptyList(),
) {
    private val patterns: List<Regex> = DEFAULT_PATTERNS + additionalPatterns

    /**
     * Redacts all known secret patterns from [text].
     *
     * @return The redacted text, or the original text unchanged if no secrets were found.
     */
    fun redact(text: String): String {
        if (!FAST_MATCHER.containsAny(text.lowercase())) return text
        var result = text
        for (pattern in patterns) {
            // The key's name stays readable (`WEATHER_API_KEY = [REDACTED]`): only its value is a secret.
            result =
                if (pattern === ASSIGNMENT) pattern.replace(result) { it.groupValues[1] + REDACTED } else pattern.replace(result, REDACTED)
        }
        return result
    }

    companion object {
        /** Replacement string for redacted content. */
        const val REDACTED = "[REDACTED]"

        private val FAST_MATCHER = MultiPatternMatcher.build(
            listOf(
                "password",
                "secret",
                "token",
                "api_key",
                "api-key",
                "apikey",
                "private_key",
                "private-key",
                "privatekey",
                "bearer",
                "-----begin",
                "ghp_",
                "glpat-",
                "sk-",
                "akia",
                "jdbc:",
            ),
        )

        // `(?!=)` skips comparisons (`password == null`); a bare boolean/null literal (not `null-ish`) is not a secret.
        // Group 1 is the key's name and operator, kept by [redact]. The value is one quoted string or a run that stops only
        // at whitespace or a bare quote: punctuation in a password is redacted with it, and an escape pair (`\"`) is taken
        // whole, so a JSON string around the value keeps its closing quote and stays valid.
        // A value already [REDACTED] is skipped, so a second pass changes nothing. An unquoted value that is plain code is
        // kept: a letters-only member access (`self.token`) or a lowerCamel name whose humps are words (`deviceToken`).
        // Digits disqualify both, so a mixed-case random key (`xK9mP2qR`) or a JWT is still redacted.
        private val ASSIGNMENT =
            Regex(
                """((?i:password|secret|token|api[_-]?key|private[_-]?key)\s*[:=](?!=)\s*)""" +
                    """(?!(?i:true|false|null)(?:[\s,;)}\]'"]|$))(?!\[REDACTED])""" +
                    """(?!(?!(?i:true|false|null)\.)(?:[A-Za-z_]+(?:\??\.[A-Za-z_]+)+|[a-z]{2,}(?:[A-Z][a-z]{2,})+)""" +
                    """(?:[\s,;)}\]'"]|$))""" +
                    """(?:"[^"\\]*"|'[^']*'|(?:[^\s"'\\]|\\.)+)"""
            )

        /**
         * Credential formats specific enough to flag on sight (token prefixes, key headers, a JDBC password).
         * The `security` rule scans for these too, so nothing it passes is a format redaction would hide.
         */
        val CREDENTIAL_FORMATS: List<Regex> = listOf(
            Regex("""-----BEGIN\s+(RSA\s+)?PRIVATE\s+KEY-----"""),
            Regex("""ghp_[a-zA-Z0-9]{36}"""),
            Regex("""glpat-[a-zA-Z0-9\-_]{20,}"""),
            Regex("""sk-[a-zA-Z0-9]{48}"""),
            Regex("""sk-ant-[a-zA-Z0-9\-]{95,}"""),
            Regex("""AKIA[A-Z0-9]{16}"""),
            Regex("""(?i)jdbc:[^\s]+password=[^\s&"']+"""),
        )

        /** Default secret detection patterns: [CREDENTIAL_FORMATS] plus any `password|secret|token|api key` assignment and bearer value. */
        val DEFAULT_PATTERNS: List<Regex> = listOf(ASSIGNMENT, Regex("""(?i)bearer\s+[a-zA-Z0-9._\-]+""")) + CREDENTIAL_FORMATS

        /** Redacts secrets from text using the default pattern set. */
        fun redact(text: String): String = TextRedactor().redact(text)

        /** Quoted JSON/YAML secret fields (`"apiKey": "…"`) - the default patterns need `key:` adjacency. */
        private val QUOTED_SECRET_FIELD =
            Regex("""(?i)"[^"\s]*(password|secret|token|api[_-]?key|private[_-]?key)[^"\s]*"\s*:(?!=)\s*"(?!(?:true|false|null)")[^"]*"""")

        private val LOG_REDACTOR = TextRedactor(listOf(QUOTED_SECRET_FIELD))

        /**
         * Redaction for log output: strips credentials from URLs via [UrlSanitizer], then applies
         * [DEFAULT_PATTERNS] plus quoted secret fields. Cheap when nothing matches (keyword prefilter).
         */
        fun redactForLog(text: String): String = LOG_REDACTOR.redact(UrlSanitizer.sanitizeText(text))
    }
}
