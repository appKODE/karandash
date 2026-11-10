package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.errors.ConfigException
import io.karandash.api.module.ProfileModule
import io.karandash.config.Config
import io.karandash.config.ProfileOverridesConfig
import io.karandash.config.ProfileRef
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.keys.PolicyKeys
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.ModuleIdNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Resolves a [ProfileRef] against the module registry and applies profile rule config
 * defaults to the [Config].
 *
 * ## Merge priority (highest to lowest)
 * 1. User's local `rules:` block (already in [Config.rules.ruleConfigs])
 * 2. [ProfileRef.overrides] rule overrides from config
 * 3. [ProfileOverridesConfig] threshold overrides from `profile-overrides:` block
 * 4. Profile's [ProfileModule.ruleConfigs] baseline
 * 5. Rules inherited via `extends:` only (`inheritedRuleIds`) - the profile wins over them
 *
 * Each layer is deep-merged over the one below, so a user key overrides only that key.
 * [ProfileRef.severityOverride] becomes the [io.karandash.config.RuleExecutionMeta.severity]
 * of every profile-contributed rule that has no explicit per-rule `severity:`; failing that, the severity the
 * profile itself declares for the rule (`rule(id, cfg, severity)`) is used.
 * [ProfileModule.policies] run before the user's own `policies:`, so a user policy on the same rules wins.
 */
