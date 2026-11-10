package io.karandash.cli.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.RuleModule
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.config.Config
import io.karandash.config.RuleEnrichmentConfig
import io.karandash.config.RulesConfig
import io.karandash.config.keys.ModuleIds
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies that [ModuleInitializer.deliverEnrichmentToRuleModules] actually delivers
 * [EnrichmentContext] data to the [PluginContext.enrichment] a rule module observes
 * (providers fetch data, and this is the step that hands it to a rule).
 */
class ModuleInitializerEnrichmentTest {
    private class RecordingRuleModule(
        override val id: String,
        private val ruleId: String,
        override val supportedEnrichment: List<String> = emptyList(),
    ) : RuleModule {
        override val name = "Recording"
        override val version = "1.0"
        override val dslName = id
        override val usesExtensionComputedConfig = id == ModuleIds.AI_ANALYSIS
        var lastReceivedEnrichment: EnrichmentContext? = null
            private set
        var lastReceivedConfig: JsonElement? = null
            private set
        var initializeCalls = 0
            private set

        override suspend fun contributedRules(): Set<String> = setOf(ruleId)

        override suspend fun initialize(context: PluginContext): Boolean {
            initializeCalls++
            lastReceivedEnrichment = context.enrichment
            lastReceivedConfig = context.pluginConfig
            return true
        }

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ) = emptyList<io.karandash.models.finding.Finding>()
    }

    /** Stands in for the AI extension: computes a rule-module config carrying provider credentials. */
    private class CredentialComputingExtension : PipelineExtension {
        override val id = "stub-ai-extension"
        override val name = "Stub AI extension"
        override val version = "1.0"
        override val dslName = id
        override val type = ModuleType.PIPELINE_EXTENSION

        override suspend fun initialize(context: PluginContext) = true

        override fun computeRuleModuleConfig(configElement: JsonElement?): JsonElement =
            buildJsonObject {
                put("provider", "openai")
                put("apiKey", "sk-main")
                put("maxTokens", 512)
                putJsonObject("providers") {
                    putJsonObject("backup") {
                        put("api-key", "sk-backup")
                        put("model", "m")
                    }
                }
            }
    }

    private suspend fun makeConfigStage(
        ruleId: String,
        registry: ModuleRegistryImpl,
        ruleEnrichment: Map<String, RuleEnrichmentConfig>? = null,
        ai: JsonElement? = null,
    ): ConfigLoadStage {
        val bootstrap = PluginBootstrap()
        bootstrap.loadPlugins()
        return ConfigLoadStage(
            config = Config(
                rules = RulesConfig(ruleConfigs = mapOf(ruleId to JsonObject(emptyMap()))),
                ruleEnrichment = ruleEnrichment,
                ai = ai,
            ),
            configFilePath = null,
            usingZeroConfig = false,
            registry = registry,
            bootstrap = bootstrap,
        )
    }

    private fun moduleInitializer(logger: Logger = NoOpLogger): ModuleInitializer =
        ModuleInitializer(
            logger = logger,
            options = ValidationOptions(
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
                logger = logger,
            ),
        )

    @Test
    fun `guest receives no apiKey in the computed AI config`() =
        runTest {
            val registry = ModuleRegistryImpl()
            // a guest (not registered by the built-in loader) even when it reuses the built-in AI module ID
            val guest = RecordingRuleModule(id = ModuleIds.AI_ANALYSIS, ruleId = "aiRule")
            registry.register(guest)
            val configStage = makeConfigStage("aiRule", registry, ai = buildJsonObject { put("provider", "openai") })

            moduleInitializer().initializeConfiguredRuleModules(
                configStage,
                ValidateCommandScope(configStage.config, NoOpLogger, registry, configStage.bootstrap),
                listOf(CredentialComputingExtension()),
            )

            val received = guest.lastReceivedConfig?.jsonObject
            assertTrue(received != null, "guest should have been initialized with the computed config")
            assertNull(received["apiKey"], "guest must not receive the provider apiKey")
            assertNull(
                received["providers"]
                    ?.jsonObject
                    ?.get("backup")
                    ?.jsonObject
                    ?.get("api-key")
            )
            assertEquals("openai", received["provider"]?.jsonPrimitive?.content, "non-secret fields are kept")
            assertEquals("512", received["maxTokens"]?.jsonPrimitive?.content, "maxTokens is not a credential")
        }

    @Test
    fun `module declaring supportedEnrichment receives filtered data on re-init`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val module = RecordingRuleModule(id = "stub-jira-rule", ruleId = "jiraRule", supportedEnrichment = listOf("jira"))
            registry.register(module)
            val configStage = makeConfigStage("jiraRule", registry)

            val enrichmentContext = EnrichmentContext.withMissing(
                data = mapOf(
                    "jira:tickets" to buildJsonObject { put("key", "PROJ-1") },
                    "ci:failed-jobs" to buildJsonObject { put("name", "build") },
                ),
                missingIds = emptySet(),
            )

            moduleInitializer().deliverEnrichmentToRuleModules(configStage, enrichmentContext, emptyMap(), emptyList())

            val received = module.lastReceivedEnrichment
            assertTrue(received != null, "module should have been re-initialized")
            assertTrue(received!!.has("jira:tickets"), "jira: prefix should be delivered")
            assertTrue(!received.has("ci:failed-jobs"), "ci: prefix is not in this module's supportedEnrichment")
        }

    @Test
    fun `module with no supportedEnrichment and no binding is not re-initialized`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val module = RecordingRuleModule(id = "stub-plain-rule", ruleId = "plainRule")
            registry.register(module)
            val configStage = makeConfigStage("plainRule", registry)

            val enrichmentContext = EnrichmentContext.withMissing(
                data = mapOf("jira:tickets" to buildJsonObject { put("key", "PROJ-1") }),
                missingIds = emptySet(),
            )

            moduleInitializer().deliverEnrichmentToRuleModules(configStage, enrichmentContext, emptyMap(), emptyList())

            assertNull(module.lastReceivedEnrichment, "module without enrichment interest must not be re-initialized")
        }

    @Test
    fun `rule-enrichment config binding delivers data even without supportedEnrichment`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val module = RecordingRuleModule(id = "stub-bound-rule", ruleId = "boundRule")
            registry.register(module)
            val bindings = mapOf("boundRule" to RuleEnrichmentConfig(enrich = listOf("ci:*")))
            val configStage = makeConfigStage("boundRule", registry, ruleEnrichment = bindings)

            val enrichmentContext = EnrichmentContext.withMissing(
                data = mapOf(
                    "ci:failed-jobs" to buildJsonObject { put("name", "build") },
                    "jira:tickets" to buildJsonObject { put("key", "PROJ-1") },
                ),
                missingIds = emptySet(),
            )

            moduleInitializer().deliverEnrichmentToRuleModules(configStage, enrichmentContext, bindings, emptyList())

            val received = module.lastReceivedEnrichment
            assertTrue(received != null)
            assertTrue(received!!.has("ci:failed-jobs"), "'ci:*' binding should match ci:failed-jobs")
            assertTrue(!received.has("jira:tickets"), "binding is scoped to 'ci:*' only")
        }

    @Test
    fun `a module whose enrichment matched nothing is initialized once`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val module = RecordingRuleModule(id = "stub-jira-rule", ruleId = "jiraRule", supportedEnrichment = listOf("jira"))
            registry.register(module)
            val configStage = makeConfigStage("jiraRule", registry)
            val initializer = moduleInitializer()
            initializer.initializeConfiguredRuleModules(
                configStage,
                ValidateCommandScope(configStage.config, NoOpLogger, registry, configStage.bootstrap),
                emptyList(),
            )
            val enrichmentContext = EnrichmentContext.withMissing(
                data = mapOf("ci:failed-jobs" to buildJsonObject { put("name", "build") }),
                missingIds = emptySet(),
            )

            initializer.deliverEnrichmentToRuleModules(configStage, enrichmentContext, emptyMap(), emptyList())

            assertEquals(1, module.initializeCalls)
        }

    @Test
    fun `no re-init work happens when enrichment context is entirely empty`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val module = RecordingRuleModule(id = "stub-jira-rule", ruleId = "jiraRule", supportedEnrichment = listOf("jira"))
            registry.register(module)
            val configStage = makeConfigStage("jiraRule", registry)

            moduleInitializer().deliverEnrichmentToRuleModules(configStage, EnrichmentContext.EMPTY, emptyMap(), emptyList())

            assertEquals(null, module.lastReceivedEnrichment)
        }

    /**
     * Enrichment must reach [AiRuleModule][io.karandash.plugins.ai.AiRuleModule], which is never
     * keyed by a rule ID in `rules:` config (its rules are enabled via `ai: features:`). A candidate
     * list built only from `rules:` keys would skip it, so no AI rule would get enrichment data.
     *
     * The stub is registered under [ModuleIds.AI_ANALYSIS] with an empty `rules:` block and a
     * non-null `ai:` config, so it can only be found through the AI-module branch.
     */
    @Test
    fun `AI module is re-initialized via ai config even with empty rules block`() =
        runTest {
            val registry = ModuleRegistryImpl()
            val module = RecordingRuleModule(
                id = ModuleIds.AI_ANALYSIS,
                ruleId = "review-comments",
                supportedEnrichment = listOf("jira", "ci"),
            )
            registry.register(module)

            val bootstrap = PluginBootstrap()
            bootstrap.loadPlugins()
            val configStage = ConfigLoadStage(
                config = Config(
                    rules = RulesConfig(ruleConfigs = emptyMap()),
                    ai = JsonObject(emptyMap()),
                ),
                configFilePath = null,
                usingZeroConfig = false,
                registry = registry,
                bootstrap = bootstrap,
            )

            val enrichmentContext = EnrichmentContext.withMissing(
                data = mapOf(
                    "jira:tickets" to buildJsonObject { put("key", "PROJ-1") },
                    "confluence:pages" to buildJsonObject { put("title", "unrelated") },
                ),
                missingIds = emptySet(),
            )

            moduleInitializer().deliverEnrichmentToRuleModules(configStage, enrichmentContext, emptyMap(), emptyList())

            val received = module.lastReceivedEnrichment
            assertTrue(received != null, "AI module must be re-initialized even though `rules:` is empty")
            assertTrue(received!!.has("jira:tickets"), "'jira' is in supportedEnrichment")
            assertTrue(!received.has("confluence:pages"), "'confluence' is not in supportedEnrichment")
        }
}
