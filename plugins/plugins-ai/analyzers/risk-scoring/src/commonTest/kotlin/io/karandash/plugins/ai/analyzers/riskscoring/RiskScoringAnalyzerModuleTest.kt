package io.karandash.plugins.ai.analyzers.riskscoring

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.ProcessRiskCalculator
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.formatDecimals
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RiskScoringAnalyzerModuleTest {
    private val module = RiskScoringAnalyzerModule()

    private suspend fun analyze(
        score: Double,
        level: String,
        config: JsonElement = JsonObject(emptyMap()),
    ): List<Finding> =
        module.analyze(
            AiAnalyzerContext(
                mr = createTestMergeRequest(title = "Touch auth"),
                aiService = FakeAiService(
                    """{"riskScore":$score,"riskLevel":"$level","confidence":0.8,"factors":["auth","no tests"],"summary":"s"}""",
                ),
                budget = TokenBudget(50_000),
                model = "m",
                config = config,
            ),
        )

    @Test
    fun `high score is an INFO advisory by default with risk metadata`() =
        runTest {
            val finding = analyze(0.8, "high").single()
            assertEquals(Severity.INFO, finding.severity)
            assertTrue(finding.message.startsWith("High risk MR"), finding.message)
            assertEquals("high", finding.metadata[AiFindingMetadataKeys.RISK_LEVEL])
            assertEquals("auth, no tests", finding.metadata[AiFindingMetadataKeys.RISK_FACTORS])
        }

    @Test
    fun `an explicit riskError threshold turns a score at the threshold into an error`() =
        runTest {
            val config = JsonObject(mapOf("thresholds" to JsonObject(mapOf("riskError" to JsonPrimitive(0.7)))))
            assertEquals(Severity.ERROR, analyze(0.7, "high", config).single().severity)
            assertEquals(Severity.INFO, analyze(0.69, "medium", config).single().severity)
        }

    @Test
    fun `medium score is an INFO advisory and low score is silent`() =
        runTest {
            assertEquals(Severity.INFO, analyze(0.4, "medium").single().severity)
            assertTrue(analyze(0.39, "low").isEmpty())
        }

    @Test
    fun `configured thresholds are honoured`() =
        runTest {
            val config = JsonObject(
                mapOf("thresholds" to JsonObject(mapOf("riskError" to JsonPrimitive(0.9), "riskWarning" to JsonPrimitive(0.2)))),
            )
            assertEquals(Severity.INFO, analyze(0.8, "high", config).single().severity)
            assertEquals(Severity.INFO, analyze(0.25, "low", config).single().severity)
        }

    @Test
    fun `malformed response yields a parse error warning`() =
        runTest {
            val finding = module
                .analyze(
                    AiAnalyzerContext(createTestMergeRequest(), FakeAiService("risk: high"), TokenBudget(50_000), "m"),
                ).single()
            assertEquals(Severity.INFO, finding.severity)
        }

    @Test
    fun `riskScoringMaxDiffChars cuts the diff in the risk prompt`() =
        runTest {
            val diff = (1..2_000).joinToString("\n") { "+line $it" }
            val service = FakeAiService("""{"riskScore":0.1,"riskLevel":"low","confidence":0.8,"factors":[],"summary":"s"}""")
            module.analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(title = "Touch auth"),
                    aiService = service,
                    budget = TokenBudget(50_000),
                    model = "m",
                    config = JsonObject(
                        mapOf(
                            "riskScoringIncludeDiff" to JsonPrimitive(true),
                            "limits" to JsonObject(mapOf("riskScoringMaxDiffChars" to JsonPrimitive(100))),
                        ),
                    ),
                    diffContext = PrecomputedDiffContext(preparedDiff = diff),
                ),
            )
            val prompt = service.requests.single().prompt
            assertTrue("+line 1" in prompt, "diff start missing")
            assertTrue("+line 2000" !in prompt, "diff not cut at riskScoringMaxDiffChars")
        }

    @Test
    fun `an included diff reaches the risk prompt whole without a cap`() =
        runTest {
            val diff = (1..2_000).joinToString("\n") { "+line $it" }
            val service = FakeAiService("""{"riskScore":0.1,"riskLevel":"low","confidence":0.8,"factors":[],"summary":"s"}""")
            module.analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(title = "Touch auth"),
                    aiService = service,
                    budget = TokenBudget(50_000),
                    model = "m",
                    config = JsonObject(mapOf("riskScoringIncludeDiff" to JsonPrimitive(true))),
                    diffContext = PrecomputedDiffContext(preparedDiff = diff),
                ),
            )
            assertTrue("+line 2000" in service.requests.single().prompt, "diff cut without a cap")
        }

    @Test
    fun `without include diff the risk prompt carries no diff`() =
        runTest {
            val service = FakeAiService("""{"riskScore":0.1,"riskLevel":"low","confidence":0.8,"factors":[],"summary":"s"}""")
            module.analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(title = "Touch auth"),
                    aiService = service,
                    budget = TokenBudget(50_000),
                    model = "m",
                    diffContext = PrecomputedDiffContext(preparedDiff = "+secret line"),
                ),
            )
            assertTrue("+secret line" !in service.requests.single().prompt)
        }

    @Test
    fun `score is rounded to two decimals not truncated by float error`() =
        runTest {
            // 0.58 * 100 == 57.99999999999999 in IEEE 754, so truncation printed 0.57
            val message = analyze(0.58, "medium").single().message
            assertTrue("score: 0.58" in message, message)
        }

    @Test
    fun `riskScorerMaxTokens caps the response and module metadata describes a default analyzer`() =
        runTest {
            val service = FakeAiService("""{"riskScore":0.1,"riskLevel":"low","confidence":0.8,"factors":[],"summary":"s"}""")
            val config = JsonObject(mapOf("limits" to JsonObject(mapOf("riskScorerMaxTokens" to JsonPrimitive(321)))))
            module.analyze(
                AiAnalyzerContext(
                    mr = createTestMergeRequest(title = "Touch auth"),
                    aiService = service,
                    budget = TokenBudget(50_000),
                    model = "m",
                    config = config,
                ),
            )

            assertEquals(321, service.requests.single().maxTokens)
            assertEquals("riskScoring", module.featureKey)
            assertTrue(module.defaultEnabled)
            assertTrue(module.supportsSemiFormalReasoning)
            assertTrue(module.explanation.isNotBlank())
            assertTrue(module.initialize(TestPluginContext()))
        }

    @Test
    fun `prompt and finding carry the shared measured process risk`() =
        runTest {
            val service = FakeAiService("""{"riskScore":0.8,"riskLevel":"high","confidence":0.8,"factors":["auth"],"summary":"s"}""")
            val ctx = AiAnalyzerContext(createTestMergeRequest(title = "Touch auth"), service, TokenBudget(50_000), "m")
            val findings = module.analyze(ctx)
            val finding = findings.first()
            val expected = ProcessRiskCalculator.calculate(ctx.mr).composite.formatDecimals(2)
            assertEquals(expected, finding.metadata[AiFindingMetadataKeys.PROCESS_RISK])
            val prompt = service.requests.single().prompt
            assertTrue(prompt.contains("## Measured Process Risk") && prompt.contains("Measured process risk: $expected"), prompt)
        }
}
