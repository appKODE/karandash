package io.karandash.config.pipeline

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.module.ProfileModule
import io.karandash.config.Config
import io.karandash.config.RuleExecutionMeta
import io.karandash.config.RulesConfig
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug

/**
 * Provides default configuration when no config file is present by querying
 * the [ModuleRegistry] for registered rule modules and profiles.
 *
 * Requires at least one rule module to be registered. If no rules are available,
 * an error is thrown instructing the user to install or attach rule modules.
 */
object ZeroConfigDefaults {
    /**
     * Returns a default configuration by querying registered modules for their
     * self-declared defaults.
     *
     * Resolution order:
     * 1. Use the profile flagged as [ProfileModule.isDefaultZeroConfigProfile]
     * 2. Throw [IllegalStateException] if no profile is available
     *
     * @param registry The module registry containing loaded rule/profile modules
     * @param logger Receives which profile supplied the defaults, at debug.
     * @return A [Config] with rules from the registry
     * @throws IllegalStateException if no rule modules provide default configs
     */
    suspend fun defaultConfig(
        registry: ModuleRegistry,
        logger: Logger = NoOpLogger,
    ): Config {
        val minimalProfile = registry
            .getByType(ModuleType.PROFILE)
            .filterIsInstance<ProfileModule>()
            .firstOrNull { it.isDefaultZeroConfigProfile }

        if (minimalProfile != null) {
            val (profileConfigs, severities) = minimalProfile.ruleConfigs().splitProfileSeverity()
            logger.debug { "ZeroConfig: no config file; profile '${minimalProfile.profileId}' supplies ${profileConfigs.size} rule(s)" }
            return Config(
                rules = RulesConfig(
                    ruleConfigs = profileConfigs,
                    ruleMeta = severities.mapValues { (_, severity) -> RuleExecutionMeta(severity = severity) },
                ),
                reporters = null,
            )
        }

        error(
            "No rule modules are registered. " +
                "Install or attach rule modules, or create a config file with 'karandash init'."
        )
    }
}
