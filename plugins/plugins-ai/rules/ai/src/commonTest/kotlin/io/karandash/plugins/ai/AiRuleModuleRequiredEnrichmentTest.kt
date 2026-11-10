package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.finding.SkipCategory
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies [AiAnalyzerModule.requiredEnrichment] is actually enforced by [AiRuleModule], the
 * only place with per-rule visibility into which analyzer handles which rule ID.
 *
 * [io.karandash.engine.ValidationEngine.filterByRequiredEnrichment] cannot do this itself: it
 * checks `registry.findRuleHandler(ruleId).requiredEnrichment`, but every AI-dispatched rule
 * ID resolves to the same [AiRuleModule] instance, whose own `requiredEnrichment` is always the
 * SDK default (`emptyList()`) -- so the engine's gate silently never fires for any AI rule.
 */
class AiRuleModuleRequiredEnrichmentTest {
    private fun createContext(
        pluginConfig: JsonElement,
        enrichmentContext: EnrichmentContext,
    ): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = pluginConfig
            override val enrichment: EnrichmentContext = enrichmentContext

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private fun aiBlockConfig(): JsonElement =
        mapToJsonElement(
            mapOf(
                "provider" to "custom",
                "baseUrl" to "http://localhost:1234/v1",
                "model" to "test-model",
            ),
        )

    private fun makeMr() =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test PR",
            description = "Some description",
            sourceBranch = "feature/test",
            targetBranch = "main",
            author = User("u1", "dev", "Developer"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(
                Commit("sha1", "Test commit", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")
            ),
            changes = listOf(FileChange("src/Main.kt", ChangeType.MODIFIED, additions = 1, deletions = 0)),
        )

    /** Replaces the default `risk-scoring` analyzer with one declaring the given enrichment interest. */
    private fun buildRegistryWithAnalyzer(
        supportedEnrichment: List<String> = emptyList(),
        requiredEnrichment: List<String> = emptyList(),
    ): ModuleRegistry {
        val base = buildTestRegistry()
        val overrideAnalyzer = object : AiAnalyzerModule {
            override val id: String = "risk-scoring"
            override val name: String = "risk-scoring"
            override val version: String = "test"
            override val type: ModuleType get() = ModuleType.AI_ANALYZER
            override val analyzerId: String = "risk-scoring"
            override val featureKey: String = "riskScoring"
            override val defaultEnabled: Boolean = true
            override val estimatedTokens: Int = 1500
            override val needsScopeDetection: Boolean = false
            override val supportedEnrichment: List<String> = supportedEnrichment
            override val requiredEnrichment: List<String> = requiredEnrichment

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun analyze(context: AiAnalyzerContext): List<Finding> =
                listOf(Finding(severity = Severity.INFO, message = "analyzer ran", rule = "risk-scoring"))
        }

        return object : ModuleRegistry by base {
            private val allAnalyzers = base
                .aiAnalyzers()
                .filter { it.analyzerId != "risk-scoring" } + overrideAnalyzer

            override fun aiAnalyzers() = allAnalyzers

            override fun findAiAnalyzer(analyzerId: String) = allAnalyzers.find { it.analyzerId == analyzerId }
        }
    }

    @Test
    fun `required enrichment present via analyzer's own supportedEnrichment -- analyzer runs`() =
        runTest {
            val registry = buildRegistryWithAnalyzer(
                supportedEnrichment = listOf("jira"),
                requiredEnrichment = listOf("jira"),
            )
            val module = AiRuleModule(registry)
            val context = createContext(
                pluginConfig = aiBlockConfig(),
                enrichmentContext = EnrichmentContext(data = mapOf("jira:tickets" to JsonPrimitive("stub"))),
            )
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), aiBlockConfig())

            assertEquals(listOf("analyzer ran"), findings.map { it.message })
            module.shutdown()
        }

    @Test
    fun `required enrichment absent -- rule is skipped as MISSING_REQUIRED_ENRICHMENT`() =
        runTest {
            val registry = buildRegistryWithAnalyzer(
                supportedEnrichment = listOf("jira"),
                requiredEnrichment = listOf("jira"),
            )
            val module = AiRuleModule(registry)
            val context = createContext(pluginConfig = aiBlockConfig(), enrichmentContext = EnrichmentContext.EMPTY)
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), aiBlockConfig())

            assertTrue(findings.isEmpty())
            val skipInfo = module.skippedRuleReasons()["risk-scoring"]
            assertEquals(SkipCategory.MISSING_REQUIRED_ENRICHMENT, skipInfo?.category)
            assertTrue(skipInfo?.reason.orEmpty().contains("jira"))
            module.shutdown()
        }

    @Test
    fun `empty requiredEnrichment -- default never blocks dispatch`() =
        runTest {
            val registry = buildRegistryWithAnalyzer()
            val module = AiRuleModule(registry)
            val context = createContext(pluginConfig = aiBlockConfig(), enrichmentContext = EnrichmentContext.EMPTY)
            module.initialize(context)

            val findings = module.validate("risk-scoring", makeMr(), aiBlockConfig())

            assertEquals(listOf("analyzer ran"), findings.map { it.message })
            module.shutdown()
        }

    @Test
    fun `required enrichment satisfied via rule-enrichment config binding rather than analyzer's own declaration`() =
        runTest {
            // supportedEnrichment deliberately empty -- only a per-rule config binding grants "confluence".
            val registry = buildRegistryWithAnalyzer(
                supportedEnrichment = emptyList(),
                requiredEnrichment = listOf("confluence"),
            )
            val bindings = mapOf("risk-scoring" to listOf("confluence:"))
            val module = AiRuleModule(registry)
            val config = aiBlockConfig()
            val context = createContext(
                pluginConfig = config,
                enrichmentContext = EnrichmentContext(data = mapOf("confluence:page" to JsonPrimitive("stub"))),
            )
            module.initialize(context, bindings)

            val findings = module.validate("risk-scoring", makeMr(), config)

            assertEquals(listOf("analyzer ran"), findings.map { it.message })
            module.shutdown()
        }
}
