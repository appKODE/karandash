package io.karandash.sdk.enrichment

/**
 * Canonical enrichment ID constants shared across all providers and consumers.
 *
 * Each constant is formatted `<providerId>:<dataType>` and serves as the key
 * produced by an [io.karandash.api.module.EnrichmentProviderModule] and consumed
 * by rule modules and [io.karandash.api.ai.AiContextEnricherModule] implementations.
 *
 * IDs are grouped by provider prefix. Add new IDs here rather than as inline
 * string literals in individual modules.
 */
public object EnrichmentKeys {
    // ── local-git ─────────────────────────────────────────────────────────────

    /** Commit-frequency counts per file path, produced by the local-git enrichment provider. */
    public const val LOCAL_GIT_FILE_FREQUENCY: String = "local-git:file-frequency"

    /** Per-file contributor statistics, produced by the local-git enrichment provider. */
    public const val LOCAL_GIT_CONTRIBUTOR_STATS: String = "local-git:contributor-stats"

    // ── jira ──────────────────────────────────────────────────────────────────

    /** Raw ticket keys regex-extracted from MR metadata (no API call required). */
    public const val JIRA_TICKET_KEYS: String = "jira:ticket-keys"

    /** Full ticket details fetched from the Jira REST API. */
    public const val JIRA_TICKETS: String = "jira:tickets"

    /** Parsed acceptance-criteria bullet lists per ticket key. */
    public const val JIRA_ACCEPTANCE_CRITERIA: String = "jira:acceptance-criteria"

    /** Linked issues and remote URLs per ticket key. */
    public const val JIRA_LINKED_ISSUES: String = "jira:linked-issues"

    // ── confluence ────────────────────────────────────────────────────────────

    /** Design-document page content fetched from Confluence, keyed by page ID. */
    public const val CONFLUENCE_PAGES: String = "confluence:pages"

    // ── github ────────────────────────────────────────────────────────────────

    /** GitHub issue details fetched via the GitHub REST API, keyed by issue number. */
    public const val GITHUB_ISSUES: String = "github:issues"

    // ── gitlab ────────────────────────────────────────────────────────────────

    /** GitLab issue details fetched via the GitLab REST API, keyed by issue iid. */
    public const val GITLAB_ISSUES: String = "gitlab:issues"

    // ── ci ────────────────────────────────────────────────────────────────────

    /** Summary list of failed CI jobs from the MR's most recent pipeline run. */
    public const val CI_FAILED_JOBS: String = "ci:failed-jobs"

    /** Per-file uncovered line ranges parsed from LCOV/JaCoCo coverage artifacts. */
    public const val CI_COVERAGE_GAPS: String = "ci:coverage-gaps"

    // ── symbols ───────────────────────────────────────────────────────────────

    /** Symbol signatures (class/function/method) extracted from changed files via ast-grep. */
    public const val SYMBOLS_CHANGED: String = "symbols:changed"
}
