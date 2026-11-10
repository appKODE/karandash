package io.karandash.config

import io.karandash.cache.CacheConfig
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class Config(
    val rules: RulesConfig = RulesConfig(),
    /** Platform service modules (unified provider + mutator + comment-poster). */
    val platforms: Map<String, JsonElement>? = null,
    val reporters: Map<String, JsonElement>? = null,
    /** Active profile reference, or null when no profile is selected. */
    val profile: ProfileRef? = null,
    /** Configured script entries from the top-level `scripts:` block. */
    val scripts: List<ScriptEntryConfig>? = null,
    /** Path to a Dangerfile.js or Dangerfile.df.kts to run as part of validation. */
    val dangerfile: String? = null,
    /** `baseline:` - fingerprint file whose findings are not reported; the `--baseline` flag wins over it. */
    val baseline: String? = null,
    /**
     * `dry-run:` - feeds the `is_dry_run` condition and suppresses work per [dryRunScope]
     * (see [activeDryRunScope]). Unrelated to the `--dry-run` CLI flag.
     */
    val dryRun: Boolean = false,
    /**
     * `dry-run-scope:` - what [dryRun] suppresses: [DryRunScope.AI] skips AI provider calls;
     * [DryRunScope.ALL] also skips every output with external side effects (console still prints).
     * Null (the default) acts as [DryRunScope.AI].
     */
    val dryRunScope: DryRunScope? = null,
    /**
     * When true, SSRF protection is relaxed: localhost, private IPs, and HTTP URLs
     * are permitted for providers and webhooks. Set via `allow-localhost: true` in
     * config or `--allow-localhost` CLI flag.
     */
    val allowLocalhost: Boolean = false,
    /**
     * `allow-private-network: true` permits private (RFC 1918, IPv6 ULA) and dotless hosts for self-hosted
     * services on an internal network, while metadata/link-local and loopback hosts stay blocked and HTTPS stays
     * mandatory. Applied to the run that loads it, via its `NetworkPolicy`.
     */
    val allowPrivateNetwork: Boolean = false,
    /** Pre-validation enrichment stage configuration. Null means enrichment is disabled. */
    val enrichment: EnrichmentConfig? = null,
    /**
     * Enrichment provider module configs from the top-level `enrichment:` block.
     * Same shape as [platforms]: provider-id → per-provider config [JsonElement].
     * Null means no enrichment providers are explicitly configured.
     */
    val enrichmentProviders: Map<String, JsonElement>? = null,
    /**
     * Top-level AI configuration block. When present, configures AI-powered analysis
     * features (risk scoring, review comments, etc.) as a first-class config section
     * rather than nesting under `rules: ai:`.
     */
    val ai: JsonElement? = null,
    /**
     * AI report module configs from `ai: { reports: { ... } }`.
     * Same shape as [reporters]: module-id → per-module config [JsonElement].
     * Null means no AI reports are explicitly configured (all use [Module.defaultEnabled]).
     */
    val aiReports: Map<String, JsonElement>? = null,
    /**
     * AI metric module configs from `ai: { metrics: { ... } }`.
     * Same shape as [reporters]: module-id → per-module config [JsonElement].
     * Null means no AI metrics are explicitly configured (all use [Module.defaultEnabled]).
     */
    val aiMetrics: Map<String, JsonElement>? = null,
    /** Auto-approve configuration. Null means auto-approve is disabled. */
    val autoApprove: AutoApproveConfig? = null,
    /** Unified cache configuration. Null means cache defaults are used. */
    val cache: CacheConfig? = null,
    /** Safety limits for expressions, extends, glob, regex, and patterns. */
    val limits: LimitsConfig = LimitsConfig(),
    /** HTTP client configuration (retry, pagination, response cache). */
    val http: HttpConfig = HttpConfig(),
    /** User-defined named patterns from the top-level `patterns:` block. */
    val patterns: Map<String, String> = emptyMap(),
    /** Unified timeout configuration. Null means defaults from [TimeoutConfig] are used. */
    val timeouts: TimeoutConfig? = null,
    /** Runtime diagnostics (memory/CPU monitoring). Null means diagnostics are disabled. */
    val diagnostics: DiagnosticConfig? = null,
    /**
     * Global conditional policy overrides. Each policy applies config overrides or
     * enablement changes to affected rules when its `when:` condition matches.
     * Null means no policies are configured.
     */
    val policies: List<PolicyConfig>? = null,
    /**
     * Profile threshold overrides. Non-null fields are merged into the
     * corresponding rule config elements after profile resolution.
     */
    val profileOverrides: ProfileOverridesConfig = ProfileOverridesConfig(),
    /** User-facing display preferences (git SHA length, watcher debounce, console marks). */
    val display: DisplayConfig = DisplayConfig(),
    /** Report preferences (language, transparency, walkthrough). Not encoded when default, so effective-config snapshots stay unchanged. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val report: ReportConfig = ReportConfig(),
    /** Network security settings (extra blocked hosts). Not encoded when default, so effective-config snapshots stay unchanged. */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val security: SecurityConfig = SecurityConfig(),
    /**
     * Config-driven rule-to-enrichment bindings. Each key is a rule ID; the value lists
     * enrichment ID patterns visible to that rule (exact or glob with `*`). Bindings are
     * additive: they broaden the code-declared [io.karandash.api.module.RuleModule.supportedEnrichment]
     * without narrowing it. Unknown rule IDs emit a `warn` log at pipeline startup.
     */
    val ruleEnrichment: Map<String, RuleEnrichmentConfig>? = null,
    /**
     * Module-owned top-level blocks: a top-level key that is not a built-in section but equals a
     * registered module's `configKey` is kept here, keyed by that `configKey`, and delivered to the module as
     * its plugin config. Not encoded when empty, so effective-config snapshots stay unchanged.
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val extensions: Map<String, JsonElement> = emptyMap(),
) {
    /** The scope this run suppresses: [dryRunScope] (unset: [DryRunScope.AI]) when [dryRun] is on, else null. */
    val activeDryRunScope: DryRunScope? get() = if (dryRun) dryRunScope ?: DryRunScope.AI else null

    /**
     * The `platforms.<key>` block for a platform service's [key] (its `configKey`), matched via [ModuleIdNormalizer]
     * so `azure-devops:` (normalized to `azureDevops`) finds the `azureDevOps` service. Null when not configured.
     */
    fun platformConfig(key: String): JsonElement? =
        platforms?.let { blocks -> ModuleIdNormalizer.resolve(key, blocks.keys)?.let(blocks::get) }
}

