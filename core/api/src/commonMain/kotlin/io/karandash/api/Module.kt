package io.karandash.api

import io.karandash.api.config.ConfigProperty
import io.karandash.api.context.ConditionContext
import io.karandash.api.finding.ModuleMessage
import io.karandash.api.module.ModuleScope
import io.karandash.api.platform.PatternRegistry
import io.karandash.api.platform.PredicateRegistry
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Structured plugin with type system and lifecycle management.
 *
 * A Module is a specialized [Plugin] that provides:
 * - **Type system**: [ModuleType] categorizes modules (rule, reporter, provider, etc.)
 * - **Lifecycle**: [initialize] and [shutdown] callbacks for resource management
 * - **Configuration**: [configSchema] declares accepted config properties, [defaultConfig] provides defaults
 * - **Self-description**: [description], [dslName], [configKey] for tooling and documentation
 *
 * ## Module Types
 *
 * Implement one of the specialized module interfaces:
 * - [RuleModule] - Validates merge requests and returns findings
 * - [ReporterModule] - Formats and outputs validation results
 * - [PlatformServiceModule] - Platform integration (fetch, mutate, comment on MRs)
 * - [EnrichmentProviderModule] - Supplementary data from external services
 *
 * ## Example
 *
 * ```kotlin
 * class TitleFormatRuleModule : RuleModule {
 *     override val id = "io.karandash.rule.title-format"
 *     override val name = "Title Format Rule"
 *     override val version = "1.7.0"
 *     override val type = ModuleType.RULE
 *
 *     override suspend fun contributedRules() = setOf("titleFormat")
 *
 *     override suspend fun validate(ruleId: String, mr: MergeRequest, configElement: JsonElement): List<Finding> {
 *         // validation logic
 *     }
 * }
 * ```
 *
 * ## Configuration
 *
 * Modules are configured in domain-specific blocks in YAML/JSON config:
 * ```yaml
 * rules:       # RuleModules
 *   title-format:
 *     pattern: "^(feat|fix):"
 *
 * platforms:   # PlatformServiceModules
 *   gitlab:
 *     token: "${GITLAB_TOKEN}"
 *
 * reporters:   # ReporterModules
 *   console: true
 * ```
 *
 * ## Terminology
 *
 * **Why "Module" and not "Plugin"?**
 *
 * The name "Module" distinguishes structured plugins (with lifecycle, type system, configuration schema)
 * from future plugin types like script-based plugins or WebAssembly plugins.
 *
 * - **Plugin** (parent interface) = any Karandash extension (umbrella term)
 * - **Module** (this interface) = structured plugin with lifecycle (specific type)
 *
 * **Note on Gradle modules:** This interface is NOT related to Gradle build modules
 * (e.g., `:plugins:modules:rules:title-format`). The name "Module" here refers to runtime
 * plugin components, not build system subprojects.
 *
 * ## ServiceLoader Discovery
 *
 * Modules are discovered via Java ServiceLoader on JVM:
 * ```
 * META-INF/services/io.karandash.api.Module
 * ```
 *
 * On native platforms, modules are discovered via native plugin loader (dlopen/dlsym).
 *
 * @see Plugin The parent interface for all Karandash extensions
 * @see RuleModule For implementing validators
 * @see ReporterModule For implementing output formatters
 * @see PlatformServiceModule For implementing platform integrations
 */
@StableKarandashApi
public interface Module : Plugin {
    /**
     * Module category (rule, reporter, provider, mutator, comment-poster).
     *
     * The type determines how the module is discovered, registered, and invoked.
     * It also affects CLI filtering (`karandash plugin search --type rule`).
     */
    public val type: ModuleType

    /**
     * One-line human-readable description for CLI help, LSP completions, and documentation.
     *
     * Defaults to [name] but can be overridden to provide more context.
     * Used in `karandash plugin list` and IDE autocomplete.
     */
    public val description: String get() = name

    /**
     * DSL block name used in .karandash.kts config (camelCase, e.g. "titleFormat", "azureDevOps").
     * Defaults to [id].
     */
    public val dslName: String get() = id

    /**
     * YAML/JSON config key used in .karandash/config.yaml (kebab-case, e.g. "title-format").
     * Defaults to [dslName]; override when YAML key differs from DSL name.
     */
    public val configKey: String get() = dslName

    public suspend fun initialize(context: PluginContext): Boolean

