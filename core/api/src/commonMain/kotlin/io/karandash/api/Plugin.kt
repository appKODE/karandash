package io.karandash.api

/**
 * Base interface for all Karandash extensions.
 *
 * A Plugin represents any component that extends Karandash functionality. This umbrella interface
 * supports multiple plugin types with different implementation strategies and runtime characteristics.
 *
 * ## Plugin Types
 *
 * ### Structured Plugins (Modules)
 * [Module] is the primary plugin type for implementing rules, reporters, and providers.
 * Modules have a defined lifecycle ([Module.initialize], [Module.shutdown]), type system
 * ([ModuleType]), and configuration schema ([Module.configSchema]).
 *
 * **Example:**
 * ```kotlin
 * class TitleFormatRuleModule : RuleModule {
 *     override val id = "io.karandash.rule.title-format"
 *     override val name = "Title Format Rule"
 *     override val version = "1.7.0"
 *     override val type = ModuleType.RULE
 *     // ... implementation
 * }
 * ```
 *
 * ### Script-Based Plugins
 * Script plugins execute via embedded interpreters (QuickJS for JavaScript, etc.).
 * They provide a lightweight alternative for simple validation logic without compiling JARs.
 * Script plugins are currently loaded through module bridges; the shape below is illustrative only.
 *
 * **Example:**
 * ```kotlin
 * interface ScriptPlugin : Plugin {
 *     val language: ScriptLanguage  // JS, LUA, WASM
 *     val source: String
 *     fun execute(env: ScriptEnv): PluginResult
 * }
 * ```
 *
 * ### WebAssembly Plugins
 * WASM plugins offer near-native performance for compute-intensive validation logic
 * while maintaining cross-platform compatibility.
 *
 * ## For Plugin Authors
 *
 * **Most plugin authors should implement [Module]** or one of its specialized subtypes:
 * - [RuleModule] for merge request validators
 * - [ReporterModule] for output formatters
 * - [PlatformServiceModule] for platform integrations (fetch, mutate, comment)
 *
 * Only implement Plugin directly if you're creating a new plugin type that doesn't fit
 * the Module pattern (e.g., experimental runtime engines).
 *
 * ## Configuration
 *
 * **In YAML/JSON config:**
 * - Built-in modules use domain-specific blocks: `rules:`, `providers:`, `reporters:`
 * - External plugins use the `plugins:` block
 *
 * **Example:**
 * ```yaml
 * rules:                    # Built-in RuleModules
 *   title-format: {}
 *
 * platforms:                # Built-in PlatformServiceModules
 *   gitlab: {}
 *
 * plugins:                  # External plugins (any Plugin type)
 *   - id: "custom-validator"
 *     version: "1.0.0"
 * ```
 *
 * ## Terminology
 *
 * **Plugin** (this interface) is the umbrella term for any Karandash extension.
 * **Module** is a specialized Plugin with lifecycle and type system - the most common plugin type.
 *
 * Loaders discover only [Module] implementations (JAR plugins register them in
 * `META-INF/services/io.karandash.api.Module`); implement [Module], not bare [Plugin].
 *
 * **Historical note:** Early versions of Karandash used "Module" as the top-level interface.
 * Module now extends Plugin to clarify terminology and enable future plugin types.
 *
 * @see Module The primary plugin type for structured extensions
 * @see RuleModule For implementing validators
 * @see ReporterModule For implementing output formatters
 * @see PlatformServiceModule For implementing platform integrations
 */
@StableKarandashApi
public interface Plugin {
    /**
     * Unique plugin identifier using reverse-domain notation.
     *
     * **Examples:**
     * - `io.karandash.rule.title-format`
     * - `io.karandash.reporter.console`
     * - `com.example.custom-validator`
     *
     * **Requirements:**
     * - Must be globally unique
     * - Reverse-domain notation recommended
     * - Lowercase, alphanumeric, dots, and hyphens only
     * - No whitespace or special characters
     *
     * This identifier is used for:
     * - Plugin registry lookups
     * - Configuration references
     * - Dependency resolution
     * - Conflict detection
     */
    public val id: String

    /**
     * Human-readable plugin name for display in CLI help, logs, and error messages.
     *
     * **Examples:**
     * - `"Title Format Rule"`
     * - `"Console Reporter"`
     * - `"Custom JIRA Validator"`
     *
     * **Guidelines:**
     * - Use title case
     * - Keep under 50 characters
     * - Describe what the plugin does, not implementation details
     */
    public val name: String

    /**
     * Semantic version string (MAJOR.MINOR.PATCH).
     *
     * **Examples:**
     * - `"1.0.0"` - Initial release
     * - `"2.1.3"` - Bug fix on 2.1 minor version
     * - `"3.0.0-beta.1"` - Pre-release version
     *
     * **Semantic versioning rules:**
     * - MAJOR: Breaking changes (incompatible API changes)
     * - MINOR: New features (backward-compatible)
     * - PATCH: Bug fixes (backward-compatible)
     *
     * Pre-release versions can use suffixes: `-alpha.1`, `-beta.2`, `-rc.1`
     *
     * @see <a href="https://semver.org">Semantic Versioning 2.0.0</a>
     */
    public val version: String

    /**
     * Minimum host plugin API level ([KarandashApi.LEVEL]) this plugin requires.
     *
     * Defaults to [KarandashApi.MIN_SUPPORTED_LEVEL], so plugins that do not declare it keep
     * loading. Loaders reject a plugin whose value is greater than the host's [KarandashApi.LEVEL]
     * with a message naming both levels.
     */
    @ExperimentalKarandashApi
    public val minApiLevel: Int get() = KarandashApi.MIN_SUPPORTED_LEVEL
}
