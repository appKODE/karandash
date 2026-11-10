package io.karandash.api.module

import io.karandash.api.Module
import io.karandash.api.ModuleType
import io.karandash.models.finding.Severity
import kotlinx.serialization.json.JsonElement

/**
 * A module that provides a predefined set of rule configurations encoding an industry standard
 * or team workflow convention.
 *
 * ## What is a Profile?
 *
 * A Profile bundles sensible default rule configurations for a known development workflow
 * (e.g., Conventional Commits, GitHub Flow, trunk-based development). It acts as a
 * **default provider** - the user's explicit `rules:` config always takes precedence over
 * whatever the profile declares.
 *
 * ## Usage in config
 *
 * ```yaml
 * # Simple - use a profile with its defaults
 * profile: conventional-commits
 *
 * # Extended - override severity or individual rule params
 * profile:
 *   id: conventional-commits
 *   severity: error
 *   overrides:
 *     rules:
 *       titleFormat:
 *         maxLength: 50
 * ```
 *
 * ## Merge priority (highest → lowest)
 *
 * 1. User's explicit `rules:` block
 * 2. `profile.overrides.rules` block in config
 * 3. Profile's [ruleConfigs] baseline
 * 4. Each rule module's own [Module.defaultConfig]
 *
 * ## Implementing a profile
 *
 * ```kotlin
 * class ConventionalCommitsProfileModule : ProfileModule {
 *     override val id = "io.karandash.profile.conventional-commits"
 *     override val profileId = "conventional-commits"
 *     override val name = "Conventional Commits Profile"
 *     override val version = "1.7.0"
 *     override val summary = "Enforces the Conventional Commits specification."
 *
 *     override fun initialize(context: PluginContext): Boolean = true
 *
 *     override suspend fun ruleConfigs() = mapOf(
 *         "titleFormat" to mapOf("pattern" to "^(feat|fix|chore):.*")
 *     )
 * }
 * ```
 *
 * @see ModuleType.PROFILE
 */
public interface ProfileModule : Module {
    override val type: ModuleType get() = ModuleType.PROFILE

    /**
     * Stable kebab-case identifier used in config files and CLI commands.
     *
     * Examples: `"conventional-commits"`, `"github-flow"`, `"trunk-based"`.
     * Must be unique across all registered profiles.
     */
    public val profileId: String

    /**
     * One-line description shown in `karandash profile list`.
     */
    public val summary: String

    /**
     * Tags for filtering with `karandash profile list --tag <tag>`.
     *
     * Common tags: `"title"`, `"commits"`, `"branching"`, `"size"`, `"security"`.
     */
    public suspend fun tags(): Set<String> = emptySet()

    /**
     * Whether this profile is the default for zero-config mode.
     *
     * When no config file is present, the profile with this flag set to `true`
     * is used to provide baseline rule configurations. Exactly one profile in the
     * registry should return `true` (typically the minimal profile).
     */
    public val isDefaultZeroConfigProfile: Boolean get() = false

    /**
     * Baseline severity applied to all rules this profile contributes.
     *
     * [Severity.WARNING] (default) = advisory, never blocks merges.
     * [Severity.ERROR] = enforced, blocks merges on failure.
     *
     * Users can override this per-profile via `profile.severity` in config.
     */
    public val defaultSeverity: Severity get() = Severity.WARNING

    /**
     * Returns the rule configurations this profile contributes.
     *
     * Keys are camelCase rule IDs matching [RuleModule.contributedRules] entries
     * (e.g. `"titleFormat"`, `"sizeLimits"`).
     * Values are [JsonElement] objects passed to each rule module.
     *
     * These configs are applied as **defaults only** - any matching entry in the
     * user's `rules:` block replaces the entire profile config for that rule.
     */
    public suspend fun ruleConfigs(): Map<String, JsonElement>

    /**
     * Optional reporter configurations this profile suggests.
     *
     * Applied only when the user has no `reporters:` block in their config.
     * Empty by default (no reporter preferences).
     */
    public suspend fun reporterConfigs(): Map<String, JsonElement> = emptyMap()

    /**
     * Conditional policies this profile contributes, each one entry of the `policies:` block in its camelCase
     * JSON form (`when`, `name`, `modules`, `severity`, `override`, ...).
     *
     * They run before the user's own `policies:`, so a user policy matching the same MR deep-merges over them.
     * Empty by default. Not bridged: only in-process (built-in or JVM) profiles contribute policies.
     */
    public suspend fun policies(): List<JsonElement> = emptyList()
}
