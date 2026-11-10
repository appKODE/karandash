package io.karandash.config.pipeline

import io.karandash.api.config.parseConfigDuration
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.exception.ConfigParseException
import io.karandash.cache.CacheConfig
import io.karandash.cache.DownloadsCacheConfig
import io.karandash.config.AutoApproveConfig
import io.karandash.config.Config
import io.karandash.config.DiagnosticConfig
import io.karandash.config.DisplayConfig
import io.karandash.config.DryRunScope
import io.karandash.config.EnrichmentConfig
import io.karandash.config.HttpConfig
import io.karandash.config.KarandashLimits
import io.karandash.config.LimitsConfig
import io.karandash.config.ProfileOverrides
import io.karandash.config.ProfileOverridesConfig
import io.karandash.config.ProfileRef
import io.karandash.config.ReportConfig
import io.karandash.config.RuleEnrichmentConfig
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.config.ScriptEntryConfig
import io.karandash.config.ScriptMode
import io.karandash.config.SecurityConfig
import io.karandash.config.TimeoutConfig
import io.karandash.config.keys.ModuleIds
import io.karandash.config.schema.ConfigSections
import io.karandash.config.validate
import io.karandash.duration.KarandashDuration
import io.karandash.interpolation.EnvInterpolation
import io.karandash.interpolation.KarandashEnvSecurity
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.deepNormalizeKeys
import io.karandash.normalize.normalizedKeyCollisionMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Platform-independent parser that converts a [JsonElement] into a [Config] object.
 *
 * Operates natively on [JsonObject] throughout the parsing pipeline, eliminating
 * intermediate map conversions.
 */
object ConfigMapParser {
    const val MAX_EXTENDS_ENTRIES = KarandashLimits.MAX_EXTENDS_ENTRIES

    /**
     * Keys whose immediate children are user-defined identifiers and must not be camelCased (AI scope names like
     * "core-cpp" are schema keys and ARE normalized). `headers` holds HTTP header names (webhook `X-Source`),
     * `budgets` enrichment and module ids, `minConfidenceByModel` model globs: all sent as written.
     */
    internal val CONFIG_PRESERVE_KEYS: Set<String> = setOf(
        "auth",
        "headers",
        "env",
        "patterns",
        "context",
        "ruleEnrichment",
        "budgets",
        "minConfidenceByModel",
    )

    /** The registered modules' [io.karandash.api.Module.preserveKeys]: map keys kept as written on top of [CONFIG_PRESERVE_KEYS]. */
    fun modulePreserveKeys(registry: io.karandash.api.ModuleRegistry): Set<String> =
        registry.allModules().flatMapTo(mutableSetOf()) { it.preserveKeys() }

    internal fun preserveKeysFor(registry: io.karandash.api.ModuleRegistry): Set<String> =
        CONFIG_PRESERVE_KEYS + modulePreserveKeys(registry)

    // Pre-computed camelCase versions of kebab-case config keys.
    private val CAMEL_DRY_RUN = ConfigKeyNormalizer.kebabToCamelCase("dry-run")
    private val CAMEL_DRY_RUN_SCOPE = ConfigKeyNormalizer.kebabToCamelCase("dry-run-scope")
    private val CAMEL_ALLOW_LOCALHOST = ConfigKeyNormalizer.kebabToCamelCase("allow-localhost")
    private val CAMEL_ALLOW_PRIVATE_NETWORK = ConfigKeyNormalizer.kebabToCamelCase("allow-private-network")
    private val CAMEL_AUTO_APPROVE = ConfigKeyNormalizer.kebabToCamelCase(ConfigSections.AUTO_APPROVE)
    private val CAMEL_CACHE = ConfigKeyNormalizer.kebabToCamelCase(ConfigSections.CACHE)
    private const val DOWNLOADS_KEY = "downloads"
    private val CAMEL_TIMEOUTS = ConfigKeyNormalizer.kebabToCamelCase(ConfigSections.TIMEOUTS)
    private val CAMEL_DIAGNOSTICS = ConfigKeyNormalizer.kebabToCamelCase(ConfigSections.DIAGNOSTICS)
    private val CAMEL_RULE_ENRICHMENT = ConfigKeyNormalizer.kebabToCamelCase(ConfigSections.RULE_ENRICHMENT)
    private val CAMEL_PROFILE_OVERRIDES = ConfigKeyNormalizer.kebabToCamelCase("profile-overrides")

