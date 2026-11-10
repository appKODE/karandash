@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.module.ExtensionStateStore
import io.karandash.api.module.PipelineEnvironment
import io.karandash.duration.KarandashDuration
import io.karandash.logger.NoOpLogger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.RuleGate
import io.karandash.plugins.ai.core.config.CognitiveComplexityConfig
import io.karandash.plugins.ai.core.config.TrackConfig
import io.karandash.plugins.ai.core.config.ViolationBaselineConfig
import io.karandash.plugins.ai.dispatch.AiSkipEvaluator
import io.karandash.plugins.ai.dispatch.SkipResult
import io.karandash.plugins.ai.pipeline.AiDescriptionPolicies
import io.karandash.plugins.ai.pipeline.AiReportGenerator
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock

/** Behaviour of the process risk, temporal, track, baseline and complexity keys of the `ai` block. */
class AiPipelineExtensionKeyBehaviourTest {
    private class MemoryStore : ExtensionStateStore {
        val entries = mutableMapOf<String, ByteArray>()
        override val ttl: KarandashDuration = KarandashDuration.ofSeconds(60)

        override fun get(key: String): ByteArray? = entries[key]

        override fun put(
            key: String,
            content: ByteArray,
        ) {
            entries[key] = content
        }

        override fun invalidate(key: String) {
            entries.remove(key)
        }

        override fun keys(): List<String> = entries.keys.toList()
    }

    private val riskMr = createTestMergeRequest(
        changes = listOf(
            FileChange("a/A.kt", ChangeType.MODIFIED, additions = 10, deletions = 10),
            FileChange("b/B.kt", ChangeType.MODIFIED, additions = 10, deletions = 10),
        ),
    )

    private suspend fun configured(
        config: String,
        store: MemoryStore? = null,
        aiService: AiService? = null,
    ): AiPipelineExtension {
        val ext = AiPipelineExtension(buildTestRegistry())
        store?.let { ext.configureStateStore(it) }
        ext.onConfigured(
            PipelineEnvironment(
                aiService = aiService,
                rawConfig = KarandashJson.Default.parseToJsonElement(config),
                statsDir = TestFiles.createTestTempDirectory("karandash-key-stats").toString(),
            ),
        )
        return ext
    }

    private suspend fun riskFinding(config: String): Finding? =
        configured(config).let { ext ->
            ext.onPostFetch(riskMr, null)
            ext.beforeReporting(emptyList(), riskMr).firstOrNull { it.rule == ProcessRiskAssessment.RULE }
        }

    @Test
    fun `ai processRisk enabled false switches the assessment off`() =
        runTest {
            assertNotNull(riskFinding("""{"ai": {"processRisk": {"enabled": true}}}"""))
            assertNull(riskFinding("""{"ai": {"processRisk": {"enabled": false}}}"""))
        }

    @Test
    fun `ai processRisk factors changeChurn changeDensity and fileDiversity each drop out of the breakdown`() =
        runTest {
            for (factor in listOf("changeChurn", "changeDensity", "fileDiversity")) {
                val on = riskFinding("""{"ai": {"processRisk": {"factors": {"$factor": true}}}}""")
                val off = riskFinding("""{"ai": {"processRisk": {"factors": {"$factor": false}}}}""")
                assertNotNull(on?.metadata?.get(factor), factor)
                assertNull(off?.metadata?.get(factor), factor)
            }
        }

    private suspend fun thirdRunSeverity(config: String): Severity {
        val store = MemoryStore()
        val mr = createTestMergeRequest()
        val parsed = KarandashJson.Default.parseToJsonElement(config)
        repeat(2) {
            configured(config, store).afterValidation(listOf(Finding(Severity.WARNING, "m", "r1")), mr, parsed)
        }
        return configured(config, store).afterValidation(listOf(Finding(Severity.INFO, "m", "r1")), mr, parsed).single().severity
    }

    @Test
    fun `ai temporal enabled turns on escalation of repeat violations`() =
        runTest {
            assertEquals(Severity.WARNING, thirdRunSeverity("""{"ai": {"temporal": {"enabled": true}}}"""))
            assertEquals(Severity.INFO, thirdRunSeverity("""{"ai": {"temporal": {"enabled": false}}}"""))
        }

    @Test
    fun `ai pipeline temporalEscalation turns on escalation of repeat violations`() =
        runTest {
            assertEquals(Severity.WARNING, thirdRunSeverity("""{"ai": {"pipeline": {"temporalEscalation": true}}}"""))
            assertEquals(Severity.INFO, thirdRunSeverity("""{"ai": {"pipeline": {"temporalEscalation": false}}}"""))
        }

