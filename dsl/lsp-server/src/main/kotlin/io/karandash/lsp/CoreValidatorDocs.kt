package io.karandash.lsp

/** Fallback hover docs for validators (starting at titleFormat). */
internal object CoreValidatorDocs {
    val DOCS: Map<String, String> = mapOf(
        "titleFormat" to """
            # titleFormat

            **MR/PR Title Format Validator**

            Validates that merge request or pull request titles follow a specific format and length requirements.

            ## Properties
            - `pattern: String` - Regex pattern the title must match
            - `minLength: Int` - Minimum title length (optional)
            - `maxLength: Int` - Maximum title length (optional)
            - `requireTicket: Boolean` - Require ticket reference (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            titleFormat {
                pattern = "^\\[PROJ-\\d+\\].*"
                minLength = 10
                maxLength = 100
                message = "Title must start with [PROJ-XXX] and be 10-100 chars"
            }
            ```
        """.trimIndent(),
        "commitMessages" to """
            # commitMessages

            **Commit Message Validator**

            Validates that commit messages follow a specific format (e.g., Conventional Commits).

            ## Properties
            - `format: String` - Format type (e.g., 'conventional', 'custom')
            - `pattern: String` - Regex pattern for custom format
            - `minLength: Int` - Minimum message length (optional)
            - `maxLength: Int` - Maximum message length (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            commitMessages {
                format = "conventional"
                minLength = 10
                maxLength = 72
            }
            ```
        """.trimIndent(),
        "jiraTicket" to """
            # jiraTicket

            **JIRA Ticket Validator**

            Validates that MR/PR references valid JIRA tickets and optionally checks ticket status and assignee.

            ## Properties
            - `projectKeys: List<String>` - Valid JIRA project keys (required)
            - `pattern: String` - Regex pattern for ticket ID (optional)
            - `validateStatus: List<String>` - Required ticket statuses (optional)
            - `validateAssignee: Boolean` - Validate ticket assignee (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            jiraTicket {
                projectKeys = listOf("PROJ", "TEAM")
                validateStatus = listOf("In Progress", "In Review")
                validateAssignee = true
            }
            ```
        """.trimIndent(),
        "branchNaming" to """
            # branchNaming

            **Branch Naming Validator**

            Validates that branch names follow specific naming conventions.

            ## Properties
            - `pattern: String` - Regex pattern for branch names
            - `allowedPatterns: List<String>` - List of allowed patterns (alternative to pattern)
            - `requireSourceMatch: Boolean` - Require source branch to match (optional)
            - `requireTargetMatch: Boolean` - Require target branch to match (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            branchNaming {
                allowedPatterns = listOf(
                    "^feature/.*",
                    "^bugfix/.*",
                    "^hotfix/.*"
                )
            }
            ```
        """.trimIndent(),
        "fileChanges" to """
            # fileChanges

            **File Changes Validator**

            Validates file changes against specific patterns and rules.

            ## Properties
            - `patterns: List<FilePatternRule>` - File pattern rules
            - `requireApproval: List<String>` - Required approvers for matched files (optional)
            - `blockOnDraft: Boolean` - Block draft MRs/PRs (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            fileChanges {
                patterns = listOf(
                    filePattern("*.sql") { requireApproval = listOf("dba-team") }
                )
            }
            ```
        """.trimIndent(),
    )
}
