package io.karandash.lsp

/** Fallback hover docs for common validator properties. */
internal object PropertyDocs {
    /**
     * Fallback property-level docs for common properties shared across validators.
     */
    val FALLBACK_PROPERTY_DOCS: Map<String, String> = mapOf(
        "when" to """
            # when

            **Conditional Rule Gating**

            Gates rule execution on a condition. When the expression evaluates to `false`,
            the rule is skipped entirely. When `true` and an `override` block is present,
            the override is deep-merged into the rule's config before execution.

            **Type:** `String` (simple form) or block with `expression` + `override`

            ## Supported predicates
            `is_draft`, `branch_matches(pattern)`, `has_ticket(KEY)`,
            `change_count > N`, `additions >= N`, `deletions < N`,
            `title_matches(pattern)`, `has_label(name)`,
            `&&`, `||`, `not()`

            ## Simple form
            ```
            jiraTicket {
                when = "has_ticket(${'$'}{KARANDASH_JIRA_KEY})"
                projectKeys = listOf("PROJ")
            }
            ```

            ## Object form with override
            ```yaml
            rules:
              commit-body:
                when:
                  expression: 'has_ticket(${'$'}{KARANDASH_JIRA_KEY}) && has_tag(android)'
                  override:
                    required: true
                    pattern: 'regex:^CHANGELOG: ...${'$'}'
            ```
        """.trimIndent(),
        "pattern" to """
            # pattern

            **Regex Pattern**

            Regular expression pattern for validation.

            **Type:** `String`

            ## Example
            ```
            pattern = "^\\[PROJ-\\d+\\].*"
            ```
        """.trimIndent(),
        "message" to """
            # message

            **Custom Error Message**

            Custom error message to display when validation fails.

            **Type:** `String`

            ## Example
            ```
            message = "Title must start with [PROJ-XXX]"
            ```
        """.trimIndent(),
        "enabled" to """
            # enabled

            **Enable/Disable Validator**

            Enable or disable this validator.

            **Type:** `Boolean` (default: `true`)

            ## Example
            ```
            enabled = true
            ```
        """.trimIndent(),
        "minLength" to """
            # minLength

            **Minimum Length**

            Minimum required length for the text.

            **Type:** `Int`

            ## Example
            ```
            minLength = 10
            ```
        """.trimIndent(),
        "maxLength" to """
            # maxLength

            **Maximum Length**

            Maximum allowed length for the text.

            **Type:** `Int`

            ## Example
            ```
            maxLength = 100
            ```
        """.trimIndent(),
        "requireTicket" to """
            # requireTicket

            **Require Ticket Reference**

            Whether to require a ticket reference in the title.

            **Type:** `Boolean`

            ## Example
            ```
            requireTicket = true
            ```
        """.trimIndent(),
        "format" to """
            # format

            **Format Type**

            Format type for validation (e.g., 'conventional', 'custom').

            **Type:** `String`

            ## Example
            ```
            format = "conventional"
            ```
        """.trimIndent(),
        "projectKeys" to """
            # projectKeys

            **JIRA Project Keys**

            List of valid JIRA project keys.

            **Type:** `List<String>`

            ## Example
            ```
            projectKeys = listOf("PROJ", "TEAM", "BUG")
            ```
        """.trimIndent(),
        "validateStatus" to """
            # validateStatus

            **Validate Ticket Status**

            List of required JIRA ticket statuses.

            **Type:** `List<String>`

            ## Example
            ```
            validateStatus = listOf("In Progress", "In Review")
            ```
        """.trimIndent(),
        "validateAssignee" to """
            # validateAssignee

            **Validate Ticket Assignee**

            Whether to validate that the ticket is assigned.

            **Type:** `Boolean`

            ## Example
            ```
            validateAssignee = true
            ```
        """.trimIndent(),
    )
}
