package io.karandash.testfixtures

import io.karandash.config.pipeline.RemoteConfigFetcher
import io.karandash.http.ProviderHttpClientFactory
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel

/**
 * Shared test support for remote `extends:` integration tests.
 *
 * Provides a mock HTTP-backed [RemoteConfigFetcher] that serves configs by URL,
 * optionally verifying auth headers on each request.
 */
object RemoteExtendsTestSupport {
    /** Simulated GitLab API URL for the base config. */
    const val MOCK_BASE_URL =
        "https://gitlab.example.com/api/v4/projects/1234/repository/files/" +
            "base%2F.karandash%2Fconfig.yaml/raw?ref=main"

    /** Simulated GitLab API URL for the base-kmp config. */
    const val MOCK_BASE_KMP_URL =
        "https://gitlab.example.com/api/v4/projects/1234/repository/files/" +
            "base-kmp%2F.karandash%2Fconfig.yaml/raw?ref=main"

    /** Default test auth token value. */
    const val TEST_AUTH_TOKEN = "test-private-token"

    /**
     * Creates a [RemoteConfigFetcher] backed by [MockEngine].
     *
     * @param responses URL-to-YAML-content map. URLs not in the map get 404.
     * @param expectedAuthHeader Optional (headerName, headerValue) pair. When set,
     *   every request is asserted to carry this header; missing header fails the test.
     */
    fun mockConfigFetcher(
        responses: Map<String, String>,
        expectedAuthHeader: Pair<String, String>? = null,
    ): RemoteConfigFetcher {
        val engine = MockEngine { request ->
            // Verify auth header if expected
            if (expectedAuthHeader != null) {
                val (name, value) = expectedAuthHeader
                val actual = request.headers[name]
                if (actual != value) {
                    error(
                        "Expected auth header '$name: $value' but got " +
                            "'$name: $actual' for ${request.url}"
                    )
                }
            }

            val url = request.url.toString()
            val body = responses[url]
            if (body != null) {
                respond(
                    content = ByteReadChannel(body),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/yaml"),
                )
            } else {
                respond(
                    content = ByteReadChannel("Not Found: $url"),
                    status = HttpStatusCode.NotFound,
                )
            }
        }
        val client = ProviderHttpClientFactory.create(engine = engine, allowLocalhost = true)
        return RemoteConfigFetcher(client)
    }

