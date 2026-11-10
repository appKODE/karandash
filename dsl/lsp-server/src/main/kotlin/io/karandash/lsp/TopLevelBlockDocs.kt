package io.karandash.lsp

/** Static hover docs for top-level config blocks. */
internal object TopLevelBlockDocs {
    /**
     * Static docs for top-level config blocks that don't need dynamic registry content.
     * Keyed by [io.karandash.config.schema.ConfigSections] constant values.
     */
    val TOP_LEVEL_BLOCK_DOCS: Map<String, String> = mapOf(
        "policies" to """
            # policies

            **Global Conditional Overrides**

            Applies config overrides and enablement changes to rules when a condition is met.

            ## Properties
            - `when: String` - Condition expression (supports `is_draft`, `branch_matches(pattern)`, `has_ticket(KEY)`, `change_count > N`, `&&`, `||`, `not()`)
            - `override: Map<ruleId, config>` - Config overrides merged into each affected rule
            - `rules: List<String>` - Rule IDs affected (optional; default: all configured rules)
            - `enabled: Boolean` - Enable or disable affected rules (optional)

            ## Example
            ```yaml
            policies:
              - when: 'has_ticket(${'$'}{KARANDASH_JIRA_KEY})'
                override:
                  commit-body:
                    pattern: 'regex:^CHANGELOG: ...${'$'}'
                    required: true
            ```
        """.trimIndent(),
        "reporters" to """
            # reporters

            **Reporting Configuration**

            Configure how validation results are reported (console, comments, SARIF, Slack, etc.).

            ## Available Options
            - `console = true|false` - Console output
            - `markdown = true|false` - MR/PR comments
            - `slack { }` - Slack notifications
            - `sarif { }` - SARIF format output

            ## Example
            ```
            reporters {
                console = true
                markdown = true
                slack {
                    webhookUrl = env("SLACK_WEBHOOK")
                }
            }
            ```
        """.trimIndent(),
        "platforms" to """
            # platforms

            **Platform Service Configuration**

            Configure platform integrations for fetching MR/PR data, posting comments, and mutating state.

            ## Example
            ```
            platforms {
                gitlab {
                    url = "https://gitlab.com"
                    token = env.GITLAB_TOKEN
                }
            }
            ```
        """.trimIndent(),
        "profile" to """
            # profile

            **Profile Configuration**

            Select a predefined rule configuration profile. Profiles provide sensible defaults for common workflows.

            ## Simple form
            ```yaml
            profile: conventional-commits
            ```

            ## Extended form
            ```yaml
            profile:
              id: conventional-commits
              severity: error        # warning (default) or error
              overrides:
                rules:
                  titleFormat:
                    maxLength: 50
            ```

            ## Available profiles
            `minimal`, `conventional-commits`, `github-flow`, `gitflow`, `trunk-based`, `semantic-versioning`, `security-conscious`
        """.trimIndent(),
    )
}
