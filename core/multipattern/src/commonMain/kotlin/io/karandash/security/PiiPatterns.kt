package io.karandash.security

/** Centralized PII regex constants for ticket/knowledge-system content redaction. */
internal object PiiPatterns {
    /** Matches standard email addresses at word boundaries. */
    val EMAIL = Regex("""\b[A-Za-z0-9._%+\-]+@[A-Za-z0-9.\-]+\.[A-Za-z]{2,}\b""")

    /** Matches E.164 international phone numbers (+1234567890, up to 15 digits). */
    val PHONE_E164 = Regex("""\+\d{10,15}\b""")

    /** Matches US-style phone numbers (e.g. 555-123-4567, (555) 123-4567). */
    val PHONE_US = Regex("""\b(?:\(\d{3}\)[\s\-]?|\d{3}[\-\.])\d{3}[\-\.]\d{4}\b""")

    /** Matches SSN-like patterns (NNN-NN-NNNN) - conservative, requires word boundaries. */
    val SSN_LIKE = Regex("""\b\d{3}-\d{2}-\d{4}\b""")

    /**
     * Matches credit-card-like 16-digit groups (groups of 4 separated by space/dash).
     * Requires non-digit boundary to avoid ticket keys like PROJ-1234.
     */
    val CREDIT_CARD_LIKE = Regex("""\b\d{4}[\s\-]\d{4}[\s\-]\d{4}[\s\-]\d{4}\b""")

    /** Matches Jira/Confluence @mentions; a capitalised `@Name` is a code annotation (`@Override`), not a user. */
    val JIRA_MENTION = Regex("""@[a-z0-9_][A-Za-z0-9._\-]{1,63}\b""")

    /** Matches shell export lines that might carry secrets (e.g. export TOKEN=abc123). */
    val SHELL_EXPORT = Regex("""(?m)^export\s+\w+=\S+""")

    /** Matches common connection-string URI schemes carrying embedded credentials. */
    val CONNECTION_STRING = Regex(
        """(?:mongodb|postgres|postgresql|redis|mysql|amqp|amqps)://[^\s"'<>]+""",
        RegexOption.IGNORE_CASE,
    )

    /** Matches Authorization header values (Bearer, Basic, Token). */
    val AUTH_HEADER = Regex(
        """(?i)\bAuthorization:\s*(?:Bearer|Basic|Token)\s+\S+""",
    )

    /** Matches base64-looking blobs of 40+ chars bounded by non-base64 characters. */
    val BASE64_BLOB = Regex("""(?<![A-Za-z0-9+/=])[A-Za-z0-9+/]{40,}={0,2}(?![A-Za-z0-9+/=])""")
}
