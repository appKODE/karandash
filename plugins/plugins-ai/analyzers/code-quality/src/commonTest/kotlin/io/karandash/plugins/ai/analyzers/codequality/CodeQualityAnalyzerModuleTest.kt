package io.karandash.plugins.ai.analyzers.codequality

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeQualityAnalyzerModuleTest {
    private val module = CodeQualityAnalyzerModule()

    private val mr = createTestMergeRequest(
        title = "Add user service",
        description = "Implements user registration",
        sourceBranch = "feat/user-service",
        commits = listOf(Commit("abc123", "Add user service", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z")),
        changes = listOf(FileChange("src/UserService.kt", ChangeType.MODIFIED, additions = 30, deletions = 5)),
    )

    private class RecordingAiService(
        private val content: String,
        private val totalTokens: Int = 500,
    ) : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse(content, promptTokens = 300, completionTokens = 200, totalTokens, request.model)
        }
    }

    private fun context(
        service: AiService,
        budget: TokenBudget = TokenBudget(50_000),
        config: JsonElement = JsonObject(emptyMap()),
    ) = AiAnalyzerContext(
        mr = mr,
        aiService = service,
        budget = budget,
        model = "gpt-4o-mini",
        config = config,
        diffContext = PrecomputedDiffContext(preparedDiff = "+fun register(user: User) = db.save(user)"),
    )

    private suspend fun analyze(
        json: String,
        config: JsonElement = JsonObject(emptyMap()),
    ) = module.analyze(context(RecordingAiService(json), config = config))

    private fun qualityJson(
        overallScore: Double = 0.85,
        extra: String = "",
    ) = """
        {
            "overallScore": $overallScore,
            "dimensions": {
                "correctness": 0.9, "errorHandling": 0.8, "readability": 0.85, "maintainability": 0.8,
                "performance": 0.9, "security": 0.85, "design": 0.8
            },
            "issues": [
                {
                    "file": "src/UserService.kt", "line": 10, "dimension": "errorHandling", "severity": "warning",
                    "message": "Missing error handling for database save", "suggestion": "Wrap db.save() in try-catch"
                }
            ],
            "strengths": ["Clean function naming", "Good separation of concerns"],
            "summary": "Well-structured code with minor error handling gaps."
            $extra
        }
        """.trimIndent()

    @Test
    fun `module is default off with codeQuality feature key`() {
        assertFalse(module.defaultEnabled)
        assertEquals("codeQuality", module.featureKey)
        assertEquals(CodeQualityAnalyzerModule.ANALYZER_ID, module.analyzerId)
    }

    @Test
    fun `high quality score maps to INFO severity`() =
        runTest {
            val summary = analyze(qualityJson()).first()
            assertEquals(Severity.INFO, summary.severity)
            assertEquals(CodeQualityAnalyzerModule.ANALYZER_ID, summary.rule)
            assertEquals("0.85", summary.metadata[CodeQualityAnalyzerModule.OVERALL_SCORE_KEY])
            assertTrue(summary.message.contains("excellent"), summary.message)
        }

    @Test
    fun `boundary scores map to thresholds from AiRuleConfig defaults`() =
        runTest {
            assertEquals(Severity.ERROR, analyze(qualityJson(0.3)).first().severity)
            assertEquals(Severity.WARNING, analyze(qualityJson(0.6)).first().severity)
            assertEquals(Severity.INFO, analyze(qualityJson(0.61)).first().severity)
            assertTrue(analyze(qualityJson(0.2)).first().message.contains("poor"))
        }

    @Test
    fun `configured thresholds are honoured`() =
        runTest {
            val config = JsonObject(mapOf("thresholds" to JsonObject(mapOf("codeQualityWarning" to JsonPrimitive(0.9)))))
            assertEquals(Severity.WARNING, analyze(qualityJson(0.85), config).first().severity)
        }

    @Test
    fun `configured codeQualityError raises a mid score to an error`() =
        runTest {
            val config = JsonObject(mapOf("thresholds" to JsonObject(mapOf("codeQualityError" to JsonPrimitive(0.7)))))
            val finding = analyze(qualityJson(0.65), config).first()
            assertEquals(Severity.ERROR, finding.severity)
            assertTrue(finding.message.contains("poor"))
            assertEquals(Severity.INFO, analyze(qualityJson(0.95), config).first().severity)
            assertTrue(analyze(qualityJson(0.95), config).first().message.contains("excellent"))
        }

    @Test
    fun `per-file issues generate findings with FileRef location`() =
        runTest {
            val issue = analyze(qualityJson())[1]
            assertEquals(Severity.WARNING, issue.severity)
            assertTrue(issue.message.contains("errorHandling"))
            assertEquals("Wrap db.save() in try-catch", issue.suggestion)
            assertEquals(Location.FileRef("src/UserService.kt", 10), issue.location)
        }

    @Test
    fun `dimension scores preserved in metadata`() =
        runTest {
            val meta = analyze(qualityJson()).first().metadata
            assertEquals("0.9", meta["correctness"])
            assertEquals("0.8", meta["errorHandling"])
            assertEquals("0.85", meta["readability"])
            assertEquals("0.8", meta["design"])
            assertTrue(meta["strengths"].orEmpty().contains("Clean function naming"))
        }

    @Test
    fun `maturity level only reported and prompted when configured`() =
        runTest {
            val json = qualityJson(extra = """, "codeMaturityLevel": "senior"""")
            val off = RecordingAiService(json)
            assertNull(module.analyze(context(off)).first().metadata[CodeQualityAnalyzerModule.MATURITY_KEY])
            assertFalse(
                off.requests
                    .single()
                    .systemPrompt
                    .orEmpty()
                    .contains("codeMaturityLevel")
            )

            val on = RecordingAiService(json)
            val config = JsonObject(mapOf("codeQualityIncludeMaturityLevel" to JsonPrimitive(true)))
            val findings = module.analyze(context(on, config = config))
            assertEquals("senior", findings.first().metadata[CodeQualityAnalyzerModule.MATURITY_KEY])
            assertTrue(
                on.requests
                    .single()
                    .systemPrompt
                    .orEmpty()
                    .contains("codeMaturityLevel")
            )
        }

    @Test
    fun `a missing maturity level and a file less issue are tolerated`() =
        runTest {
            val json = qualityJson().replace("\"file\": \"src/UserService.kt\", ", "")
            val config = JsonObject(mapOf("codeQualityIncludeMaturityLevel" to JsonPrimitive(true)))
            val findings = analyze(json, config)
            assertNull(findings.first().metadata[CodeQualityAnalyzerModule.MATURITY_KEY])
            assertNull(findings[1].location)
        }

    @Test
    fun `request uses configured temperature and max tokens`() =
        runTest {
            val service = RecordingAiService(qualityJson())
            val config = JsonObject(mapOf("limits" to JsonObject(mapOf("codeQualityMaxTokens" to JsonPrimitive(777)))))
            module.analyze(context(service, config = config))
            assertEquals(777, service.requests.single().maxTokens)
            assertEquals(0.1, service.requests.single().temperature)
        }

    @Test
    fun `json parse error returns WARNING fallback finding`() =
        runTest {
            val findings = analyze("This is not valid JSON")
            assertEquals(1, findings.size)
            assertEquals(Severity.INFO, findings[0].severity)
            assertTrue(findings[0].message.contains("unparseable"), findings[0].message)
        }

    @Test
    fun `response wrapped in markdown code block is parsed`() =
        runTest {
            assertEquals("0.85", analyze("```json\n${qualityJson()}\n```").first().metadata["overallScore"])
        }

    @Test
    fun `response tokens are left to the dispatcher to record`() =
        runTest {
            val budget = TokenBudget(50_000)
            module.analyze(context(RecordingAiService(qualityJson(), totalTokens = 600), budget = budget))
            assertEquals(0, budget.totalConsumed())
        }

    @Test
    fun `buildPrompt includes metadata commits and prepared diff`() {
        val prompt = module.buildPrompt(context(RecordingAiService("")))
        listOf("Add user service", "Implements user registration", "feat/user-service", "src/UserService.kt", "db.save(user)")
            .forEach { assertTrue(prompt.contains(it), it) }
        val noDescription = module.buildPrompt(context(RecordingAiService("")).copy(mr = mr.copy(description = null)))
        assertTrue(noDescription.contains("(no description)"))
    }

    @Test
    fun `issues capped at 8`() {
        val issues = (1..12).joinToString(",") { i ->
            """{"file": "src/File$i.kt", "line": $i, "dimension": "readability", "severity": "info", "message": "Issue $i"}"""
        }
        val json = qualityJson(0.7).replace(Regex("\"issues\": \\[[^\\]]*]"), "\"issues\": [$issues]")
        assertEquals(9, module.parseResponse(json, "test-model", 100).size)
    }
}
