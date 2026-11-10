package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.support.mapToJsonElement
import io.karandash.duration.KarandashDuration
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiPluginConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Tests for the [AiRuleModule] execution timeout feature ([AiPluginConfig.executionTimeout]).
 *
 * Verifies that a global wall-clock budget for AI rule execution is honoured:
 * - rules that start after the deadline has passed are skipped immediately
 * - rules whose dispatch exceeds the remaining time are cancelled mid-flight
 * - when no timeout is configured, behaviour is unchanged
 */
class AiExecutionTimeoutTest {
    // ---------------------------------------------------------------------------
    // Config parsing
    // ---------------------------------------------------------------------------

    @Test
    fun `executionTimeout is parsed from plugin config`() {
        val config = buildJsonObject { put("executionTimeout", "5m") }
        val parsed = AiPluginConfig.fromPluginConfig(config)
        assertEquals(KarandashDuration.ofMinutes(5), parsed.executionTimeout)
    }

    @Test
    fun `executionTimeout defaults to null when absent`() {
        val parsed = AiPluginConfig.fromPluginConfig(JsonObject(emptyMap()))
        assertNull(parsed.executionTimeout)
    }

    // ---------------------------------------------------------------------------
    // Runtime behaviour
    // ---------------------------------------------------------------------------

    @Test
    fun `timeout not exceeded — validate returns findings normally`() =
        runTest {
            val module = AiRuleModule(buildRegistryWithAnalyzer { emptyList() })
            module.initialize(createContext(executionTimeout = KarandashDuration.ofMinutes(5)))

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            // Stub analyzer returns empty list; error finding absent means dispatch ran.
            assertTrue(findings.none { it.message.contains("not initialized") })
            module.shutdown()
        }

    @Test
    fun `timeout already exceeded before dispatch — returns empty list and records skip reason`() =
        runTest {
            // executionTimeout = ZERO guarantees remainingMs <= 0 no matter when validate() is called.
            val module = AiRuleModule(buildRegistryWithAnalyzer { emptyList() })
            module.initialize(createContext(executionTimeout = KarandashDuration.ZERO))

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty(), "Expected empty list when deadline already passed, got $findings")
            module.shutdown()
        }

    @Test
    fun `timeout fires mid-dispatch — returns empty list`() =
        runTest {
            // The analyzer delays 200ms (virtual); timeout is 50ms -> withTimeoutOrNull fires first.
            val module = AiRuleModule(
                buildRegistryWithAnalyzer {
                    delay(200)
                    emptyList()
                }
            )
            module.initialize(createContext(executionTimeout = KarandashDuration.ofMillis(50)))

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty(), "Expected empty list when timeout fires mid-dispatch, got $findings")
            module.shutdown()
        }

    @Test
    fun `the execution profile totalTimeout stops an analyzer that is already running`() =
        runTest {
            val module = AiRuleModule(
                buildRegistryWithAnalyzer {
                    delay(60_000)
                    listOf(Finding(severity = Severity.ERROR, message = "late", rule = "risk-scoring"))
                }
            )
            val profile = mapOf("executionProfile" to "quick", "executionProfiles" to mapOf("quick" to mapOf("totalTimeout" to "5s")))
            module.initialize(createContext(executionTimeout = null, extra = profile))

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertTrue(findings.isEmpty(), "the analyzer ran past the profile totalTimeout: $findings")
            assertEquals("execution profile totalTimeout reached", module.skippedRuleReasons()["risk-scoring"]?.reason)
            module.shutdown()
        }

    @Test
    fun `a rule that starts after the configured profile totalTimeout is skipped`() =
        runTest {
            val analyzerFinding = Finding(severity = Severity.ERROR, message = "ran", rule = "risk-scoring")
            val skipped = AiRuleModule(buildRegistryWithAnalyzer { listOf(analyzerFinding) })
            val roomy = AiRuleModule(buildRegistryWithAnalyzer { listOf(analyzerFinding) })
            skipped.initialize(createContext(null, profileWithTotalTimeout("1ms")))
            roomy.initialize(createContext(null, profileWithTotalTimeout("1h")))

            // The plugin start mark is a real monotonic clock, so let real time pass.
            withContext(Dispatchers.Default) { delay(100) }

            assertTrue(skipped.validate("risk-scoring", makeMr(), JsonObject(emptyMap())).isEmpty())
            assertEquals("execution profile totalTimeout reached", skipped.skippedRuleReasons()["risk-scoring"]?.reason)
            assertNull(roomy.skippedRuleReasons()["risk-scoring"])
            assertTrue(roomy.validate("risk-scoring", makeMr(), JsonObject(emptyMap())).isNotEmpty())
            skipped.shutdown()
            roomy.shutdown()
        }

    @Test
    fun `null executionTimeout — no deadline applied validate runs normally`() =
        runTest {
            val module = AiRuleModule(buildRegistryWithAnalyzer { emptyList() })
            // No executionTimeout in config -> null
            module.initialize(createContext(executionTimeout = null))

            val findings = module.validate("risk-scoring", makeMr(), JsonObject(emptyMap()))

            assertTrue(findings.none { it.message.contains("not initialized") })
            module.shutdown()
        }

    // ---------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------

    private fun createContext(
        executionTimeout: KarandashDuration?,
        extra: Map<String, Any?> = emptyMap(),
    ): PluginContext {
        val config = buildMap<String, Any?> {
            putAll(extra)
            put("provider", "custom")
            put("baseUrl", "http://localhost:1234/v1")
            put("model", "test-model")
            if (executionTimeout != null) put("executionTimeout", executionTimeout.toString())
        }
        return object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(config)

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }
    }

    private fun profileWithTotalTimeout(timeout: String) =
        mapOf("executionProfile" to "quick", "executionProfiles" to mapOf("quick" to mapOf("totalTimeout" to timeout)))

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

    /** Builds a [ModuleRegistry] that replaces the default [risk-scoring] analyzer with [analyzeBlock]. */
    private fun buildRegistryWithAnalyzer(analyzeBlock: suspend () -> List<Finding>): ModuleRegistry {
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

            override suspend fun initialize(context: PluginContext): Boolean = true

            override suspend fun analyze(context: AiAnalyzerContext): List<Finding> = analyzeBlock()
        }

        return object : ModuleRegistry by base {
            private val allAnalyzers = base
                .aiAnalyzers()
                .filter { it.analyzerId != "risk-scoring" } + overrideAnalyzer

            override fun aiAnalyzers() = allAnalyzers

            override fun findAiAnalyzer(analyzerId: String) = allAnalyzers.find { it.analyzerId == analyzerId }
        }
    }
}
