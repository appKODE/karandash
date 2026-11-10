package io.karandash.lsp

/** Fallback hover docs for validators (starting at labels). */
internal object QualityValidatorDocs {
    val DOCS: Map<String, String> = mapOf(
        "labels" to """
            # labels

            **Label Requirements Validator**

            Validates that MR/PR has required labels and no forbidden labels.

            ## Properties
            - `requiredLabels: List<String>` - Required label names (optional)
            - `forbiddenLabels: List<String>` - Forbidden label names (optional)
            - `conditionalLabels: List<ConditionalLabel>` - Conditional label rules (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            labels {
                requiredLabels = listOf("reviewed", "tested")
                forbiddenLabels = listOf("do-not-merge")
            }
            ```
        """.trimIndent(),
        "sizeLimits" to """
            # sizeLimits

            **Size Limits Validator**

            Validates that MR/PR size is within acceptable limits.

            ## Properties
            - `maxFiles: Int` - Maximum number of files changed (optional)
            - `maxLines: Int` - Maximum lines changed (optional)
            - `maxCommits: Int` - Maximum number of commits (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            sizeLimits {
                maxFiles = 50
                maxLines = 1000
                maxCommits = 20
            }
            ```
        """.trimIndent(),
        "security" to """
            # security

            **Security Validator**

            Validates security requirements including secret scanning and sensitive file detection.

            ## Properties
            - `scanForSecrets: Boolean` - Enable secret scanning (default: true)
            - `secretPatterns: List<String>` - Regex patterns for secrets (optional)
            - `sensitiveFiles: List<String>` - Sensitive file patterns (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            security {
                scanForSecrets = true
                secretPatterns = listOf("API[_-]?KEY", "PASSWORD")
                sensitiveFiles = listOf("*.key", "*.pem", ".env")
            }
            ```
        """.trimIndent(),
        "descriptionFormat" to """
            # descriptionFormat

            **MR/PR Description Format Validator**

            Validates that MR/PR descriptions follow a required structure with defined sections.

            ## Properties
            - `mrSections: List<SectionRule>` - Required sections with name, pattern, and required flag

            ## Example
            ```
            descriptionFormat {
                requiredSections = listOf("Summary", "Test Plan", "Changelog")
            }
            ```
        """.trimIndent(),
    )
}