    // ── JsonElement typed accessors ──────────────────────────────────────

    private fun JsonElement.stringValue(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement.boolValue(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull

    /**
     * Recursively interpolates `${ENV_VAR}` placeholders in all string values at all depths.
     * Keys are NOT modified - use after [deepNormalizeKeys] to keep the two concerns separate.
     */
    internal fun deepInterpolateValues(
        element: JsonElement,
        defaults: Map<String, String>,
        missingVars: MutableSet<String> = mutableSetOf(),
        deniedVars: MutableSet<String> = mutableSetOf(),
        onResolve: ((varName: String, source: String, resolvedValue: String?) -> Unit)? = null,
    ): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(
                element.entries.associate { (key, value) ->
                    key to deepInterpolateValues(value, defaults, missingVars, deniedVars, onResolve)
                },
            )

            is JsonArray -> JsonArray(
                element.map { deepInterpolateValues(it, defaults, missingVars, deniedVars, onResolve) },
            )

            is JsonPrimitive -> if (element.isString) {
                JsonPrimitive(
                    interpolate(
                        element.content,
                        defaults = defaults,
                        missingVars = missingVars,
                        deniedVars = deniedVars,
                        onResolve = onResolve,
                    ),
                )
            } else {
                element
            }

            else -> element
        }

    // ── Public entry points: JsonElement ─────────────────────────────────

    /**
     * Parse minimal bootstrap config (the first parsing pass) from a [JsonElement] tree.
     *
     * @param element [JsonElement] representation of the parsed config content.
     * @param maxExtendsEntries The `limits.extends.maxEntries` this config inherits (clamped).
     * @return BootstrapConfig with minimal information.
     */
    fun parseBootstrapConfig(
        element: JsonElement,
        maxExtendsEntries: Int = MAX_EXTENDS_ENTRIES,
    ): BootstrapConfig {
        val data = element as? JsonObject
            ?: return BootstrapConfig(extends = emptyList())
        val env = parseEnvBlockObj(data)
        validateEnvDeclaration(env)
        return BootstrapConfig(extends = ExtendsParser.parse(data, env.defaults, maxExtendsEntries))
    }

    /**
     * Parse full config (the second parsing pass) from a [JsonElement] tree.
     *
     * Works natively with [JsonObject] - no intermediate Map conversion.
     *
     * @param element [JsonElement] representation of the parsed config content.
     * @param defaults Default values for env vars declared in `env:` block.
     * @param aiSubRuleKeys Map of AI feature keys to sub-rule IDs.
     * @param extensionKeys Registered modules' `configKey`s; matching unknown top-level blocks go to [Config.extensions].
     * @param logger Receives a warning when two keys of one object normalize to the same name.
     * @param extraPreserveKeys Module-reported [io.karandash.api.Module.preserveKeys], kept as written like [CONFIG_PRESERVE_KEYS].
     * @return [ConfigParseResult] with parsed config and any missing env var names.
     */
    fun parseFullConfig(
        element: JsonElement,
        defaults: Map<String, String> = emptyMap(),
        aiSubRuleKeys: Map<String, String> = emptyMap(),
        extensionKeys: Set<String> = emptySet(),
        logger: Logger = NoOpLogger,
        extraPreserveKeys: Set<String> = emptySet(),
    ): ConfigParseResult {
        val data = element as? JsonObject ?: return ConfigParseResult(Config())
        return parseFullConfigObj(data, defaults, aiSubRuleKeys, extensionKeys, logger, extraPreserveKeys)
    }

    /**
     * Parse the top-level `env:` block from a [JsonElement] tree.
     *
     * @param element [JsonElement] representation of the parsed config content.
     * @return [EnvDeclaration] with declared var names and optional defaults.
     */
    fun parseEnvBlock(element: JsonElement): EnvDeclaration {
        val data = element as? JsonObject ?: return EnvDeclaration.EMPTY
        return parseEnvBlockObj(data)
    }

    /**
     * Pre-parse of the `limits:` block of one config layer (or of chain-fetch keys merged so far), for the stages
     * that run before the full config exists (extends resolution, remote fetch). Keys are normalized; values are not
     * interpolated here ([ChainFetchKeys] interpolates them first). Throws [kotlinx.serialization.SerializationException] or [IllegalArgumentException]
     * on a malformed block, like the full parse.
     */
    fun parseRootLimits(root: JsonElement?): LimitsConfig {
        val data = root as? JsonObject ?: return LimitsConfig()
        val limitsKey = data.keys.firstOrNull { ConfigKeyNormalizer.kebabToCamelCase(it) == ConfigSections.LIMITS }
        val limits = limitsKey?.let { data[it] } as? JsonObject ?: return LimitsConfig()
        return parseLimitsObj(limits)
    }

    /**
     * `cache.downloads` of one config layer (or of chain-fetch keys merged so far): it governs fetching the
     * `extends:` chain, so like [parseRootLimits] it is read before the full config exists.
     */
    fun parseRootDownloadsCache(root: JsonElement?): DownloadsCacheConfig {
        val data = root as? JsonObject ?: return DownloadsCacheConfig()
        val cacheKey = data.keys.firstOrNull { ConfigKeyNormalizer.kebabToCamelCase(it) == CAMEL_CACHE }
        val cache = cacheKey?.let { data[it] } as? JsonObject ?: return DownloadsCacheConfig()
        val normalized = deepNormalizeKeys(cache) as JsonObject
        val downloads = normalized[DOWNLOADS_KEY] as? JsonObject ?: return DownloadsCacheConfig()
        return decodeConfigSection("cache.downloads", DownloadsCacheConfig.serializer(), downloads)
    }

    /** URL `extends:` sources of a top-level config object, in declaration order. */
    internal fun getExtendsSources(data: JsonObject): List<ConfigSource> =
        getExtendsEntriesObj(data).mapNotNull { (it as? ExtendsEntry.UrlEntry)?.source }

    // ── Private: Core JsonObject-native parsing ─────────────────────────

    /**
     * `options:` is a wrapper for top-level keys in the KTS DSL; YAML and JSON now read it the same way.
     * A key set at the top level wins over the same key inside `options:`.
     */
    private fun flattenOptions(norm: JsonObject): JsonObject {
        val options = norm[ConfigSections.OPTIONS] as? JsonObject ?: return norm
        return JsonObject(options + (norm - ConfigSections.OPTIONS))
    }

    private fun parseFullConfigObj(
        data: JsonObject,
        defaults: Map<String, String>,
        aiSubRuleKeys: Map<String, String>,
        extensionKeys: Set<String> = emptySet(),
        logger: Logger = NoOpLogger,
        extraPreserveKeys: Set<String> = emptySet(),
    ): ConfigParseResult {
        val missingVars = mutableSetOf<String>()
        val deniedVars = mutableSetOf<String>()

        // Normalize all keys to camelCase recursively (no interpolation).
        val normalized = flattenOptions(
            deepNormalizeKeys(data, CONFIG_PRESERVE_KEYS + extraPreserveKeys) { key, originals ->
                logger.warn(normalizedKeyCollisionMessage(key, originals))
            } as JsonObject,
        )
        // Interpolate ${ENV_VAR} values at all depths (no key changes).
        val norm = deepInterpolateValues(normalized, defaults, missingVars, deniedVars) as JsonObject
        ConfigSchemaVersion.check(norm)

        val parsedRules = parseRulesObj(norm[ConfigSections.RULES] as? JsonObject, logger)
        val reportBlock = (norm[ConfigSections.REPORT] as? JsonObject)
            ?.let { decodeConfigSection(ConfigSections.REPORT, ReportConfig.serializer(), it) } ?: ReportConfig()
        val report = ReportLanguage.resolve(norm, reportBlock)
        val topLevelAi = ReportLanguage.applyToAi(
            norm,
            parseAiObj(norm[ConfigSections.AI] as? JsonObject),
            report
        )

        val effectiveRules = if (topLevelAi is JsonObject) {
            synthesizeAiRuleConfigsObj(topLevelAi, parsedRules, aiSubRuleKeys)
        } else {
            parsedRules
        }

        val config = Config(
            rules = effectiveRules,
            platforms = parseModuleSectionObj(norm[ConfigSections.PLATFORMS] as? JsonObject),
            reporters = parseModuleSectionObj(norm[ConfigSections.REPORTERS] as? JsonObject),
            profile = parseProfileObj(norm[ConfigSections.PROFILE]),
            scripts = parseScriptsObj(norm[ConfigSections.SCRIPTS]),
            dangerfile = norm[ConfigSections.DANGERFILE]?.stringValue(),
            baseline = norm[ConfigSections.BASELINE]?.stringValue(),
            dryRun = norm[CAMEL_DRY_RUN]?.boolValue() ?: false,
            dryRunScope = norm[CAMEL_DRY_RUN_SCOPE]?.stringValue()?.let(DryRunScope::fromString),
            allowLocalhost = norm[CAMEL_ALLOW_LOCALHOST]?.boolValue() ?: false,
            allowPrivateNetwork = norm[CAMEL_ALLOW_PRIVATE_NETWORK]?.boolValue() ?: false,
            enrichment = parseEnrichmentObj((topLevelAi as? JsonObject)?.get("enrichment") as? JsonObject),
            enrichmentProviders = parseModuleSectionObj(
                norm[ConfigSections.ENRICHMENT] as? JsonObject,
            ),
            ai = topLevelAi,
            aiReports = parseModuleSectionObj(
                (topLevelAi as? JsonObject)?.get(ConfigSections.AI_REPORTS) as? JsonObject,
            ),
            aiMetrics = parseModuleSectionObj(
                (topLevelAi as? JsonObject)?.get(ConfigSections.AI_METRICS) as? JsonObject,
            ),
            autoApprove = parseAutoApproveObj(norm[CAMEL_AUTO_APPROVE] as? JsonObject),
            cache = parseCacheConfigObj(norm[CAMEL_CACHE] as? JsonObject),
            patterns = parsePatternsObj(norm[ConfigSections.PATTERNS] as? JsonObject),
            timeouts = parseTimeoutsObj(norm[CAMEL_TIMEOUTS] as? JsonObject),
            http = parseHttpObj(norm[ConfigSections.HTTP] as? JsonObject),
            limits = parseLimitsObj(norm[ConfigSections.LIMITS] as? JsonObject),
            display = parseDisplayObj(norm[ConfigSections.DISPLAY] as? JsonObject),
            security = (norm[ConfigSections.SECURITY] as? JsonObject)
                ?.let { decodeConfigSection(ConfigSections.SECURITY, SecurityConfig.serializer(), it) } ?: SecurityConfig(),
            report = report,
            diagnostics = parseDiagnosticsObj(norm[CAMEL_DIAGNOSTICS] as? JsonObject),
            policies = PolicyConfigParser().parse(norm),
            ruleEnrichment = parseRuleEnrichmentObj(norm[CAMEL_RULE_ENRICHMENT] as? JsonObject),
            profileOverrides = parseProfileOverridesConfigObj(norm[CAMEL_PROFILE_OVERRIDES] as? JsonObject),
            extensions = UnknownTopLevelKeys.claimed(norm, extensionKeys),
        )
        val validationErrors = config.validate()
        if (validationErrors.isNotEmpty()) {
            throw ConfigException(validationErrors.joinToString("; "))
        }
        return ConfigParseResult(config = config, missingEnvVars = missingVars, deniedEnvVars = deniedVars)
    }

    private fun parseProfileOverridesConfigObj(data: JsonObject?): ProfileOverridesConfig {
        if (data == null) return ProfileOverridesConfig()
        return decodeConfigSection("profile-overrides", ProfileOverridesConfig.serializer(), data)
    }

    private fun parseRuleEnrichmentObj(obj: JsonObject?): Map<String, RuleEnrichmentConfig>? {
        if (obj == null) return null
        return obj.mapValues { (_, value) ->
            val ruleObj = value as? JsonObject
            val enrich = (ruleObj?.get("enrich") as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.content }
                ?: emptyList()
            RuleEnrichmentConfig(enrich = enrich)
        }
    }

    /** Delegates to [ExtendsParser]. */
    private fun getExtendsEntriesObj(data: JsonObject): List<ExtendsEntry> = ExtendsParser.parse(data, parseEnvBlockObj(data).defaults)

    private fun parseRulesObj(
        data: JsonObject?,
        logger: Logger,
    ): RulesConfig {
        if (data == null) return RulesConfig()
        val ruleConfigs = mutableMapOf<String, JsonElement>()
        val ruleMeta = mutableMapOf<String, RuleExecutionMeta>()
        val booleanBlockKeys = mutableListOf<String>()

        for ((key, value) in data) {
            RuleKeyAliases.warnScalarRule(key, value, logger) // `x: true` = defaults; `x: false` is left to ModuleEnablementResolver
            // Keys and values are already normalized by the top-level deepNormalizeKeys +
            // deepInterpolateValues pass in parseFullConfigObj - no secondary call needed.
            val normRule = when {
                value is JsonObject -> value
                (value as? JsonPrimitive)?.booleanOrNull == true -> JsonObject(emptyMap())
                else -> continue
            }
            val normalizedKey = kebabToCamelCase(key).let { id ->
                ModuleIds.RULE_ALIASES[id]?.takeUnless(data::containsKey) ?: id
            }
            booleanBlockKeys += RuleKeyAliases.booleanBlockKeys(normalizedKey, normRule)

            // Extract rule-only config (strip meta fields) and deserialize meta via serializer.
            // Only a scalar `severity:` is meta; a nested object stays rule config.
            val isMetaKey = { key: String, element: JsonElement ->
                key in RuleExecutionMeta.META_FIELD_NAMES &&
                    (key != RuleExecutionMeta.SEVERITY_KEY || element is JsonPrimitive)
            }
            ruleConfigs[normalizedKey] = JsonObject(normRule.filterNot { (k, v) -> isMetaKey(k, v) })

            val meta = decodeConfigSection(
                "${ConfigSections.RULES}.$normalizedKey",
                RuleExecutionMeta.serializer(),
                JsonObject(normRule.filter { (k, v) -> isMetaKey(k, v) }),
            )
            RuleKeyAliases.warnUnknownSeverity(normalizedKey, meta, logger)
            if (meta != RuleExecutionMeta()) {
                ruleMeta[normalizedKey] = meta
            }
        }
        RuleKeyAliases.noteBooleanBlockKeys(booleanBlockKeys, logger)

        return RulesConfig(
            ruleConfigs = ruleConfigs,
            ruleMeta = ruleMeta,
        )
    }

    private fun parseModuleSectionObj(data: JsonObject?): Map<String, JsonElement>? {
        if (data == null) return null
        val result = mutableMapOf<String, JsonElement>()
        for ((key, value) in data) {
            val configElement: JsonElement = when {
                value is JsonPrimitive && value.booleanOrNull != null -> {
                    if (value.booleanOrNull == true) JsonObject(emptyMap()) else DISABLED_MODULE_ELEMENT
                }

                value is JsonObject -> {
                    // Keys and values are already normalized by the top-level pass.
                    val enabled = (value["enabled"] as? JsonPrimitive)?.booleanOrNull
                    if (enabled == false) DISABLED_MODULE_ELEMENT else value
                }

                else -> {
                    JsonObject(emptyMap())
                }
            }
            result[kebabToCamelCase(key)] = configElement
        }
        return result.takeIf { it.isNotEmpty() }
    }

    private fun parseScriptEnv(raw: JsonElement): List<String> =
        (raw as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: raw.notEnvList() }
            ?: raw.notEnvList()

    private fun JsonElement.notEnvList(): Nothing =
        throw ConfigParseException(
            "scripts[].env must be a list of env var names, got: $this",
            hint = "Write 'env' as a list such as [TOKEN].",
        )

    /**
     * Parse the `scripts:` config block into a list of [ScriptEntryConfig].
     */
    private fun parseScriptsObj(raw: JsonElement?): List<ScriptEntryConfig>? {
        if (raw == null || raw is JsonNull) return null
        val list = raw as? JsonArray ?: return null
        // every entry is kept; one that cannot be read fails the parse instead of silently vanishing.
        val result = list.mapIndexed { index, entry ->
            when (entry) {
                is JsonPrimitive -> {
                    ScriptEntryConfig(
                        path = entry.content,
                        mode = ScriptMode.AUTO,
                        timeout = ScriptEntryConfig.DEFAULT_TIMEOUT,
                    )
                }

                is JsonObject -> {
                    val path = (entry["path"] as? JsonPrimitive)?.content
                        ?: throw ConfigParseException(
                            "scripts[$index] has no 'path'",
                            hint = "Give every script entry a 'path:' or list the script as a plain string.",
                        )
                    val modeStr = (entry["mode"] as? JsonPrimitive)?.content
                    val mode = if (modeStr != null) {
                        ScriptMode.fromString(modeStr)
                    } else {
                        ScriptMode.AUTO
                    }
                    val timeout = (entry["timeout"] as? JsonPrimitive)?.let { prim ->
                        prim.content
                            .toLongOrNull()
                            ?.let { KarandashDuration.ofSeconds(it) }
                            ?: parseConfigDuration("scripts[$index].timeout", prim.content)
                    } ?: ScriptEntryConfig.DEFAULT_TIMEOUT
                    val env = entry["env"]?.takeUnless { it is JsonNull }?.let(::parseScriptEnv)
                    ScriptEntryConfig(path = path, mode = mode, timeout = timeout, env = env)
                }

                else -> {
                    throw ConfigParseException(
                        "scripts[$index] must be a path string or an object with 'path'",
                        hint = "Write the entry as a script path or '{ path: ..., mode: ..., timeout: ... }'.",
                    )
                }
            }
        }
        return result.takeIf { it.isNotEmpty() }
    }

    /** Delegates to [AiConfigSynthesizer]. */
    private fun parseAiObj(data: JsonObject?): JsonElement? = AiConfigSynthesizer.parseAiBlock(data)

    /** Delegates to [AiConfigSynthesizer]. */
    private fun synthesizeAiRuleConfigsObj(
        aiBlock: JsonObject,
        existingRules: RulesConfig,
        aiSubRuleKeys: Map<String, String>,
    ): RulesConfig = AiConfigSynthesizer.synthesizeRuleConfigs(aiBlock, existingRules, aiSubRuleKeys)

    private fun parseProfileObj(raw: JsonElement?): ProfileRef? =
        when {
            raw == null || raw is JsonNull -> {
                null
            }

            raw is JsonPrimitive && raw.isString -> {
                ProfileRef(id = raw.content.trim())
            }

            raw is JsonObject -> {
                val id = (raw["id"] as? JsonPrimitive)?.content
                    ?: throw ConfigException(
                        "${KarandashError.MISSING_REQUIRED_FIELD.bracketCode} Missing required field 'id' in profile block"
                    )
                val severity = (raw["severity"] as? JsonPrimitive)?.content
                val overrides = parseProfileOverridesObj(raw["overrides"] as? JsonObject)
                ProfileRef(id = id.trim(), severityOverride = severity, overrides = overrides)
            }

            else -> {
                throw ConfigException(
                    "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid profile value: expected string or object"
                )
            }
        }

    private fun parseProfileOverridesObj(data: JsonObject?): ProfileOverrides {
        if (data == null) return ProfileOverrides()
        val rulesObj = data["rules"] as? JsonObject
        val parsedRules: Map<String, JsonElement> = if (rulesObj != null) {
            buildMap {
                for ((key, value) in rulesObj) {
                    val ruleConfig = value as? JsonObject ?: continue
                    // Keys already normalized by the top-level deepNormalizeKeys pass.
                    put(kebabToCamelCase(key), ruleConfig)
                }
            }
        } else {
            emptyMap()
        }
        return ProfileOverrides(rules = parsedRules)
    }

    private fun parseEnrichmentObj(data: JsonObject?): EnrichmentConfig? {
        if (data == null) return null
        // Keys are already normalized to camelCase by the top-level deepNormalizeKeys pass.
        return decodeConfigSection("ai.enrichment", EnrichmentConfig.serializer(), data)
    }

    private fun parseAutoApproveObj(data: JsonObject?): AutoApproveConfig? {
        if (data == null) return null
        // Keys already normalized by the top-level deepNormalizeKeys pass.
        return decodeConfigSection(ConfigSections.AUTO_APPROVE, AutoApproveConfig.serializer(), data)
    }

    private fun parseCacheConfigObj(data: JsonObject?): CacheConfig? {
        if (data == null) return null
        // Keys at all depths already normalized by the top-level deepNormalizeKeys pass.
        return decodeConfigSection(ConfigSections.CACHE, CacheConfig.serializer(), data)
    }

    private fun parseTimeoutsObj(data: JsonObject?): TimeoutConfig? {
        if (data == null) return null
        // Keys at all depths already normalized by the top-level deepNormalizeKeys pass.
        return decodeConfigSection(ConfigSections.TIMEOUTS, TimeoutConfig.serializer(), data)
    }

    private fun parseHttpObj(data: JsonObject?): HttpConfig {
        if (data == null) return HttpConfig()
        return decodeConfigSection(ConfigSections.HTTP, HttpConfig.serializer(), data)
    }

    /** Normalized again without preserve keys: `limits.patterns` is schema, not the user-keyed top-level `patterns`. */
    private fun parseLimitsObj(data: JsonObject?): LimitsConfig {
        if (data == null) return LimitsConfig()
        return decodeConfigSection(ConfigSections.LIMITS, LimitsConfig.serializer(), deepNormalizeKeys(data))
    }

    private fun parseDisplayObj(data: JsonObject?): DisplayConfig {
        if (data == null) return DisplayConfig()
        return decodeConfigSection(ConfigSections.DISPLAY, DisplayConfig.serializer(), data)
    }

    private fun parseDiagnosticsObj(data: JsonObject?): DiagnosticConfig? {
        if (data == null) return null
        return decodeConfigSection(ConfigSections.DIAGNOSTICS, DiagnosticConfig.serializer(), data)
    }

    /**
     * Keys stay as-is (pattern names, not config keys). Values arrive already interpolated by the
     * `deepInterpolateValues` pass, so a `${...}` inside an env var's value stays literal.
     */
    private fun parsePatternsObj(data: JsonObject?): Map<String, String> {
        if (data == null) return emptyMap()
        return data.entries.associate { (key, value) ->
            key to (
                (value as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw ConfigException(
                        "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Pattern '$key' must be a string, got: $value"
                    )
            )
        }
    }

    private fun parseEnvBlockObj(data: JsonObject): EnvDeclaration {
        val raw = data[ConfigSections.ENV] ?: return EnvDeclaration.EMPTY
        return when (raw) {
            is JsonNull -> {
                EnvDeclaration.EMPTY
            }

            is JsonArray -> {
                val vars = raw
                    .mapNotNull { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                    .associateWith { null as String? }
                EnvDeclaration(vars)
            }

            is JsonObject -> {
                val vars = raw.entries.associate { (k, v) ->
                    k to when (v) {
                        is JsonNull -> null
                        is JsonPrimitive -> v.content
                        else -> v.toString()
                    }
                }
                EnvDeclaration(vars)
            }

            else -> {
                throw ConfigException(
                    "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid 'env' block: expected list or map, got ${raw::class.simpleName}"
                )
            }
        }
    }

    // ── Private helpers ────────────────────────────────────────────────

    internal fun kebabToCamelCase(key: String): String = ConfigKeyNormalizer.kebabToCamelCase(key)

    /**
     * Interpolate environment variables in the format ${VAR_NAME}.
     *
     * **Security**: Env vars in the [denylist] are never interpolated (security-sensitive vars
     * like PATH, HOME, SSH keys). All other vars are resolved from the environment; if unset,
     * the [defaults] map is consulted; if still missing, the var name is added to [missingVars]
     * and replaced with an empty string.
     *
     * [defaults] is required: every caller passes the `env:` defaults in scope (empty only where no config is).
     */
    internal fun interpolate(
        value: String,
        defaults: Map<String, String>,
        denylist: Set<String> = KarandashEnvSecurity.DENYLIST,
        missingVars: MutableSet<String>? = null,
        deniedVars: MutableSet<String>? = null,
        onResolve: ((varName: String, source: String, resolvedValue: String?) -> Unit)? = null,
    ): String = EnvInterpolation.interpolate(value, denylist, defaults, missingVars, deniedVars, onResolve)
}
