@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.conditions.DefaultPolicyEngine
import io.karandash.config.Config
import io.karandash.config.PolicyConfig
import io.karandash.config.RulesConfig
import io.karandash.config.getRuleConfigElements
import io.karandash.duration.KarandashDuration
import io.karandash.engine.PolicyOptions
import io.karandash.engine.ValidationEngine
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.WellKnownMetadataKeys
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.plugins.ai.providers.OpenAiProvider
import io.karandash.testfixtures.createTestMergeRequest
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Code-suggestions is its own rule on the live [AiRuleModule], so a `rule-enrichment:` binding
 * and a `policies.modules` entry naming it act on the code-suggestions analyzer, while a `code-suggestions` block
 * in `mode: inline` still gives review-comments its suggestion prompt.
 */
class AiCodeSuggestionsLiveModuleTest {
    private val calls = mutableListOf<Pair<String, JsonElement>>()

    private val mr = createTestMergeRequest(
        sourceBranch = "feature/x",
        changes = listOf(FileChange("A.kt", ChangeType.MODIFIED, 1, 0, "@@ -1 +1 @@\n-a\n+b\n")),
    )

    @Test
    fun `a rule-enrichment binding for code-suggestions reaches the code-suggestions analyzer`() =
        runTest {
            val enrichment = EnrichmentContext(
                data = mapOf("ci:pipeline" to JsonPrimitive("ci"), "gitlab:mr" to JsonPrimitive("mr")),
            )
            val module = AiRuleModule(registry())
            assertTrue(module.initialize(FakeContext(config(), enrichment), mapOf("code-suggestions" to listOf("ci:"))))

            assertTrue("code-suggestions" in module.contributedRules())
            val findings = module.validate("code-suggestions", mr, JsonObject(emptyMap()))
            module.shutdown()

            assertEquals(listOf("code-suggestions"), calls.map { it.first })
            assertEquals("`code-suggestions` on m; context: ci:pipeline", findings.single().metadata[WellKnownMetadataKeys.WHY])
        }

    @Test
    fun `a policy naming code-suggestions switches off the code-suggestions analyzer only`() =
        runTest {
            val module = AiRuleModule(registry())
            assertTrue(module.initialize(FakeContext(config())))
            val engineRegistry = ModuleRegistryImpl().also { it.register(module) }
            val engine = ValidationEngine(
                config = Config(
                    rules = RulesConfig(
                        ruleConfigs = mapOf("codeSuggestions" to JsonObject(emptyMap()), "reviewComments" to JsonObject(emptyMap())),
                    ),
                ),
                registry = engineRegistry,
                configExtractor = { cfg, ruleId -> cfg.rules.getRuleConfigElements(ruleId) },
                enabledModules = mapOf("codeSuggestions" to true, "reviewComments" to true),
                policy = PolicyOptions(
                    engine = DefaultPolicyEngine(),
                    policies = listOf(
                        PolicyConfig(condition = "branch_matches(\"^feature/\")", modules = listOf("code-suggestions"), enabled = false),
                    ),
                ),
            )

            engine.validate(mr)
            module.shutdown()

            assertEquals(listOf("review-comments"), calls.map { it.first })
        }

    @Test
    fun `an inline code-suggestions block keeps the suggestion prompt in review-comments`() =
        runTest {
            val module = AiRuleModule(registry())
            val cfg = config(extra = mapOf("codeSuggestions" to JsonObject(mapOf("mode" to JsonPrimitive("inline")))))
            assertTrue(module.initialize(FakeContext(cfg)))

            module.validate("review-comments", mr, JsonObject(emptyMap()))
            module.validate("code-suggestions", mr, JsonObject(emptyMap()))
            module.shutdown()

            assertEquals(listOf("review-comments", "code-suggestions"), calls.map { it.first })
            assertTrue(AiRuleConfig.fromPluginConfig(calls.first().second).includeFixSuggestions)
        }

    private fun config(extra: Map<String, JsonElement> = emptyMap()): JsonElement =
        JsonObject(
            mapOf(
                "provider" to JsonPrimitive("openai"),
                "apiKey" to JsonPrimitive("k"),
                "baseUrl" to JsonPrimitive("https://ai.test/v1"),
                "model" to JsonPrimitive("m"),
                "reviewSampling" to JsonObject(mapOf("samples" to JsonPrimitive(1))),
                "features" to JsonObject(
                    mapOf("codeSuggestions" to JsonPrimitive(true), "reviewComments" to JsonPrimitive(true)),
                ),
            ) + extra,
        )

    private suspend fun registry(): ModuleRegistry {
        val provider = OpenAiProvider(
            apiKey = null,
            baseUrl = "https://ai.test/v1",
            timeout = KarandashDuration.ofSeconds(30),
            maxRetries = 0,
            httpClientEngine = MockEngine { _ -> respondError(HttpStatusCode.InternalServerError) },
        )
        val base = ModuleRegistryImpl().also { it.register(provider) }
        val analyzers = listOf(recording("review-comments", "reviewComments"), recording("code-suggestions", "codeSuggestions"))
        return object : ModuleRegistry by base {
            override fun aiAnalyzers() = analyzers

            override fun findAiAnalyzer(analyzerId: String) = analyzers.find { it.analyzerId == analyzerId }
        }
    }

    private fun recording(
        analyzerIdValue: String,
        featureKeyValue: String,
    ): AiAnalyzerModule =
        object : AiAnalyzerModule {
            override val id: String = analyzerIdValue
            override val name: String = analyzerIdValue
            override val version: String = "test"
            override val type: ModuleType get() = ModuleType.AI_ANALYZER
            override val analyzerId: String = analyzerIdValue
            override val featureKey: String = featureKeyValue
            override val defaultEnabled: Boolean = false
            override val needsScopeDetection: Boolean = false

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
                calls += analyzerIdValue to context.config
                return listOf(Finding(severity = Severity.WARNING, message = "from $analyzerIdValue", rule = analyzerIdValue))
            }
        }

    private class FakeContext(
        override val pluginConfig: JsonElement,
        override val enrichment: EnrichmentContext = EnrichmentContext.EMPTY,
    ) : PluginContext {
        override fun env(name: String): String? = null

        override fun log(message: String) = Unit

        override fun warn(message: String) = Unit

        override val hostVersion: String = "1.0.0-test"
        override val workingDirectory: String = "/tmp/test"
    }
}