class ProfileResolver(
    private val registry: ModuleRegistry,
    private val logger: Logger = NoOpLogger,
) {
    /**
     * Applies the profile referenced by [ref] to [config], merging profile rule defaults
     * under the user's explicit rules and profile overrides.
     *
     * @throws ConfigException if the profile ID is not found in the registry.
     */
    suspend fun apply(
        config: Config,
        ref: ProfileRef,
        inheritedRuleIds: Set<String> = emptySet(),
    ): Config {
        val profile = allProfiles().firstOrNull { it.profileId == ref.id }
            ?: throw ConfigException(
                "Unknown profile: '${ref.id}'. " +
                    "Run 'karandash profile list' to see available profiles."
            )

        val merged = mutableMapOf<String, JsonElement>()
        val userRuleIds = config.rules.ruleConfigs.keys
        // Profile ids (often kebab-case) land on the user's configured key, else on the camelCase
        // key the config parser would give them, so `title-format` and `titleFormat` never split.
        val split = profile.ruleConfigs().splitProfileSeverity()
        val profileRules = split.ruleConfigs.mapKeys { (ruleId, _) -> canonicalRuleId(ruleId, userRuleIds) }
        val profileSeverities = split.severities.mapKeys { (ruleId, _) -> canonicalRuleId(ruleId, userRuleIds) }
        logger.debug {
            "Profile '${ref.id}': ${profileRules.size} profile rule(s), ${ref.overrides.rules.size} override(s), " +
                "${config.rules.ruleConfigs.size} user rule(s) (${inheritedRuleIds.size} inherited)"
        }

        for ((ruleId, profileConfig) in profileRules) {
            merged[ruleId] = profileConfig
        }

        // Apply profile-overrides threshold values (lower priority than user rules); a patch for a rule that
        // neither the profile nor the user configures would enable that rule, so it is skipped with a warning.
        val refRuleIds = ref.overrides.rules.keys
            .mapTo(mutableSetOf()) { canonicalRuleId(it, userRuleIds + merged.keys) }
        val configuredIds = userRuleIds + refRuleIds
        applyProfileOverrides(merged, config.profileOverrides, configuredIds).forEach { (section, ruleId) ->
            logger.warn("profile-overrides.$section ignored: profile '${ref.id}' does not configure rule '$ruleId'")
        }

        for ((rawRuleId, overrideElement) in ref.overrides.rules) {
            val ruleId = canonicalRuleId(rawRuleId, userRuleIds + merged.keys)
            val existing = merged[ruleId]
            merged[ruleId] = if (existing != null) {
                JsonElementMerger.mergeLayers(existing, overrideElement)
            } else {
                overrideElement
            }
        }

        for ((ruleId, userConfig) in config.rules.ruleConfigs) {
            val profileConfig = merged[ruleId]
            merged[ruleId] = when {
                profileConfig == null -> userConfig
                ruleId in inheritedRuleIds -> JsonElementMerger.mergeLayers(userConfig, profileConfig)
                else -> JsonElementMerger.mergeLayers(profileConfig, userConfig)
            }
        }

        val profilePolicies = PolicyConfigParser().parse(JsonObject(mapOf(PolicyKeys.POLICIES to JsonArray(profile.policies()))))
        return config.copy(
            reporters = config.reporters ?: profileReporters(profile),
            policies = (profilePolicies + config.policies.orEmpty()).ifEmpty { config.policies },
            rules = config.rules.copy(
                ruleConfigs = merged,
                ruleMeta = config.rules.ruleMeta + profileSeverityMeta(config, ref, profileRules.keys + refRuleIds, profileSeverities),
            )
        )
    }

    /** [ProfileModule.reporterConfigs], used only when the config has no `reporters:` block. */
    private suspend fun profileReporters(profile: ProfileModule): Map<String, JsonElement>? =
        profile.reporterConfigs().ifEmpty { null }?.also {
            logger.info(
                "Profile '${profile.profileId}': using its reporters (${it.keys.joinToString()}); a reporters: block overrides them"
            )
        }

    private fun profileSeverityMeta(
        config: Config,
        ref: ProfileRef,
        profileRuleIds: Set<String>,
        profileSeverities: Map<String, String>,
    ): Map<String, RuleExecutionMeta> =
        profileRuleIds
            .map { it to config.rules.metaFor(it) }
            .filter { (_, meta) -> meta.severity == null }
            .mapNotNull { (ruleId, meta) ->
                (ref.severityOverride ?: profileSeverities[ruleId])?.let { ruleId to meta.copy(severity = it) }
            }.toMap()

    /** Returns all registered profiles sorted by [ProfileModule.profileId]. */
    fun allProfiles(): List<ProfileModule> =
        registry
            .getByType(ModuleType.PROFILE)
            .filterIsInstance<ProfileModule>()
            .sortedBy { it.profileId }

    companion object {
        private const val TITLE_FORMAT_RULE = "titleFormat"
        private const val SIZE_LIMITS_RULE = "sizeLimits"
        private const val COMMIT_MESSAGES_RULE = "commitMessages"
        private const val TITLE_SECTION = "title"
        private const val SIZE_SECTION = "size"

        private fun canonicalRuleId(
            ruleId: String,
            configuredIds: Set<String>,
        ): String = ModuleIdNormalizer.resolve(ruleId, configuredIds) ?: ConfigKeyNormalizer.kebabToCamelCase(ruleId)

        /**
         * Merges non-null [ProfileOverridesConfig] fields into the rule config map.
         * Values are merged at the lowest priority (below profile baseline). A patch for a rule absent from
         * [merged] and [configuredIds] is not applied; the returned `section to ruleId` pairs name the skipped ones.
         */
        internal fun applyProfileOverrides(
            merged: MutableMap<String, JsonElement>,
            overrides: ProfileOverridesConfig,
            configuredIds: Set<String> = emptySet(),
        ): List<Pair<String, String>> {
            val patches = listOfNotNull(
                Triple(TITLE_SECTION, TITLE_FORMAT_RULE, JsonObject(buildTitlePatch(overrides))),
                buildCommitSubjectPatch(overrides)?.let { Triple(TITLE_SECTION, COMMIT_MESSAGES_RULE, it) },
                Triple(SIZE_SECTION, SIZE_LIMITS_RULE, JsonObject(buildSizePatch(overrides))),
            )
            val skipped = mutableListOf<Pair<String, String>>()
            for ((section, ruleId, patch) in patches) {
                if (patch is JsonObject && patch.isEmpty()) continue
                val existing = merged[ruleId]
                when {
                    existing != null -> merged[ruleId] = JsonElementMerger.mergeLayers(existing, patch)
                    ruleId in configuredIds -> merged[ruleId] = patch
                    else -> skipped += section to ruleId
                }
            }
            return skipped
        }

        private fun buildTitlePatch(overrides: ProfileOverridesConfig,): Map<String, JsonElement> =
            buildMap {
                overrides.title.minLength?.let { put("minLength", JsonPrimitive(it)) }
                overrides.title.maxLength?.let { put("maxLength", JsonPrimitive(it)) }
            }

        private fun buildCommitSubjectPatch(overrides: ProfileOverridesConfig,): JsonElement? {
            val maxSubject = overrides.title.maxSubjectLength ?: return null
            val subjectObj = JsonObject(mapOf("maxLength" to JsonPrimitive(maxSubject)))
            return JsonObject(mapOf("subject" to subjectObj))
        }

        private fun buildSizePatch(overrides: ProfileOverridesConfig,): Map<String, JsonElement> =
            buildMap {
                overrides.size.maxLines?.let { put("maxLines", JsonPrimitive(it)) }
                overrides.size.maxCommits?.let { put("maxCommits", JsonPrimitive(it)) }
            }
    }
}
