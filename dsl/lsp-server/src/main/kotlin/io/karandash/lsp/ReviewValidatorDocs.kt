package io.karandash.lsp

/** Fallback hover docs for validators (starting at codeowners). */
internal object ReviewValidatorDocs {
    val DOCS: Map<String, String> = mapOf(
        "codeowners" to """
            # codeowners

            **CODEOWNERS Validator**

            Validates that code owners have approved changes to their owned files.

            ## Properties
            - `path: String` - Path to CODEOWNERS file (optional, default: ".github/CODEOWNERS")
            - `requireOwnerApproval: Boolean` - Require owner approval (default: true)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            codeowners {
                path = ".github/CODEOWNERS"
                requireOwnerApproval = true
            }
            ```
        """.trimIndent(),
        "approval" to """
            # approval

            **Approval Requirements Validator**

            Validates that MR/PR has required number and type of approvals.

            ## Properties
            - `minApprovals: Int` - Minimum number of approvals (optional)
            - `requiredApprovers: List<String>` - Required approver usernames (optional)
            - `noChangesSinceApproval: Boolean` - No changes since approval (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            approval {
                minApprovals = 2
                requiredApprovers = listOf("tech-lead", "architect")
                noChangesSinceApproval = true
            }
            ```
        """.trimIndent(),
        "reviewer" to """
            # reviewer

            **Reviewer Requirements Validator**

            Validates that specific reviewers or teams are assigned to the MR/PR.

            ## Properties
            - `requiredReviewers: List<String>` - Required reviewer usernames (optional)
            - `requiredTeams: List<String>` - Required team names (optional)
            - `excludedReviewers: List<String>` - Excluded reviewer usernames (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            reviewer {
                requiredTeams = listOf("backend-team", "security-team")
                excludedReviewers = listOf("bot-user")
            }
            ```
        """.trimIndent(),
        "cicdStatus" to """
            # cicdStatus

            **CI/CD Status Validator**

            Validates that CI/CD pipelines pass before merging.

            ## Properties
            - `requirePassed: Boolean` - Require CI/CD to pass (default: true)
            - `requiredJobs: List<String>` - Required job names (optional)
            - `blockOnFailing: Boolean` - Block on failing jobs (default: true)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            cicdStatus {
                requirePassed = true
                requiredJobs = listOf("test", "build", "security-scan")
            }
            ```
        """.trimIndent(),
        "conflict" to """
            # conflict

            **Merge Conflict Validator**

            Validates that the MR/PR has no merge conflicts.

            ## Properties
            - `blockOnConflicts: Boolean` - Block on merge conflicts (default: true)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            conflict {
                blockOnConflicts = true
            }
            ```
        """.trimIndent(),
        "draftPrevention" to """
            # draft

            **Draft Status Validator**

            Validates draft/WIP status of MR/PR.

            ## Properties
            - `blockDraft: Boolean` - Block draft MRs/PRs (default: true)
            - `wipPatterns: List<String>` - WIP patterns to detect (optional)
            - `message: String` - Custom error message (optional)
            - `enabled: Boolean` - Enable/disable this validator (default: true)

            ## Example
            ```
            draft {
                blockDraft = true
                wipPatterns = listOf("WIP", "DRAFT", "DO NOT MERGE")
            }
            ```
        """.trimIndent(),
    )
}
