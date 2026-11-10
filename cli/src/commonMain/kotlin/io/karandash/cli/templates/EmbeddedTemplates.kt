package io.karandash.cli.templates

/**
 * Embeds all YAML template content as string constants.
 * This replaces JVM resource loading to enable templates on all platforms.
 */
object EmbeddedTemplates {
    /**
     * Get template content by category and name.
     * @param category One of "base", "platforms"
     * @param name Template name (e.g. "minimal", "github")
     * @return YAML content string or null if not found
     */
    fun get(
        category: String,
        name: String
    ): String? = categoryMap(category)?.get(name)

    /**
     * Get all template keys for a given category.
     * @param category One of "base", "platforms"
     * @return Set of template names in the category, or empty set if category is unknown
     */
    fun keys(category: String): Set<String> = categoryMap(category)?.keys ?: emptySet()

    private fun categoryMap(category: String): Map<String, String>? =
        when (category) {
            "base" -> baseTemplates
            "platforms" -> platformTemplates
            else -> null
        }

    internal val baseTemplates = mapOf(
        "minimal" to """
# Karandash Minimal Configuration Template
# Lightweight validation for basic merge request checks
#
# This template enables essential MR validation rules with minimal overhead.
# Perfect for projects just getting started with MR validation.

rules:
  titleFormat:
    enabled: true
    pattern: "^(feat|fix|docs|style|refactor|test|chore)"
    minLength: 10
    maxLength: 100

  draftPrevention:
    enabled: true
    blockDraft: true
        """.trimIndent(),
        "standard" to """
# Karandash Standard Configuration Template
# Balanced validation with comprehensive MR checks
#
# This template provides a well-rounded set of validation rules for most projects.
# Includes title format, description, and basic size constraints.

version: "1.0"

rules:
  titleFormat:
    enabled: true
    pattern: "^(feat|fix|docs|style|refactor|test|chore|perf|ci)(:|(\\s-))\\s"
    minLength: 10
    maxLength: 100

  descriptionFormat:
    enabled: true
    minLength: 50

  sizeLimits:
    enabled: true
    maxLines: 500

  labels:
    enabled: false

  draftPrevention:
    enabled: true
    blockDraft: true

plugins: []
        """.trimIndent(),
        "strict" to """
# Karandash Strict Configuration Template
# Comprehensive validation with strict requirements
#
# This template enforces a strict set of validation rules suitable for
# production-critical projects with high code quality standards.

version: "1.0"

rules:
  titleFormat:
    enabled: true
    pattern: "^(feat|fix|docs|style|refactor|test|chore|perf|ci|build)(!)?(:|(\\s-))\\s"
    minLength: 15
    maxLength: 80

  descriptionFormat:
    enabled: true
    minLength: 100

  sizeLimits:
    enabled: true
    maxLines: 300

  labels:
    enabled: true
    required:
      - "priority"
      - "type"
    forbidden:
      - "wip"
      - "do-not-merge"

  approval:
    enabled: true
    minApprovals: 2

  draftPrevention:
    enabled: true
    blockDraft: true

  security:
    enabled: true

plugins: []
        """.trimIndent()
    )

    internal val platformTemplates = mapOf(
        "github" to """
# Karandash GitHub Platform Configuration
# Optimized rules and settings for GitHub pull requests

version: "1.0"

platform:
  name: "github"

platforms:
  github:
    token: "${'$'}{GITHUB_TOKEN}"

rules:
  titleFormat:
    enabled: true
    pattern: "^(feat|fix|docs|style|refactor|test|chore|perf|ci)(:|(\\s-))\\s"
    minLength: 10
    maxLength: 100

  descriptionFormat:
    enabled: true
    minLength: 50

  sizeLimits:
    enabled: true
    maxLines: 500

  labels:
    enabled: false

  cicdStatus:
    enabled: true

  draftPrevention:
    enabled: true
    blockDraft: true
        """.trimIndent(),
        "gitlab" to """
# Karandash GitLab Platform Configuration
# Optimized rules and settings for GitLab merge requests

version: "1.0"

platform:
  name: "gitlab"

platforms:
  gitlab:
    token: "${'$'}{GITLAB_TOKEN}"

rules:
  titleFormat:
    enabled: true
    pattern: "^(feat|fix|docs|style|refactor|test|chore|perf|ci)(:|(\\s-))\\s"
    minLength: 10
    maxLength: 100

  descriptionFormat:
    enabled: true
    minLength: 50

  sizeLimits:
    enabled: true
    maxLines: 500

  labels:
    enabled: false

  cicdStatus:
    enabled: true

  approval:
    enabled: false
    minApprovals: 1

  draftPrevention:
    enabled: true
    blockDraft: true
        """.trimIndent(),
        "bitbucket" to """
# Karandash Bitbucket Platform Configuration
# Optimized rules and settings for Bitbucket pull requests

version: "1.0"

platform:
  name: "bitbucket"

platforms:
  bitbucket:
    token: "${'$'}{BITBUCKET_TOKEN}"

rules:
  titleFormat:
    enabled: true
    pattern: "^(feat|fix|docs|style|refactor|test|chore|perf|ci)(:|(\\s-))\\s"
    minLength: 10
    maxLength: 100

  descriptionFormat:
    enabled: true
    minLength: 50

  sizeLimits:
    enabled: true
    maxLines: 500

  labels:
    enabled: false

  cicdStatus:
    enabled: true

  draftPrevention:
    enabled: true
    blockDraft: true
        """.trimIndent(),
        "azure-devops" to """
# Karandash Azure DevOps Platform Configuration
# Optimized rules and settings for Azure DevOps pull requests

version: "1.0"

platform:
  name: "azure-devops"

platforms:
  azuredevops:
    token: "${'$'}{AZURE_DEVOPS_TOKEN}"

rules:
  titleFormat:
    enabled: true
    pattern: "^(feat|fix|docs|style|refactor|test|chore|perf|ci)(:|(\\s-))\\s"
    minLength: 10
    maxLength: 100

  descriptionFormat:
    enabled: true
    minLength: 50

  sizeLimits:
    enabled: true
    maxLines: 500

  labels:
    enabled: false

  cicdStatus:
    enabled: true

  draftPrevention:
    enabled: true
    blockDraft: true
        """.trimIndent()
    )
}
