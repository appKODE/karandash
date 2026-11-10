package io.karandash.cli.commands

import io.karandash.api.errors.ConfigException
import io.karandash.api.module.PlatformServiceModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.PrUrlParser
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.cli.plugin.loadPluginsWithManifest
import io.karandash.cli.provider.ProviderResolver
import io.karandash.cli.provider.ProviderRuntime
import io.karandash.config.Config
import io.karandash.config.getRuleConfigElements
import io.karandash.config.pipeline.ModuleEnablementResolver
import io.karandash.engine.ValidationEngine
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import kotlinx.serialization.json.JsonObject

/**
 * Evaluates the configured rules outside the full `validate` pipeline, for `simulate`, `mutate` and
 * `test-rule`. The config is loaded exactly as `validate` loads it (extends chain, `{ file: }` refs,
 * `${VAR}` interpolation); the rule modules it configures are initialized once in [load].
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class ConfiguredRuleRunner private constructor(
    private val bootstrap: PluginBootstrap,
    override val config: Config,
) : ConfiguredRules {
    /** Runs every rule kept in [config] on [mr] and returns their findings. */
    override suspend fun validate(mr: MergeRequest): List<Finding> =
        ValidationEngine(
            config = config,
            registry = bootstrap.registry(),
            configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
            enabledModules = ModuleEnablementResolver(bootstrap.logger).resolveFromConfig(config),
            logger = bootstrap.logger,
        ).validate(mr).findings

    /** Fetches the MR/PR at [url] with the platform service configured under `platforms:`. */
    override suspend fun fetchMergeRequest(url: String): MergeRequest {
        val registry = bootstrap.registry()
        val parsed = PrUrlParser(registry.platformServices()).parse(url, warnOnInsecureHttp = true)
            ?: throw ConfigException("Cannot parse MR/PR URL: $url")
        val provider = ProviderRuntime(ProviderResolver()).resolveModule(parsed.platform, registry)
        bootstrap.initPlatformService(provider, config)
        return try {
            provider.fetchMergeRequestByUrl(url)
        } finally {
            provider.shutdown()
        }
    }

    companion object {
        /** [ConfiguredRulesSource] for the AI commands in cli-ai: [load] inside [withBootstrap]. */
        fun source(shared: PluginBootstrap?): ConfiguredRulesSource =
            object : ConfiguredRulesSource {
                override suspend fun <T> withRules(
                    configPath: String?,
                    ruleFilter: (String) -> Boolean,
                    block: suspend (ConfiguredRules) -> T,
                ): T = withBootstrap(shared) { block(load(it, configPath, ruleFilter)) }
            }

        /**
         * Loads the config at [configPath] (auto-discovered when null), keeps only the rules accepted by
         * [ruleFilter], and initializes their modules.
         */
        suspend fun load(
            bootstrap: PluginBootstrap,
            configPath: String?,
            ruleFilter: (String) -> Boolean = { true },
        ): ConfiguredRuleRunner {
            val full = ConfigLoader.forCommand(configPath, bootstrap).loadConfig().config
            val config = full.copy(rules = full.rules.copy(ruleConfigs = full.rules.ruleConfigs.filterKeys(ruleFilter)))
            val handlers = config.rules.ruleConfigs.keys
                .mapNotNull { bootstrap.registry().findRuleHandler(it) }
                .distinct()
            for (handler in handlers) {
                if (!handler.initialize(bootstrap.forModule(handler.id))) {
                    bootstrap.logger.warn("Rule module '${handler.id}' failed to initialize; its rules may report nothing")
                }
            }
            return ConfiguredRuleRunner(bootstrap, config)
        }

        /**
         * Runs [block] with [shared] when given, else with a freshly loaded bootstrap that is shut down
         * afterwards (commands constructed standalone, e.g. in tests).
         */
        suspend fun <T> withBootstrap(
            shared: PluginBootstrap?,
            block: suspend (PluginBootstrap) -> T,
        ): T {
            if (shared != null) return block(shared)
            val owned = PluginBootstrap()
            owned.loadPluginsWithManifest()
            return try {
                block(owned)
            } finally {
                owned.shutdown()
            }
        }
    }
}

/**
 * Initializes [service] with its `platforms.<configKey>` block of [config] and the run's `allowLocalhost`.
 * @throws ConfigException when the service rejects its config.
 */
internal suspend fun PluginBootstrap.initPlatformService(
    service: PlatformServiceModule,
    config: Config,
) {
    val serviceConfig = config.platformConfig(service.configKey) ?: JsonObject(emptyMap())
    val contexts = newRunContexts().apply { allowLocalhost = config.allowLocalhost }
    if (!service.initialize(contexts.contextWithConfig(service.id, serviceConfig))) {
        throw ConfigException("Platform service '${service.id}' failed to initialize; check 'platforms.${service.configKey}'")
    }
}