/**
 * Per-rule enrichment binding from the top-level `rule-enrichment:` config block.
 *
 * Each [enrich] entry is an enrichment ID or glob pattern (`jira:*`, `ci:*`).
 * Patterns are matched against the IDs available in [io.karandash.api.context.EnrichmentContext].
 */
@Serializable
data class RuleEnrichmentConfig(
    val enrich: List<String> = emptyList(),
)

/**
 * Configuration for automatic MR approval when findings are below a severity threshold.
 *
 * ```yaml
 * auto-approve:
 *   enabled: true
 *   max-severity: info
 * ```
 *
 * When enabled, MRs are automatically approved if the worst finding severity
 * does not exceed [maxSeverity]. Default threshold is INFO - only MRs with
 * no errors and no warnings are approved.
 */
@Serializable
data class AutoApproveConfig(
    /** Whether auto-approve is active. */
    val enabled: Boolean = true,
    /** Maximum finding severity to still approve. One of "info" or "warning". */
    val maxSeverity: String = "info",
    /** Merge the MR after a successful auto-approve (platform default merge method). */
    val merge: Boolean = false,
) {
    init {
        // `error` would approve an MR with error findings; an unknown value used to fall back silently.
        require(maxSeverity.lowercase() in ALLOWED_MAX_SEVERITIES) {
            "maxSeverity must be one of ${ALLOWED_MAX_SEVERITIES.joinToString()}, got '$maxSeverity'"
        }
    }

    companion object {
        private val ALLOWED_MAX_SEVERITIES = listOf("info", "warning")
    }
}

/**
 * Reference to a [io.karandash.api.module.ProfileModule] selected in config.
 *
 * The profile provides rule config defaults. User `rules:` entries always override.
 *
 * ```yaml
 * profile: conventional-commits
 *
 * profile:
 *   id: conventional-commits
 *   severity: error
 *   overrides:
 *     rules:
 *       titleFormat:
 *         maxLength: 50
 * ```
 */
@Serializable
data class ProfileRef(
    /** Profile ID matching [io.karandash.api.module.ProfileModule.profileId]. */
    val id: String,
    /**
     * Severity override for all rules contributed by this profile.
     * When null, uses [io.karandash.api.module.ProfileModule.defaultSeverity].
     * In config: `severity: warning` or `severity: error`.
     */
    @SerialName("severity")
    val severityOverride: String? = null,
    /** User-level rule overrides applied on top of the profile baseline. */
    val overrides: ProfileOverrides = ProfileOverrides(),
)

/**
 * User-supplied overrides that layer on top of a profile's baseline configs.
 */
@Serializable
data class ProfileOverrides(
    /** ruleId → partial config [JsonElement] merged on top of profile's ruleConfigs entry. */
    val rules: Map<String, JsonElement> = emptyMap(),
)

@Serializable
data class RulesConfig(
    val ruleConfigs: Map<String, JsonElement> = emptyMap(),
    val ruleMeta: Map<String, RuleExecutionMeta> = emptyMap(),
) {
    fun isEmpty(): Boolean = ruleConfigs.isEmpty()

    /**
     * Returns the [RuleExecutionMeta] for [ruleId], or default meta if not explicitly set.
     *
     * Tolerates naming-convention differences via [ModuleIdNormalizer], so a finding's canonical
     * rule id (`go-title-length`) finds meta stored under its configured key (`goTitleLength`).
     */
    fun metaFor(ruleId: String): RuleExecutionMeta =
        ModuleIdNormalizer.resolve(ruleId, ruleMeta.keys)?.let { ruleMeta[it] } ?: RuleExecutionMeta()
}

/**
 * Validates the config for common errors. Returns a list of validation issues found.
 * Called automatically after config construction in [io.karandash.config.pipeline.ConfigMapParser].
 *
 * @return a list of human-readable error strings; empty if the config is valid.
 */
fun Config.validate(): List<String> {
    val errors = mutableListOf<String>()
    rules.ruleConfigs.forEach { (id, _) ->
        if (id.isBlank()) errors.add("Rule ID cannot be blank")
    }
    platforms?.forEach { (id, _) ->
        if (id.isBlank()) errors.add("Platform ID cannot be blank")
    }
    reporters?.forEach { (id, _) ->
        if (id.isBlank()) errors.add("Reporter format ID cannot be blank")
    }
    aiReports?.forEach { (id, _) ->
        if (id.isBlank()) errors.add("AI report ID cannot be blank")
    }
    aiMetrics?.forEach { (id, _) ->
        if (id.isBlank()) errors.add("AI metric ID cannot be blank")
    }
    enrichmentProviders?.forEach { (id, _) ->
        if (id.isBlank()) errors.add("Enrichment provider ID cannot be blank")
    }
    return errors
}
