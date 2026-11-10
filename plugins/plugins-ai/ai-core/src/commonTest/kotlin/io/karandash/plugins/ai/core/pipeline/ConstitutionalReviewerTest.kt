package io.karandash.plugins.ai.core.pipeline

import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiProviderException
import io.karandash.api.ai.AiService
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConstitutionalReviewerTest {
    private val testModel = "gpt-4o-mini"
    private val principles = listOf(
        "Never comment on code formatting",
        "Only flag high-confidence issues",
    )

    private fun mockAiService(responseContent: String): AiService =
        object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse =
                AiCompletionResponse(
                    content = responseContent,
                    promptTokens = 100,
                    completionTokens = 100,
                    totalTokens = 200,
                    model = testModel,
                )
        }

    private fun failingAiService(): AiService =
        object : AiService {
            override val isAvailable = true

            override suspend fun complete(
                request: AiCompletionRequest,
                providerId: String?,
            ): AiCompletionResponse = throw AiProviderException("API error", statusCode = 500, retryable = true)
        }

    private fun aiFinding(
        rule: String = "ai-review",
        severity: Severity = Severity.WARNING,
        message: String = "AI finding",
    ) = Finding(severity = severity, message = message, rule = rule)

    private fun nonAiFinding(
        rule: String = "title-format",
        message: String = "Non-AI finding",
    ) = Finding(severity = Severity.WARNING, message = message, rule = rule)

    // --- Empty / no-op paths ---

    @Test
    fun `empty principles returns findings unchanged`() =
        runTest {
            val findings = listOf(aiFinding())
            val reviewer = ConstitutionalReviewer(failingAiService(), emptyList(), testModel)

            val result = reviewer.review(findings)

            assertEquals(findings, result.findings)
            assertEquals(0, result.removedCount)
            assertEquals(0, result.totalReviewed)
        }

    @Test
    fun `empty findings returns empty result`() =
        runTest {
            val reviewer = ConstitutionalReviewer(failingAiService(), principles, testModel)

            val result = reviewer.review(emptyList())

            assertTrue(result.findings.isEmpty())
            assertEquals(0, result.removedCount)
            assertEquals(0, result.totalReviewed)
        }

    @Test
    fun `non-AI findings pass through unchanged without AI call`() =
        runTest {
            val findings = listOf(nonAiFinding(), nonAiFinding(rule = "commit-messages"))
            val reviewer = ConstitutionalReviewer(failingAiService(), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(findings, result.findings)
            assertEquals(0, result.removedCount)
            assertEquals(0, result.totalReviewed)
        }

    @Test
    fun `analyzer finding with AI provenance and plain rule id is reviewed while deterministic finding is not`() =
        runTest {
            val responseJson = """{"findings": [{"index": 0, "verdict": "remove"}]}"""
            val analyzer = nonAiFinding(rule = "ticket-compliance", message = "Analyzer").copy(
                metadata = mapOf(AiFindingMetadataKeys.AI_PROVIDER to "openai"),
            )
            val deterministic = nonAiFinding(message = "Deterministic")
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(listOf(analyzer, deterministic))

            assertEquals(listOf("Deterministic"), result.findings.map { it.message })
            assertEquals(1, result.removedCount)
            assertEquals(1, result.totalReviewed)
        }

    // --- Keep verdicts ---

    @Test
    fun `all keep verdicts returns all AI findings unchanged`() =
        runTest {
            val responseJson = """
            {
              "findings": [
                {"index": 0, "verdict": "keep"},
                {"index": 1, "verdict": "keep"}
              ]
            }
            """.trimIndent()
            val findings = listOf(aiFinding(message = "F0"), aiFinding(message = "F1"))
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(2, result.findings.size)
            assertEquals(0, result.removedCount)
            assertEquals(2, result.totalReviewed)
        }

    // --- Remove verdicts ---

    @Test
    fun `remove verdict filters that finding`() =
        runTest {
            val responseJson = """
            {
              "findings": [
                {"index": 0, "verdict": "remove", "violatedPrinciple": 1, "reason": "Formatting comment"},
                {"index": 1, "verdict": "keep"}
              ]
            }
            """.trimIndent()
            val findings = listOf(aiFinding(message = "Format this"), aiFinding(message = "Null check missing"))
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(1, result.findings.size)
            assertEquals("Null check missing", result.findings[0].message)
            assertEquals(1, result.removedCount)
            assertEquals(2, result.totalReviewed)
        }

    @Test
    fun `all remove verdicts returns empty AI findings list`() =
        runTest {
            val responseJson = """
            {
              "findings": [
                {"index": 0, "verdict": "remove"},
                {"index": 1, "verdict": "remove"}
              ]
            }
            """.trimIndent()
            val findings = listOf(aiFinding(message = "F0"), aiFinding(message = "F1"))
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(findings)

            assertTrue(result.findings.isEmpty())
            assertEquals(2, result.removedCount)
            assertEquals(2, result.totalReviewed)
        }

    // --- Mixed AI and non-AI ---

    @Test
    fun `non-AI findings preserved alongside filtered AI findings`() =
        runTest {
            val responseJson = """
            {
              "findings": [
                {"index": 0, "verdict": "keep"}
              ]
            }
            """.trimIndent()
            val nonAi = nonAiFinding(message = "Non-AI")
            val aiKept = aiFinding(message = "Kept")
            val aiRemoved = aiFinding(message = "Removed")
            val findings = listOf(nonAi, aiKept, aiRemoved)
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(2, result.findings.size)
            assertTrue(result.findings.any { it.message == "Non-AI" })
            assertTrue(result.findings.any { it.message == "Kept" })
            assertEquals(1, result.removedCount)
            assertEquals(2, result.totalReviewed)
        }

    // --- Graceful degradation ---

    @Test
    fun `AI service exception keeps all findings unchanged`() =
        runTest {
            val findings = listOf(aiFinding(message = "F0"), aiFinding(message = "F1"))
            val reviewer = ConstitutionalReviewer(failingAiService(), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(findings, result.findings)
            assertEquals(0, result.removedCount)
            assertEquals(2, result.totalReviewed)
        }

    @Test
    fun `parse failure keeps all findings unchanged`() =
        runTest {
            val findings = listOf(aiFinding(message = "F0"))
            val reviewer = ConstitutionalReviewer(mockAiService("not valid json at all"), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(findings, result.findings)
            assertEquals(0, result.removedCount)
            assertEquals(1, result.totalReviewed)
        }

    @Test
    fun `response with no matching indices keeps all findings`() =
        runTest {
            val responseJson = """{"findings": []}"""
            val findings = listOf(aiFinding(message = "F0"), aiFinding(message = "F1"))
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(findings)

            assertTrue(result.findings.isEmpty())
            assertEquals(2, result.removedCount)
            assertEquals(2, result.totalReviewed)
        }

    @Test
    fun `out-of-range indices in response are ignored safely`() =
        runTest {
            val responseJson = """
            {
              "findings": [
                {"index": 0, "verdict": "keep"},
                {"index": 99, "verdict": "keep"}
              ]
            }
            """.trimIndent()
            val findings = listOf(aiFinding(message = "F0"))
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(1, result.findings.size)
            assertEquals("F0", result.findings[0].message)
            assertEquals(0, result.removedCount)
        }

    // --- Multiple self-critique rounds ---

    @Test
    fun `multiple self-critique rounds accumulate removed counts`() =
        runTest {
            var callCount = 0
            val service = object : AiService {
                override val isAvailable = true

                override suspend fun complete(
                    request: AiCompletionRequest,
                    providerId: String?,
                ): AiCompletionResponse {
                    callCount++
                    val json = when (callCount) {
                        1 -> """{"findings": [{"index": 0, "verdict": "keep"}, {"index": 1, "verdict": "remove"}]}"""
                        else -> """{"findings": [{"index": 0, "verdict": "keep"}]}"""
                    }
                    return AiCompletionResponse(
                        content = json,
                        promptTokens = 50,
                        completionTokens = 50,
                        totalTokens = 100,
                        model = testModel,
                    )
                }
            }

            val findings = listOf(aiFinding(message = "F0"), aiFinding(message = "F1"))
            val reviewer = ConstitutionalReviewer(service, principles, testModel, selfCritiqueRounds = 2)

            val result = reviewer.review(findings)

            assertEquals(2, callCount)
            assertEquals(1, result.findings.size)
            assertEquals("F0", result.findings[0].message)
            assertEquals(1, result.removedCount)
            assertEquals(2, result.totalReviewed)
        }

    // --- Metadata correctness ---

    @Test
    fun `totalReviewed reflects only AI findings not total findings`() =
        runTest {
            val responseJson = """{"findings": [{"index": 0, "verdict": "keep"}]}"""
            val findings = listOf(
                nonAiFinding(),
                nonAiFinding(rule = "commit-messages"),
                aiFinding(message = "Only AI finding"),
            )
            val reviewer = ConstitutionalReviewer(mockAiService(responseJson), principles, testModel)

            val result = reviewer.review(findings)

            assertEquals(1, result.totalReviewed)
            assertEquals(3, result.findings.size)
        }

    // --- failureMode: downgrade ---

    @Test
    fun `downgradeViolations keeps violating finding as info`() =
        runTest {
            val response = """{"findings": [{"index": 0, "verdict": "remove"}, {"index": 1, "verdict": "keep"}]}"""
            val reviewer = ConstitutionalReviewer(mockAiService(response), principles, testModel, downgradeViolations = true)
            val findings = listOf(aiFinding(message = "style nit"), aiFinding(message = "real bug"))

            val result = reviewer.review(findings)

            assertEquals(2, result.findings.size)
            val nit = result.findings.single { it.message.endsWith("style nit") }
            assertEquals(Severity.INFO, nit.severity)
            assertTrue(nit.message.startsWith("[CONSTITUTION]"))
            assertEquals(Severity.WARNING, result.findings.single { it.message == "real bug" }.severity)
            assertEquals(1, result.removedCount)
        }
}
