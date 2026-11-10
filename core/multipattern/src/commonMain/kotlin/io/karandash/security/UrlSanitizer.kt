package io.karandash.security

/**
 * Strips credentials from URLs before they reach logs.
 *
 * - userinfo (`https://user:pass@host`) is removed
 * - query/fragment values whose parameter name looks sensitive (`private_token`, `key`,
 *   `access_token`, `X-Amz-Signature`, `jwt`, …) are replaced with [TextRedactor.REDACTED]
 * - webhook paths (`hooks.slack.com/services/…`, `…/webhooks/…`) keep only the prefix - the
 *   path itself is the secret
 *
 * Host, port, non-sensitive path and parameter names stay visible so logs remain useful.
 */
object UrlSanitizer {
    private const val SCHEME_SEPARATOR = "://"
    private const val WEBHOOK_HOST_PREFIX = "hooks."
    private const val WEBHOOK_SEGMENT = "webhook"

    /** Path segments kept on a webhook host without a `webhook` marker segment (`/services`). */
    private const val WEBHOOK_HOST_KEPT_SEGMENTS = 2

    private val SENSITIVE_PARAM_HINTS = listOf(
        "token",
        "key",
        "secret",
        "password",
        "passwd",
        "auth",
        "sig",
        "credential",
        "session",
        "code",
        "jwt",
    )

    /** Matches absolute URLs embedded in free text (hardcoded constant, not user input). */
    private val URL_IN_TEXT = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://[^\s"'<>`]+""")

    /** Sanitizes a single absolute URL. Strings without `://` are returned unchanged. */
    fun sanitize(url: String): String {
        val schemeEnd = url.indexOf(SCHEME_SEPARATOR)
        if (schemeEnd < 0) return url
        val afterScheme = url.substring(schemeEnd + SCHEME_SEPARATOR.length)
        val authorityEnd = afterScheme
            .indexOfFirst { it == '/' || it == '?' || it == '#' }
            .takeIf { it >= 0 } ?: afterScheme.length
        val hostPort = afterScheme.substring(0, authorityEnd).substringAfterLast('@')
        val remainder = afterScheme.substring(authorityEnd)
        val beforeFragment = remainder.substringBefore('#')
        return buildString {
            append(url, 0, schemeEnd + SCHEME_SEPARATOR.length)
            append(hostPort)
            append(sanitizePath(hostPort.substringBefore(':').lowercase(), beforeFragment.substringBefore('?')))
            if ('?' in beforeFragment) append('?').append(sanitizeParams(beforeFragment.substringAfter('?')))
            if ('#' in remainder) append('#').append(sanitizeParams(remainder.substringAfter('#')))
        }
    }

    /** Sanitizes every absolute URL found in [text]; the rest of the text is untouched. */
    fun sanitizeText(text: String): String {
        if (!text.contains(SCHEME_SEPARATOR)) return text
        return URL_IN_TEXT.replace(text) { sanitize(it.value) }
    }

    private fun sanitizePath(
        host: String,
        path: String,
    ): String {
        val segments = path.split('/')
        val marker = segments.indexOfFirst { it.contains(WEBHOOK_SEGMENT, ignoreCase = true) }
        val keep = when {
            marker >= 0 -> marker + 1
            host.startsWith(WEBHOOK_HOST_PREFIX) -> WEBHOOK_HOST_KEPT_SEGMENTS
            else -> return path
        }
        if (keep >= segments.size) return path
        return (segments.take(keep) + TextRedactor.REDACTED).joinToString("/")
    }

    private fun sanitizeParams(params: String): String =
        params.split('&').joinToString("&") { param ->
            val name = param.substringBefore('=')
            val sensitive = '=' in param && SENSITIVE_PARAM_HINTS.any { name.contains(it, ignoreCase = true) }
            if (sensitive) "$name=${TextRedactor.REDACTED}" else param
        }
}
