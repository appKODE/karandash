package io.karandash.plugins.ai.core

/**
 * Filters out paths that must never be forwarded to an AI provider as full file
 * content, as related-file snippets or as a diff: [SecretRedactor] keeps the path of
 * such a file but replaces its diff with a placeholder and drops its content, so no
 * chunk carries it, and "context" providers that send arbitrary blobs refuse it outright.
 *
 * The filter is conservative - any uncertainty skews to "sensitive". The match is
 * done on the file name and path segments; extension checks are case-insensitive.
 */
object SensitiveFileFilter {
    private val SENSITIVE_EXTENSIONS: Set<String> = setOf(
        "env",
        "pem",
        "key",
        "p12",
        "pfx",
        "jks",
        "keystore",
        "crt",
        "cer",
        "credentials",
        "secret",
        "secrets",
    )

    private val SENSITIVE_FILENAMES: Set<String> = setOf(
        ".env",
        ".env.local",
        ".env.production",
        ".env.staging",
        ".env.development",
        "credentials.json",
        "credentials.yml",
        "credentials.yaml",
        "service-account.json",
        "service_account.json",
        "id_rsa",
        "id_ed25519",
        "id_ecdsa",
        "id_dsa",
        ".npmrc",
        ".pypirc",
        ".netrc",
        ".pgpass",
        ".htpasswd",
        ".aws/credentials",
    )

    private val SENSITIVE_PATH_SEGMENTS: Set<String> = setOf(
        ".ssh",
        "secrets",
        "private-keys",
        "certificates",
        ".aws",
    )

    /**
     * @return true when [path] looks like a credential / secret bundle that must not
     *     be sent to an AI provider.
     */
    fun isSensitive(path: String): Boolean {
        if (path.isBlank()) return false
        val normalised = path.replace('\\', '/').trim()
        val fileName = normalised.substringAfterLast('/')
        val lowerFileName = fileName.lowercase()

        if (lowerFileName in SENSITIVE_FILENAMES) return true
        if (extensionOf(lowerFileName) in SENSITIVE_EXTENSIONS) return true
        if (matchesSegment(normalised)) return true
        return false
    }

    private fun extensionOf(fileName: String): String {
        val dot = fileName.lastIndexOf('.')
        if (dot <= 0 || dot == fileName.length - 1) return ""
        return fileName.substring(dot + 1)
    }

    private fun matchesSegment(path: String): Boolean {
        val parts = path.split('/')
        for (segment in parts) {
            if (segment in SENSITIVE_PATH_SEGMENTS) return true
        }
        return false
    }
}