    @Test
    fun `a finding below minConfidence never reaches the verifier`() =
        runTest {
            val prompts = mutableListOf<String>()
            val service = object : AiService {
                override val isAvailable = true

                override suspend fun complete(
                    request: AiCompletionRequest,
                    providerId: String?,
                ): AiCompletionResponse {
                    prompts += request.prompt
                    return AiCompletionResponse("""{"verdicts":[]}""", 1, 1, 2, request.model)
                }
            }
            val config = """{"ai": {"model": "m", "filter": {"minConfidence": 0.5}, "grounding": {"verifier": {"enabled": true}}}}"""
            val mr = createTestMergeRequest(
                changes = listOf(FileChange("A.kt", ChangeType.MODIFIED, diffContent = "@@ -0,0 +1,2 @@\n+val a = 1\n+val b = 2")),
            )

            fun aiFinding(
                message: String,
                confidence: String,
                line: Int,
            ) = Finding(
                Severity.WARNING,
                message,
                "ai-review",
                location = Location.FileRef("A.kt", line),
                metadata = mapOf(AiFindingMetadataKeys.CONFIDENCE to confidence),
            )
            val ext = configured(config, aiService = service)

            val result = ext.afterValidation(
                listOf(aiFinding("unsure guess", "0.1", 1), aiFinding("solid catch", "0.9", 2)),
                mr,
                KarandashJson.Default.parseToJsonElement(config),
            )

            assertEquals(listOf("solid catch"), result.filter { it.rule == "ai-review" }.map { it.message })
            assertTrue(prompts.isNotEmpty(), "the verifier was not asked")
            assertTrue(prompts.none { "unsure guess" in it }, "the filtered finding reached the verifier")
        }

    @Test
    fun `ai tracks aiEnabled false skips the AI rule on the matching branch`() {
        val evaluator = AiSkipEvaluator(logger = NoOpLogger, ruleGate = RuleGate())

        fun skipped(aiEnabled: Boolean) =
            evaluator.evaluateEnvironmentTrack(
                "ai-review",
                "main",
                listOf(TrackConfig(name = "prod", targetBranchPattern = "^main$", aiEnabled = aiEnabled)),
            ) is SkipResult.Skip

        assertTrue(skipped(false))
        assertEquals(false, skipped(true))
    }

    @Test
    fun `ai violationBudget enabled and baselineFile decide whether baselined files are downgraded`() {
        val file = Path(TestFiles.createTestTempDirectory("karandash-baseline-key"), "baseline.json")
        SystemFileSystem.sink(file).buffered().use { it.writeString("""{"src/A.kt": 5}""") }
        val policies = AiDescriptionPolicies(NoOpLogger) { Clock.System.now() }
        val findings = listOf(Finding(Severity.ERROR, "m", "r", location = Location.FileRef("src/A.kt", 1)))

        val enabled = policies.applyBaselineBudget(findings, ViolationBaselineConfig(baselineFile = file.toString()))
        val disabled = policies.applyBaselineBudget(findings, ViolationBaselineConfig(enabled = false, baselineFile = file.toString()))

        assertEquals(Severity.INFO, enabled.single().severity)
        assertEquals(Severity.ERROR, disabled.single().severity)
    }

    private fun complexityReport(weights: CognitiveComplexityConfig): String? {
        val mr = createTestMergeRequest(
            changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED, diffContent = "+if (a && b) {\n")),
        )
        val config = JsonObject(
            mapOf(
                "ai" to JsonObject(
                    mapOf(
                        "research" to JsonObject(
                            mapOf(
                                "cognitiveComplexity" to JsonObject(
                                    mapOf(
                                        "nestingWeight" to JsonPrimitive(weights.nestingWeight),
                                        "controlFlowWeight" to JsonPrimitive(weights.controlFlowWeight),
                                    ),
                                ),
                            ),
                        ),
                    ),
                ),
            ),
        )
        return AiReportGenerator(
            io.karandash.plugins.ai.core.config
                .AiPipelineConfig(),
            NoOpLogger
        ).generate(emptyList(), mr, config, "mr-1", setOf("complexity-delta"))["complexity-delta"]
    }

    @Test
    fun `ai research cognitiveComplexity nestingWeight and controlFlowWeight change the reported delta`() {
        // control flow 1 + nesting 1 * nestingWeight + boolean 1
        assertEquals("Cognitive complexity increased by 7 across 1 file(s)", complexityReport(CognitiveComplexityConfig(nestingWeight = 5)))
        assertEquals(
            "Cognitive complexity increased by 6 across 1 file(s)",
            complexityReport(CognitiveComplexityConfig(controlFlowWeight = 3))
        )
    }
}
