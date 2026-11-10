@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.module.PipelineEnvironment
import io.karandash.api.support.mapToJsonElement
import io.karandash.logger.NoOpLogger
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiSkipPolicy
import io.karandash.plugins.ai.dispatch.AiSkipEvaluator
import io.karandash.plugins.ai.dispatch.SkipEvalInput
import io.karandash.plugins.ai.dispatch.SkipResult
import io.karandash.plugins.ai.init.AiRuleInitializer
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every execution profile field takes effect, and its rule lists reach the deterministic rules. */
class AiExecutionProfileTest {
    private suspend fun enabledRules(
        extra: Map<String, Any?>,
        warnings: MutableList<String> = mutableListOf(),
        logs: MutableList<String> = mutableListOf(),
    ): Set<String>? {
        val context = object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model") + extra,
            )
            override val enrichment: EnrichmentContext = EnrichmentContext.EMPTY

            override fun env(name: String): String? = null

            override fun log(message: String) {
                logs += message
            }

            override fun warn(message: String) {
                warnings += message
            }

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }
        return AiRuleInitializer(buildTestRegistry(), null).initialize(context)?.enabledRules
    }

    @Test
    fun `the ci profile runs no AI analyzers`() =
        runTest {
            // No analyzer left means the AI module does not start, as with every feature off.
            assertNull(enabledRules(mapOf("executionProfile" to "ci")))
        }

    @Test
    fun `aiEnabled false on a custom profile runs no analyzers while true keeps them`() =
        runTest {
            val logs = mutableListOf<String>()
            val off = mapOf("custom" to mapOf("aiEnabled" to false))
            val on = mapOf("custom" to mapOf("aiEnabled" to true))

            assertNull(enabledRules(mapOf("executionProfile" to "custom", "executionProfiles" to off), logs = logs))
            assertTrue(logs.any { "'custom' turns AI off" in it }, logs.toString())
            val kept = enabledRules(mapOf("executionProfile" to "custom", "executionProfiles" to on))
            assertTrue(!kept.isNullOrEmpty(), "aiEnabled true keeps the analyzers: $kept")
        }

    @Test
    fun `a profile description is logged when the profile applies`() =
        runTest {
            val logs = mutableListOf<String>()
            val profiles = mapOf("fast" to mapOf("description" to "quick local pass"))

            enabledRules(mapOf("executionProfile" to "fast", "executionProfiles" to profiles), logs = logs)

            assertTrue(logs.any { "'fast': quick local pass" in it }, logs.toString())
        }

    @Test
    fun `a custom profile warns that exitOnError and verbose have no effect`() =
        runTest {
            val warnings = mutableListOf<String>()
            val profiles = mapOf("fast" to mapOf("exitOnError" to false, "verbose" to true))

            enabledRules(mapOf("executionProfile" to "fast", "executionProfiles" to profiles), warnings)

            assertTrue(warnings.any { "exitOnError" in it && "verbose" in it && "fast" in it }, warnings.toString())
        }

    @Test
    fun `a rule that starts after the profile totalTimeout is skipped`() {
        val input = SkipEvalInput(
            gates = emptyList(),
            costBudget = null,
            tracks = emptyList(),
            canary = emptyMap(),
            skipPolicy = AiSkipPolicy(),
            profileTimedOut = true,
        )

        val result = AiSkipEvaluator(
            NoOpLogger,
            RuleGate()
        ).evaluateSkipConditions("risk-scoring", createTestMergeRequest(), input, TokenBudget(100))

        assertIs<SkipResult.Skip>(result)
        assertTrue("totalTimeout" in result.reason, result.reason)
    }

    @Test
    fun `a profile include list filters the findings of configured deterministic rules`() =
        runTest {
            val config = KarandashJson.Default.parseToJsonElement(
                """{"rules":{"titleFormat":{},"labels":{}},"ai":{"executionProfile":"pre-push"}}""",
            )
            val extension = AiPipelineExtension(buildTestRegistry())
            extension.onConfigured(
                PipelineEnvironment(
                    aiService = null,
                    rawConfig = config,
                    statsDir = TestFiles.createTestTempDirectory("ai-profile-stats").toString()
                )
            )
            val findings = listOf("titleFormat", "labels", "engine").map { Finding(severity = Severity.ERROR, message = "m", rule = it) }

            val kept = extension.afterValidation(findings, createTestMergeRequest(), config).map { it.rule }

            assertEquals(listOf("titleFormat", "engine"), kept)
        }

    @Test
    fun `excludedRules reads only the profile keys so a bad ai value elsewhere does not fail config load`() {
        val extension = AiPipelineExtension(buildTestRegistry())
        val config = KarandashJson.Default.parseToJsonElement(
            """{"ai":{"strictness":{},"teams":{},"executionProfile":"quick",""" +
                """"executionProfiles":{"quick":{"excludeRules":["labels"]}}}}""",
        )

        assertEquals(setOf("labels"), extension.excludedRules(config, setOf("titleFormat", "labels")))
    }

    @Test
    fun `a malformed execution profile excludes nothing instead of throwing`() {
        val extension = AiPipelineExtension(buildTestRegistry())
        val config = KarandashJson.Default.parseToJsonElement("""{"ai":{"executionProfile":{}}}""")

        assertEquals(emptySet(), extension.excludedRules(config, setOf("titleFormat")))
    }
}
