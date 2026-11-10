package io.karandash.config.schema

/**
 * Top-level config section names shared by parser, diagnostics, LSP, and schema layers.
 *
 * These are structural section identifiers used for routing config blocks,
 * not parsing keys for individual fields within those blocks.
 */
object ConfigSections {
    const val RULES: String = "rules"
    const val PLATFORMS: String = "platforms"
    const val REPORTERS: String = "reporters"
    const val SCRIPTS: String = "scripts"
    const val ENV: String = "env"
    const val AI: String = "ai"

    const val AUTO_APPROVE: String = "auto-approve"
    const val CACHE: String = "cache"
    const val PATTERNS: String = "patterns"

    const val PROFILE: String = "profile"
    const val DANGERFILE: String = "dangerfile"
    const val BASELINE: String = "baseline"
    const val EXTENDS: String = "extends"
    const val OPTIONS: String = "options"
    const val POLICIES: String = "policies"
    const val AUTH: String = "auth"
    const val TIMEOUTS: String = "timeouts"
    const val HTTP: String = "http"
    const val LIMITS: String = "limits"
    const val DISPLAY: String = "display"
    const val REPORT: String = "report"
    const val SECURITY: String = "security"
    const val DIAGNOSTICS: String = "diagnostics"
    const val ENRICHMENT: String = "enrichment"
    const val RULE_ENRICHMENT: String = "rule-enrichment"

    /** Plugin declarations and registry settings; same schema as `manifest.yaml` (read by `ManifestReader`). */
    const val PLUGINS: String = "plugins"

    /** Sub-section keys within the `ai:` block (not top-level). */
    const val AI_REPORTS: String = "reports"
    const val AI_METRICS: String = "metrics"

    val all: Set<String> = setOf(
        RULES,
        PLATFORMS,
        REPORTERS,
        SCRIPTS,
        ENV,
        AI,
        AUTO_APPROVE,
        CACHE,
        PROFILE,
        DANGERFILE,
        BASELINE,
        EXTENDS,
        OPTIONS,
        PATTERNS,
        POLICIES,
        AUTH,
        TIMEOUTS,
        HTTP,
        LIMITS,
        DISPLAY,
        REPORT,
        SECURITY,
        DIAGNOSTICS,
        ENRICHMENT,
        RULE_ENRICHMENT,
        PLUGINS,
    )
}
