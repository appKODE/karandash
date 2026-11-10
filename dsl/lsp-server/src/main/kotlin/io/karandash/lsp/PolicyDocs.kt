package io.karandash.lsp

/**
 * Hover documentation for properties inside a `policy { }` entry in the `policies {}` block.
 *
 * Extracted from [ModuleDocRegistry] to keep that object within the 500-line threshold.
 */
internal object PolicyDocs {
    val POLICY_PROPERTY_DOCS: Map<String, String> = mapOf(
        "when" to """
            # when

            **Policy Condition**

            Expression that must evaluate to true for this policy to apply.

            **Type:** `String`

            ## Supported predicates
            `is_draft`, `branch_matches(pattern)`, `has_ticket(KEY)`,
            `change_count > N`, `additions >= N`, `deletions < N`,
            `title_matches(pattern)`, `has_label(name)`,
            `&&`, `||`, `not()`

            ## Example
            ```
            when = "has_ticket(PROJ) && not(is_draft)"
            ```
        """.trimIndent(),
        "rules" to """
            # rules

            **Scoped Rule IDs**

            List of rule IDs this policy applies to. When omitted, the policy applies to all configured rules.

            **Type:** `List<String>`

            ## Example
            ```
            rules = listOf("commit-body", "title-format")
            ```
        """.trimIndent(),
        "enabled" to """
            # enabled

            **Rule Enablement Override**

            Enable or disable the affected rules when the condition matches.

            **Type:** `Boolean`

            ## Example
            ```
            enabled = false
            ```
        """.trimIndent(),
        "severity" to """
            # severity

            **Severity Override**

            Override the severity for all findings from the affected rules.

            **Type:** `String` - one of `error`, `warning`, `info`

            ## Example
            ```
            severity = "warning"
            ```
        """.trimIndent(),
        "override" to """
            # override

            **Config Override Block**

            Key-value pairs deep-merged into each affected rule's config when the condition matches.

            ## Example
            ```
            override {
                required = true
                pattern = "^CHANGELOG: .*${'$'}"
            }
            ```
        """.trimIndent(),
    )
}