    public suspend fun shutdown() {}

    /**
     * Registers domain-specific named patterns into [registry] so they are available
     * as `@pattern-name` references in config.
     *
     * Called once during module registration, before any validation. The default
     * implementation is a no-op so existing modules do not need to override it.
     *
     * Domain-specific patterns are registered by their plugin modules, not globally,
     * so each pattern ships with the plugin when published to the registry.
     *
     * @param registry The pattern registry to register definitions into.
     */
    public fun registerPatterns(registry: PatternRegistry) {}

    /**
     * Registers condition predicates this module contributes.
     *
     * Called once during pipeline setup after [initialize].
     * Same pattern as [registerPatterns].
     *
     * @param registry The predicate registry to register predicates into.
     */
    public fun registerPredicates(registry: PredicateRegistry<ConditionContext>) {}

    /**
     * Whether this module is active when no explicit `enabled:` config key is present.
     * Override to `false` for opt-in modules (e.g. AI rule, context-tag).
     * Default: `true` (active unless explicitly disabled).
     */
    public val defaultEnabled: Boolean get() = true

    /**
     * Priority for ordering when multiple modules of the same type are available.
     * Lower values = higher priority. Default is 100.
     */
    public val defaultPriority: Int get() = 100

    /**
     * Returns the module's default configuration map.
     * Called AFTER [initialize] succeeds.
     */
    public suspend fun defaultConfig(): JsonElement = JsonObject(emptyMap())

    /**
     * Structured diagnostic messages this module can emit (e.g. auth errors, missing deps).
     */
    public suspend fun messages(): List<ModuleMessage> = emptyList()

    /**
     * Declares the configuration properties this module accepts.
     * Used by LSP completions, diagnostics, and documentation generation.
     */
    public suspend fun configSchema(): List<ConfigProperty> = emptyList()

    /**
     * Markdown documentation for LSP hover tooltips.
     *
     * When non-null, the [HoverProvider][io.karandash.lsp.HoverProvider] uses this to populate
     * hover documentation dynamically instead of relying on a hardcoded docs map.
     * Content is returned as-is (trimmed Markdown).
     */
    public val hoverDocumentation: String? get() = null

    /**
     * Config key names (in camelCase) whose map children should NOT have their keys
     * normalized to camelCase by the config pipeline.
     *
     * Use this for fields that hold user-defined identifiers as keys - such as HTTP
     * header maps, named pattern maps, or auth entry maps - where the keys are
     * user-controlled and must be preserved verbatim.
     *
     * Corresponds to fields annotated with [@PreserveKeys][PreserveKeys] on the module's
     * config data class.
     *
     * Default: normalize everything (empty set).
     */
    public fun preserveKeys(): Set<String> = emptySet()

    /**
     * Lifetime of this module relative to a validation run. Default: [ModuleScope.SHARED]
     * (one instance for every run, today's behaviour).
     */
    @ExperimentalKarandashApi
    public val scope: ModuleScope get() = ModuleScope.SHARED

    /**
     * Returns a handle to use for one run, configured from [context].
     *
     * Contract: for [ModuleScope.PER_RUN] modules the returned instance is configured but NOT
     * yet initialized and is of the same class as `this`. The host initializes it, tracks it,
     * and shuts it down when the run ends. Default: `this` (shared instance).
     */
    @ExperimentalKarandashApi
    public suspend fun newHandle(context: PluginContext): Module = this

    public companion object {
        /**
         * Normalize a module identifier for case-insensitive, separator-insensitive comparison.
         * Strips all non-alphanumeric characters and converts to lowercase.
         *
         * This is used for alias resolution and platform detection to allow flexible input
         * formats (e.g., "azure-devops", "AzureDevOps", "azure_devops" all normalize to "azuredevops").
         *
         * @param value The raw identifier string to normalize
         * @return The normalized identifier (lowercase, alphanumeric only)
         *
         * Example:
         * ```kotlin
         * Module.normalizeId("azure-devops")   // "azuredevops"
         * Module.normalizeId("AzureDevOps")    // "azuredevops"
         * Module.normalizeId("GitHub")         // "github"
         * ```
         *
         * Kept as public API for existing plugins; it delegates to [ModuleIdNormalizer.normalize],
         * the single id normalizer, so every lookup agrees on the matching form.
         */
        public fun normalizeId(value: String): String = ModuleIdNormalizer.normalize(value)
    }
}
