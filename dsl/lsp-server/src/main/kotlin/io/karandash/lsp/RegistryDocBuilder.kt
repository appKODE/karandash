package io.karandash.lsp

/**
 * Builds dynamic hover documentation strings from [CachedRegistryMetadata].
 *
 * Used by [HoverProvider] for the `rules` and `providers` top-level blocks, where the list of
 * available modules must be populated at runtime rather than from a static map.
 */
object RegistryDocBuilder {
    /** Builds the `rules` block doc with a dynamic list of available validators. */
    fun buildRulesBlockDoc(metadata: CachedRegistryMetadata): String {
        val validatorBullets = metadata.ruleDescriptions
            .joinToString("\n") { (ruleId, desc) -> "- `$ruleId` - $desc" }

        return """
            # rules

            **Validation Rules Configuration**

            Contains all validation rules that will be applied to merge requests/pull requests.

            ## Available Validators
            $validatorBullets

            ## Example
            ```
            rules {
                titleFormat {
                    pattern = "^\\[.*\\].*"
                    minLength = 10
                }
            }
            ```
            """.trimIndent()
    }

    /** Builds the `providers` block doc with a dynamic list of available providers. */
    fun buildProvidersBlockDoc(metadata: CachedRegistryMetadata): String {
        val providerBullets = metadata.providerDescriptions
            .joinToString("\n") { (name, desc) -> "- `$name` - $desc" }

        return """
            # providers

            **Provider Configuration**

            Configure merge request/pull request providers (GitLab, GitHub, Bitbucket, etc.).

            ## Available Providers
            $providerBullets

            ## Example
            ```
            providers {
                gitlab {
                    url = "https://gitlab.com"
                    token = env("GITLAB_TOKEN")
                }
            }
            ```
            """.trimIndent()
    }
}
