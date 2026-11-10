package io.karandash.validators

import io.karandash.api.RuleConfig
import io.karandash.security.TextRedactor
import io.karandash.size.KarandashSize
import kotlinx.serialization.Serializable

/**
 * Sub-config controlling diff scanning behaviour in the security rule.
 *
 * @property maxDiffSize Maximum diff size to scan for secrets. Diffs exceeding this
 *   threshold are skipped with a warning finding.
 */
@Serializable
data class SecurityScanConfig(
    val maxDiffSize: KarandashSize = KarandashSize.ofMb(1),
) {
    init {
        require(maxDiffSize.bytes in MIN_DIFF_SIZE.bytes..MAX_DIFF_SIZE.bytes) {
            "maxDiffSize must be between $MIN_DIFF_SIZE and $MAX_DIFF_SIZE"
        }
    }

    companion object {
        private val MIN_DIFF_SIZE = KarandashSize.ofKb(64)
        private val MAX_DIFF_SIZE = KarandashSize.ofMb(50)
    }
}

/**
 * Sub-config controlling the severity of specific security findings.
 *
 * @property secretFound Severity for detected secrets: `"error"` or `"warning"`.
 * @property largeDiffSkipped Severity for large-diff-skipped findings: `"error"` or `"warning"`.
 */
@Serializable
data class SecuritySeverityConfig(
    val secretFound: String = "error",
    val largeDiffSkipped: String = "warning",
) {
    init {
        require(secretFound.lowercase() in LEVELS) { "severities.secretFound must be one of $LEVELS, got '$secretFound'" }
        require(largeDiffSkipped.lowercase() in LEVELS) { "severities.largeDiffSkipped must be one of $LEVELS, got '$largeDiffSkipped'" }
    }

    companion object {
        val LEVELS = listOf("error", "warning", "info")
    }
}

/**
 * @property scanForSecrets Scans commits, diffs, title and description for [secretPatterns].
 *   The [sensitiveFiles] check is independent of it and runs whenever the list is non-empty.
 */
@Serializable
data class SecurityConfig(
    val scanForSecrets: Boolean = true,
    val secretPatterns: List<String> = defaultSecretPatterns,
    val sensitiveFiles: List<String> = defaultSensitiveFiles,
    val scan: SecurityScanConfig = SecurityScanConfig(),
    /** Not `severity:`, which is the scalar meta severity. */
    val severities: SecuritySeverityConfig = SecuritySeverityConfig(),
) : RuleConfig {
    companion object {
        /**
         * Key-and-value patterns for this rule plus every [TextRedactor.CREDENTIAL_FORMATS] format the AI redactor hides.
         * The redactor's looser `key: value` and bare `bearer` patterns are not here: as findings they would fire on prose.
         */
        val defaultSecretPatterns = TextRedactor.CREDENTIAL_FORMATS.map { it.pattern } + listOf(
            "(?i)(api[_-]?key|apikey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?",
            "(?i)(secret[_-]?key|secretkey)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?",
            "(?i)(password|passwd|pwd)\\s*[:=]\\s*['\"]?[^\\s'\"]{8,}['\"]?",
            "(?i)(access[_-]?token|accesstoken)\\s*[:=]\\s*['\"]?[a-zA-Z0-9_\\-]{20,}['\"]?",
            "(?i)(bearer\\s+[a-zA-Z0-9_\\-\\.]{20,})",
            "(?i)(aws[_-]?access[_-]?key[_-]?id)\\s*[:=]\\s*['\"]?[A-Z0-9]{20}['\"]?",
            "(?i)(aws[_-]?secret[_-]?access[_-]?key)\\s*[:=]\\s*['\"]?[a-zA-Z0-9/+=]{40}['\"]?",
            "(?i)(private[_-]?key)\\s*[:=]\\s*['\"]?-----BEGIN",
            "(?i)(github[_-]?token|gh[_-]?token)\\s*[:=]\\s*['\"]?ghp_[a-zA-Z0-9]{36}['\"]?",
            "(?i)(gitlab[_-]?token|gl[_-]?token)\\s*[:=]\\s*['\"]?glpat-[a-zA-Z0-9_\\-]{20}['\"]?"
        )
        val defaultSensitiveFiles = listOf(
            "**/.env",
            "**/.env.*",
            "**/config/secrets.yml",
            "**/config/credentials.yml",
            "**/*secret*",
            "**/*credential*",
            "**/*.pem",
            "**/*.key",
            "**/*.p12",
            "**/*.pfx",
            "**/id_rsa",
            "**/id_dsa"
        )
    }
}
