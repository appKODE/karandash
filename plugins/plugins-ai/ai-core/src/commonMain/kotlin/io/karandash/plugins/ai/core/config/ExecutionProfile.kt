package io.karandash.plugins.ai.core.config

import io.karandash.duration.KarandashDuration
import io.karandash.models.finding.Finding
import io.karandash.plugins.ai.core.AiPluginConfig
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Validation execution profiles: one config, multiple run modes.
 *
 * The profile named by `ai.executionProfile` decides:
 * - which AI analyzers run and which configured rules' findings are kept (`includeRules`/`excludeRules`)
 * - whether AI runs at all (`aiEnabled`)
 * - how long AI analyzers may keep starting after the plugin starts (`totalTimeout`)
 *
 * `exitOnError` and `verbose` are accepted for compatibility but change nothing; a profile setting them warns.
 */
object ExecutionProfileResolver {
    /**
     * Resolves the effective profile overrides for a given profile name.
     *
     * @param profileName The profile name (e.g. "ci", "pre-push", "local", "security-audit").
     * @param customProfiles User-defined profiles from config.
     * @return The resolved profile, or null if the profile name is not recognized.
     */
    fun resolve(
        profileName: String?,
        customProfiles: Map<String, ExecutionProfileConfig> = emptyMap(),
    ): ExecutionProfileConfig? {
        if (profileName == null) return null
        return customProfiles[profileName] ?: BUILTIN_PROFILES[profileName]
    }

    /**
     * Applies profile overrides to determine which rules should be included/excluded.
     *
     * @param profile The execution profile to apply.
     * @param allRuleIds All available rule IDs.
     * @return Set of rule IDs that should run under this profile.
     */
    fun filterRules(
        profile: ExecutionProfileConfig,
        allRuleIds: Set<String>
    ): Set<String> {
        val included = if (profile.includeRules.isNotEmpty()) {
            allRuleIds.filter { ruleId -> profile.includeRules.any { ruleId.contains(it) } }.toSet()
        } else {
            allRuleIds
        }
        return if (profile.excludeRules.isNotEmpty()) {
            included.filter { ruleId -> profile.excludeRules.none { ruleId.contains(it) } }.toSet()
        } else {
            included
        }
    }

    /**
     * with an active `ai.executionProfile`, keeps the findings of the rules configured under the top-level
     * [config]'s `rules:` that the profile lets run, matched on the rule ID without case, `-` or `_`. A finding from
     * any other rule (AI, engine) is kept.
     */
    fun filterFindings(
        aiConfig: AiPluginConfig,
        findings: List<Finding>,
        config: JsonElement?,
    ): List<Finding> {
        val profile = resolve(aiConfig.executionProfile, aiConfig.executionProfiles) ?: return findings
        val configuredRules = ((config as? JsonObject)?.get("rules") as? JsonObject)?.keys.orEmpty()
        val configured = configuredRules.map(::comparable).toSet()
        val normalized = profile.copy(
            includeRules = profile.includeRules.map(::comparable),
            excludeRules = profile.excludeRules.map(::comparable),
        )
        return findings.filter { finding ->
            val rule = comparable(finding.rule)
            rule !in configured || filterRules(normalized, setOf(rule)).isNotEmpty()
        }
    }

    /**
     * the configured rule ids among [ruleIds] the active `ai.executionProfile` keeps from running, matched
     * like [filterFindings]. AI rules ([aiRuleIds], and `ai-` custom rules) are left to the AI module's own filter.
     */
    fun excludedRules(
        aiConfig: AiPluginConfig,
        ruleIds: Set<String>,
        aiRuleIds: Set<String>,
    ): Set<String> {
        val profile = resolve(aiConfig.executionProfile, aiConfig.executionProfiles) ?: return emptySet()
        val normalized = profile.copy(
            includeRules = profile.includeRules.map(::comparable),
            excludeRules = profile.excludeRules.map(::comparable),
        )
        val ai = aiRuleIds.map(::comparable).toSet()
        return ruleIds
            .filterNot { id -> comparable(id) in ai || id.startsWith(AI_RULE_PREFIX) }
            .filter { id -> filterRules(normalized, setOf(comparable(id))).isEmpty() }
            .toSet()
    }

    private const val AI_RULE_PREFIX = "ai-"

    private fun comparable(id: String): String = id.lowercase().filter { it != '-' && it != '_' }

    /** Profile keys kept for compatibility that change nothing; a profile setting them gets a warning. */
    val NO_EFFECT_KEYS: Set<String> = setOf("exitOnError", "verbose")

    /** Built-in profiles available without any user configuration. */
    val BUILTIN_PROFILES: Map<String, ExecutionProfileConfig> = mapOf(
        "ci" to ExecutionProfileConfig(
            description = "CI pipeline: deterministic rules only, no AI",
            aiEnabled = false,
            excludeRules = listOf("ai-"),
            exitOnError = true,
        ),
        "pre-push" to ExecutionProfileConfig(
            description = "Pre-push hook: fast subset, 3s timeout",
            includeRules = listOf("title-format", "commit-messages", "branch-naming", "draft"),
            aiEnabled = false,
            totalTimeout = KarandashDuration.ofSeconds(PRE_PUSH_TIMEOUT_SECONDS.toLong()),
            exitOnError = false,
        ),
        "local" to ExecutionProfileConfig(
            description = "Local development: all rules, AI enabled",
            aiEnabled = true,
            exitOnError = false,
            verbose = true,
        ),
        "security-audit" to ExecutionProfileConfig(
            description = "Security audit: security rules only, AI enabled",
            includeRules = listOf("security", "codeowners", "approval", "ai-security"),
            aiEnabled = true,
            exitOnError = true,
        ),
    )

    private const val PRE_PUSH_TIMEOUT_SECONDS = 3
}

/** Configuration for a single execution profile. */
@Serializable
data class ExecutionProfileConfig(
    val description: String = "",
    val includeRules: List<String> = emptyList(),
    val excludeRules: List<String> = emptyList(),
    val aiEnabled: Boolean = true,
    val totalTimeout: KarandashDuration = KarandashDuration.ZERO,
    val exitOnError: Boolean = true,
    val verbose: Boolean = false,
)
