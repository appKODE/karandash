package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.PluginContext
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.config.Config
import io.karandash.config.RulesConfig
import io.karandash.models.MergeRequest
import io.karandash.models.finding.Finding
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** A top-level block named after a module's configKey reaches that module as its plugin config. */
class ModuleOwnedConfigTest {
    private class OwningRuleModule : RuleModule {
        override val id = "io.example.probe"
        override val name = "Probe"
        override val version = "1.0"
        override val configKey = OWNED_KEY
        val received = mutableListOf<JsonElement>()

        override suspend fun contributedRules(): Set<String> = setOf(RULE_ID)

        override suspend fun initialize(context: PluginContext): Boolean {
            received += context.pluginConfig
            return true
        }

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = emptyList<Finding>()
    }

    private val block = JsonObject(mapOf("maxTitleLength" to JsonPrimitive(10), "mode" to JsonPrimitive("block")))

    @Test
    fun `runtime module resolution falls back to the module-owned block`() {
        val config = Config(extensions = mapOf(OWNED_KEY to block))
        val handler = FixAndResultHandler(options(), NoOpLogger, ModuleInitializer(NoOpLogger, options()))

        assertEquals(block, handler.resolveModuleConfig(config, emptyMap(), OwningRuleModule(), null))
    }

    @Test
    fun `rule module gets the owned block merged under its rules entry`() =
        runTest {
            val module = OwningRuleModule()
            val registry = ModuleRegistryImpl().apply { register(module) }
            val bootstrap = PluginBootstrap().apply { loadPlugins() }
            val ruleEntry = JsonObject(mapOf("mode" to JsonPrimitive("rule")))
            val configStage = ConfigLoadStage(
                config = Config(
                    rules = RulesConfig(ruleConfigs = mapOf(RULE_ID to ruleEntry)),
                    extensions = mapOf(OWNED_KEY to block),
                ),
                configFilePath = null,
                usingZeroConfig = false,
                registry = registry,
                bootstrap = bootstrap,
            )

            ModuleInitializer(NoOpLogger, options()).initializeConfiguredRuleModules(
                configStage,
                ValidateCommandScope(configStage.config, NoOpLogger, registry, bootstrap),
                emptyList(),
            )

            assertEquals(
                JsonObject(mapOf("maxTitleLength" to JsonPrimitive(10), "mode" to JsonPrimitive("rule"))),
                module.received.single(),
            )
        }

    private fun options() =
        ValidationOptions(
            configPathStr = null,
            mrDataStr = null,
            mrId = null,
            platform = null,
            project = null,
            prUrl = null,
            format = "console",
            outputPathStr = null,
            quiet = false,
            verbose = false,
            strict = false,
            dryRun = false,
            showExecutionPlan = false,
            explain = false,
            fix = false,
            yes = false,
            noAutoDetect = false,
            outputWriter = {},
            sharedBootstrap = PluginBootstrap(),
            logger = NoOpLogger,
        )

    private companion object {
        const val OWNED_KEY = "probe"
        const val RULE_ID = "probeRule"
    }
}