    /**
     * The base config YAML with test-safe values (no real secrets, cache disabled).
     * Mirrors `projects/base/.karandash/config.yaml` with env vars replaced.
     */
    val BASE_CONFIG_YAML = """
env:
  KARANDASH_GITLAB_TOKEN: "test-token"
  BIFROST_API_KEY: "test-bifrost-key"
  BIFROST_BASE_URL: "https://bifrost.example.com"
  KARANDASH_BASE_TOKEN: "test-base-token"
  KARANDASH_JIRA_KEY: "PROJ"
  KARANDASH_CONFIG: ".karandash"
  KARANDASH_AI_MAX_TOKENS: "20000"

patterns:
  project-ticket: "@jira-ticket(@project:${'$'}{KARANDASH_JIRA_KEY})"
  project-tickets: "@one-or-more(@bracketed(@project-ticket))"
  project-title: "@optional(@project-tickets ).+"
  changelog-entry: "@bracketed(@project-ticket) .+"
  changelog-line: "^CHANGELOG: @separated-list(@changelog-entry, ', ')\\.$"

platforms:
  gitlab:
    url: "https://gitlab.example.com"
    token: "${'$'}{KARANDASH_GITLAB_TOKEN}"

reporters:
  markdown: true
  console: false

rules:
  title-format:
    pattern: "@project-title"
    min-length: 10
    max-length: 120
    bail-on-fail: true
    suggestion: "Format: '${'$'}{KARANDASH_JIRA_KEY}-123 Title' or just 'Title'."
    explanation: "Consistent MR titles."
  commit-messages:
    pattern: "@project-title"
    min-length: 10
    max-length: 72
    bail-on-fail: true
    suggestion: "Format: '${'$'}{KARANDASH_JIRA_KEY}-123 Description'. Max 72 chars."
    explanation: "Consistent commit messages."
  jira-ticket:
    when: 'title_matches("@any-ticket")'
    projectKeys: ["${'$'}{KARANDASH_JIRA_KEY}"]
    suggestion: "Use ${'$'}{KARANDASH_JIRA_KEY} JIRA key."
    explanation: "JIRA references."
  cross-entity-consistency:
    ticket-format: jira
    ticket-projects: ["${'$'}{KARANDASH_JIRA_KEY}"]
    commitTasksSubsetOfMr: true
    changelogConsistency: true
    changelogPrefix: "CHANGELOG:"
    mrTaskSource: "title"
    changelogDuplicateRequiresMatchingText: true
    suggestion: "All task IDs in commits must also appear in the MR title."
    explanation: "Orphaned task references."
  draft-prevention:
    wip-markers: ["WIP", "DRAFT", "DO NOT MERGE"]
    suggestion: "Remove WIP/Draft markers."
    explanation: "Merging incomplete work."
  labels:
    requiredLabels: []
    suggestion: "No labels required."
    explanation: "Labels."
  size-limits:
    maxFiles: 75
    maxLines: 2000
    maxCommits: 30
    suggestion: "Split large MRs."
    explanation: "Large MRs."
  language-policy:
    allowedScripts: ["latin"]
    checkMrTitle: true
    checkCommitTitle: true
    checkBranchName: true
    checkMrDescription: false
    checkCommitDescription: false
    suggestion: "English only."
    explanation: "English text."

policies:
  - when: 'title_matches("@any-ticket")'
    override:
      commit-body:
        pattern: "@changelog-line"
        required-when-subject-matches: "@any-ticket"
        matchMode: "fullMatch"
        suggestion: "CHANGELOG line required."
        explanation: "CHANGELOG."

  - when: 'is_draft'
    modules:
      - risk-scoring
      - review-comments
      - code-suggestions
      - review-thread-analysis
      - mr-summary
    enabled: false

cache:
  enabled: false

ai:
  provider: "bifrost"
  model: "openrouter/moonshotai/kimi-k2.5"
  api-key: "test-bifrost-key"
  base-url: "https://bifrost.example.com"
  max-token-budget: 20000
  fail-on-error: false
  timeout: "5m"
  execution-timeout: "6m"
  max-retries: 1
  priority: 200
  mode: supplement
  response-language: "English"
  strictness: "standard"

  models:
    review-thread-analysis: "openrouter/qwen/qwen3.5-35b-a3b"
    mr-summary: "openrouter/qwen/qwen3.5-35b-a3b"
    risk-scoring: "openrouter/qwen/qwen3.5-35b-a3b"

  risk-scoring:
    enabled: true
    model: "openrouter/qwen/qwen3.5-35b-a3b"
  review-comments:
    enabled: true
  code-suggestions:
    enabled: true
    mode: "inline"
    min-severity: "warning"
  review-thread-analysis:
    enabled: true
  mr-summary:
    enabled: true

  severity-overrides:
    - rule: ai-review-comments
      reclassify:
        error: warning

  cost-budget:
    max-tokens-per-run: 20000
    on-budget-exceeded: "skip-remaining"
  grounding:
    check-symbol-existence: true
    check-code-snippets: true
    auto-downgrade-refuted: true
  filter:
    max-per-rule: 10
    max-per-file: 5
    min-confidence: 0.3
    deduplicate: true
  size-policy:
    skip:
      maxFiles: 500
      maxTotalLines: 50000
      maxCommits: 200
    exclude:
      patterns:
        - "**/generated/**"
        - "**/*.generated.*"
        - "**/build/**"
        - "**/.gradle/**"
        - "**/Pods/**"
        - "**/package-lock.json"
        - "**/*.md"
    partial-review:
      maxFiles: 40
      strategy: "most-changed"
    diff-preprocessor:
      skip-deleted-files: true
      skip-pure-renames: true
      strip-diff-metadata: true
      skip-binary-diffs: true

  scopes:
    android:
      paths: ["**/*.kt", "**/*.java", "**/*.xml", "**/*.gradle.kts"]
    ci:
      paths: ["ci/**", ".gitlab-ci.yml", ".github/**", "Makefile"]
    """.trimIndent()

