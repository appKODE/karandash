package io.karandash.bootstrap

import io.karandash.api.Module
import io.karandash.api.ModuleRegistry
import io.karandash.api.finding.MessageCode
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.RuleModule
import kotlinx.serialization.json.JsonElement

/**
 * Collects self-declared defaults from all registered modules.
 *
 * Feeds [io.karandash.config.pipeline.ZeroConfigDefaults] and
 * [io.karandash.cli.commands.doctor.DoctorCommandCommon] instead of per-plugin knowledge hardcoded there.
 */
object ModuleDefaultsCollector {
    /**
     * Returns all registered provider modules sorted by their [Module.defaultPriority].
     * Lower priority value = higher precedence.
     */
    fun sortedProviders(registry: ModuleRegistry): List<PlatformServiceModule> =
        registry
            .platformServices()
            .sortedBy { it.defaultPriority }

    /**
     * Returns provider IDs sorted by priority order.
     * Replaces the former ProviderConfigDefaults.builtInProviderOrder.
     */
    fun providerOrder(registry: ModuleRegistry): List<String> = sortedProviders(registry).map { it.id }

    /**
     * Returns the missing-auth error message for a provider by querying the module directly.
     */
    suspend fun missingAuthMessage(
        providerId: String,
        registry: ModuleRegistry,
    ): String? {
        val module = registry.platformServices().find { it.id == providerId }
        return module
            ?.messages()
            ?.firstOrNull { it.code == MessageCode.MISSING_AUTH }
            ?.text
    }

    /**
     * Returns all rule modules that declare [Module.defaultEnabled] = true,
     * sorted by priority. Used for zero-config mode.
     */
    fun defaultEnabledRules(registry: ModuleRegistry): List<RuleModule> =
        registry
            .ruleModules()
            .filter { it.defaultEnabled }
            .sortedBy { it.defaultPriority }

    /**
     * Builds the zero-config rule config map by collecting [Module.defaultConfig]
     * from all default-enabled rule modules.
     *
     * Returns a map of ruleId -> config suitable for [io.karandash.config.RulesConfig.ruleConfigs].
     */
    suspend fun zeroConfigRuleConfigs(registry: ModuleRegistry): Map<String, JsonElement> =
        buildMap {
            for (rule in defaultEnabledRules(registry)) {
                val config = rule.defaultConfig()
                if ((config as? kotlinx.serialization.json.JsonObject)?.isNotEmpty() == true) {
                    for (ruleId in rule.contributedRules()) {
                        put(ruleId, config)
                    }
                }
            }
        }

    /**
     * Counts all configured validation rules by iterating registered rule modules.
     */
    suspend fun allRuleIds(registry: ModuleRegistry): Set<String> =
        registry
            .ruleModules()
            .flatMap { it.contributedRules() }
            .toSet()

    /**
     * Returns a map of provider ID → default public API base URL for providers
     * that declare a non-null [PlatformServiceModule.defaultApiUrl].
     * Self-hosted-only providers (Gerrit, Gitea, AWS) are excluded.
     *
     * Replaces the deleted `PlatformApiUrls.defaultForPlatform()`.
     */
    suspend fun allProviderApiUrls(registry: ModuleRegistry): Map<String, String> =
        sortedProviders(registry)
            .mapNotNull { provider ->
                provider.defaultApiUrl()?.let { provider.id to it }
            }.toMap()

    /**
     * Returns all reporter format IDs from registered reporter modules.
     *
     * Replaces the deleted `ReporterFormats.all` static set.
     */
    fun allReporterFormats(registry: ModuleRegistry): Set<String> =
        registry
            .reporters()
            .map { it.formatId }
            .toSet()

    /**
     * Returns all auth DSL block names from registered provider modules.
     *
     * Dynamically collects auth DSL names from registered provider modules.
     */
    fun allAuthDslNames(registry: ModuleRegistry): Set<String> =
        sortedProviders(registry)
            .map { it.dslName }
            .toSet()

    /**
     * Returns a map of ruleId -> explanation text by querying all rule modules.
     * Used by console reporter's --explain mode.
     */
    suspend fun ruleExplanations(registry: ModuleRegistry): Map<String, String> =
        buildMap {
            for (ruleModule in registry.ruleModules()) {
                for (ruleId in ruleModule.contributedRules()) {
                    ruleModule.ruleExplanation(ruleId)?.let { explanation ->
                        put(ruleId, explanation)
                    }
                }
            }
        }
}