    /**
     * The base-kmp config YAML with test-safe values.
     * Mirrors `projects/base-kmp/.karandash/config.yaml`.
     * Extends MOCK_BASE_URL (remote).
     */
    val BASE_KMP_CONFIG_YAML = """
extends:
  url: "$MOCK_BASE_URL"
  auth:
    type: custom-header
    name: "PRIVATE-TOKEN"
    value: "$TEST_AUTH_TOKEN"

patterns:
  project-tag: "@bracketed((?:android|ios))"
  project-title: "@optional(@project-tickets)@project-tag .+"

rules:
  title-format:
    pattern: "@project-title"
    suggestion: "Format: '[JIRA-123][android] Title' or '[ios] Title'."
    explanation: "Platform tag routes MR."
  commit-messages:
    pattern: "@project-title"
    suggestion: "Format: '[JIRA-123][android] Description' or '[ios] Description'. Max 72 chars."
    explanation: "Platform-tagged commits."
  context-tag:
    allowed: ["android", "ios"]
    delimiter: brackets
    exactlyOne: true
    caseSensitive: true
    checkMrTitle: true
    checkCommitTitles: true
    suggestion: "Exactly one tag: [android] or [ios]."
    explanation: "Context tags."

policies:
  - when: 'title_matches("@any-ticket") && has_tag(android)'
    override:
      commit-body:
        pattern: "@changelog-line"
        required-when-subject-matches: "@any-ticket"
        matchMode: "fullMatch"
        suggestion: "CHANGELOG line required for Android with JIRA ticket."
        explanation: "CHANGELOG for Android."
  - when: 'is_draft'
    modules:
      - risk-scoring
      - review-comments
      - code-suggestions
      - review-thread-analysis
      - mr-summary
    enabled: false

ai:
  scopes:
    android:
      paths: ["android/**/*.kt", "android/**/*.java"]
    ios:
      paths: ["ios/**/*.swift", "ios/**/*.m", "ios/**/*.mm", "ios/**/*.h"]
    shared:
      paths: ["shared/**/*.kt"]
    """.trimIndent()

    /**
     * Expected rule IDs for base (Android-only) projects, as `rules:` keys read after key normalization (camelCase).
     * every project's remote-extends test checks against this one list.
     */
    val BASE_RULE_IDS = setOf(
        "titleFormat",
        "commitMessages",
        "jiraTicket",
        "crossEntityConsistency",
        "draftPrevention",
        "labels",
        "sizeLimits",
        "languagePolicy",
    )

    /** Expected rule IDs for KMP projects (base + contextTag). */
    val KMP_RULE_IDS = BASE_RULE_IDS + "contextTag"

    /**
     * Builds a project config YAML that extends a remote base URL.
     *
     * @param extendsUrl The remote URL to extend from.
     * @param jiraKey The JIRA project key for this project.
     * @param extraYaml Additional YAML to append (e.g., extra rule overrides).
     */
    fun projectConfig(
        extendsUrl: String,
        jiraKey: String,
        extraYaml: String = "",
    ): String =
        buildString {
            appendLine("extends:")
            appendLine("  file-resolution: fallback-remote")
            appendLine("  url: \"$extendsUrl\"")
            appendLine("  auth:")
            appendLine("    type: custom-header")
            appendLine("    name: \"PRIVATE-TOKEN\"")
            appendLine("    value: \"$TEST_AUTH_TOKEN\"")
            appendLine()
            appendLine("env:")
            appendLine("  KARANDASH_JIRA_KEY: \"$jiraKey\"")
            if (extraYaml.isNotBlank()) {
                appendLine()
                appendLine(extraYaml)
            }
        }
}
