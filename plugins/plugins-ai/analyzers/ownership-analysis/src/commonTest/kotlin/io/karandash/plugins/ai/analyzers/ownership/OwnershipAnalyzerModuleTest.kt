package io.karandash.plugins.ai.analyzers.ownership

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiCompletionRequest
import io.karandash.api.ai.AiCompletionResponse
import io.karandash.api.ai.AiService
import io.karandash.models.Approval
import io.karandash.models.BlameCommit
import io.karandash.models.BlameEntry
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.plugins.ai.core.rules.AiRuleConfig
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OwnershipAnalyzerModuleTest {
    private val module = OwnershipAnalyzerModule()

    private class RecordingAiService(
        private val content: String,
    ) : AiService {
        val requests = mutableListOf<AiCompletionRequest>()
        override val isAvailable = true

        override suspend fun complete(
            request: AiCompletionRequest,
            providerId: String?,
        ): AiCompletionResponse {
            requests += request
            return AiCompletionResponse(content, promptTokens = 100, completionTokens = 100, totalTokens = 200, model = request.model)
        }
    }

    private fun commit(
        sha: String,
        message: String,
    ) = Commit(sha, message, CommitAuthor("Test Author", "test@example.com"), "2024-01-01T00:00:00Z")

    private val blame = BlameEntry(
        startLine = 1,
        endLine = 20,
        commit = BlameCommit("old123", CommitAuthor("Alice", "alice@example.com"), "Initial implementation", "2023-06-01T00:00:00Z"),
    )

    private val mrWithBlame = createTestMergeRequest(
        commits = listOf(commit("abc", "Add feature")),
        changes = listOf(FileChange("src/main.kt", ChangeType.MODIFIED, additions = 5, deletions = 2, blameData = listOf(blame))),
    )

    private fun ownershipJson(
        score: Double,
        crossTeam: Boolean = false,
        bugFixCount: Int = 0,
        correlations: String = "",
    ) = """
        {
            "ownershipScore": $score,
            "crossTeamChanges": $crossTeam,
            "contributors": [{"name": "Alice", "email": "a@test.com"}],
            "bugFixCorrelations": [$correlations],
            "knowledgeRisks": ["risk1"],
            "statistics": {"totalFilesAnalyzed": 3, "filesWithBlameData": 2, "uniqueCodeOwners": 1, "bugFixCount": $bugFixCount},
            "summary": "OK"
        }
        """.trimIndent()

    private fun correlation(i: Int) =
        """{"file": "src/f$i.kt", "bugIntroducedBy": "Author$i", "fixedBy": "Fixer", "commitMessage": "fix$i", "confidence": 0.9}"""

    @Test
    fun `module is default off with ownershipAnalysis feature key`() {
        assertFalse(module.defaultEnabled)
        assertEquals("ownershipAnalysis", module.featureKey)
    }

    @Test
    fun `detectBugFixCommits finds keywords case-insensitively`() {
        val signals = module.detectBugFixCommits(
            listOf(
                commit("a", "Fix null pointer in parser"),
                commit("b", "Add new feature"),
                commit("c", "Revert accidental deletion"),
                commit("d", "HOTFIX for production crash"),
            ),
        )
        assertEquals(listOf("a", "c", "d"), signals.map { it.commit.sha })
        assertTrue("hotfix" in signals[2].keywords)
        assertTrue(module.detectBugFixCommits(listOf(commit("x", "Update dependencies"))).isEmpty())
    }

    @Test
    fun `severity follows default ownership thresholds and escalation signals`() {
        assertEquals(Severity.INFO, module.parseResponse(ownershipJson(0.85), "m", 1).single().severity)
        assertEquals(Severity.WARNING, module.parseResponse(ownershipJson(0.55), "m", 1).single().severity)
        assertEquals(Severity.WARNING, module.parseResponse(ownershipJson(0.2), "m", 1).single().severity)
        // a count of bug fixes blames people; it does not escalate to ERROR
        assertEquals(Severity.INFO, module.parseResponse(ownershipJson(0.9, bugFixCount = 3), "m", 1).single().severity)
        assertEquals(Severity.WARNING, module.parseResponse(ownershipJson(0.9, crossTeam = true), "m", 1).single().severity)
    }

    @Test
    fun `bug-fix correlations become capped WARNING findings that name nobody`() {
        val findings = module.parseResponse(ownershipJson(0.9, correlations = (1..10).joinToString(",") { correlation(it) }), "m", 1)
        assertEquals(6, findings.size)
        assertEquals(Severity.WARNING, findings[1].severity)
        assertTrue(findings[1].message.contains("src/f1.kt"), findings[1].message)
        findings.forEach { finding ->
            val text = finding.message + finding.metadata.values.joinToString()
            assertFalse("Author1" in text || "Fixer" in text, text)
        }
    }

    @Test
    fun `the ownership summary is capped at WARNING whatever the thresholds and names nobody`() {
        val strict = AiRuleConfig.fromPluginConfig(
            JsonObject(
                mapOf(
                    "thresholds" to JsonObject(
                        mapOf(
                            "ownershipError" to JsonPrimitive(0.95),
                            "ownershipWarning" to JsonPrimitive(0.5),
                            "riskError" to JsonPrimitive(0.1)
                        ),
                    ),
                ),
            ),
        )

        val summary = module.parseResponse(ownershipJson(0.0, crossTeam = true, bugFixCount = 9), "m", 1, strict).single()
        val belowError = module.parseResponse(ownershipJson(0.9), "m", 1, strict).single()

        assertEquals(Severity.WARNING, summary.severity)
        assertEquals(Severity.WARNING, belowError.severity, "a score below ownershipError still warns")
        val text = summary.message + summary.metadata.values.joinToString()
        assertFalse("Alice" in text || "a@test.com" in text, text)
    }

    @Test
    fun `buildPrompt sends no email of the MR author or a commit author`() {
        val mr = mrWithBlame.copy(author = User("dev", "Dev", "dev@example.com"))

        val prompt = module.buildPrompt(mr, "") + module.defaultPrompt.orEmpty()

        assertFalse("@example.com" in prompt, prompt)
        assertFalse("email" in prompt.lowercase(), prompt)
    }

    @Test
    fun `summary metadata carries statistics`() {
        val metadata = module.parseResponse(ownershipJson(0.7), "test-model", 100).single().metadata
        listOf("crossTeamChanges", "uniqueContributors", "uniqueCodeOwners", "bugFixCount", "filesWithBlameData", "knowledgeRisks")
            .forEach { assertTrue(metadata.containsKey(it), it) }
        assertEquals("0.7", metadata[OwnershipAnalyzerModule.OWNERSHIP_SCORE_KEY])
    }

    @Test
    fun `json parse error returns WARNING fallback finding`() {
        val finding = module.parseResponse("not valid json", "m", 50).single()
        assertEquals(Severity.INFO, finding.severity)
        assertTrue(finding.message.contains("unparseable"), finding.message)
    }

    @Test
    fun `buildPrompt summarises blame per author and bug-fix signals`() {
        val prompt = module.buildPrompt(mrWithBlame.copy(commits = listOf(commit("a", "Fix null pointer"))), "")
        assertTrue(prompt.contains("## Blame Data (1 files with blame)"))
        // blame is grouped by name; no email reaches the model
        assertTrue(prompt.contains("Alice: 20 lines"), prompt)
        assertFalse("@example.com" in prompt, prompt)
        assertTrue(prompt.contains("## Bug-Fix Signals (1 commits)"))

        val noBlame = module.buildPrompt(mrWithBlame.copy(changes = listOf(FileChange("src/main.kt", ChangeType.MODIFIED))), "")
        assertTrue(noBlame.contains("No blame data available"))
    }

    @Test
    fun `buildPrompt lists reviewers approvers and counts open ended blame as one line`() {
        val oneLine = blame.copy(startLine = 4, endLine = 0)
        val mr = mrWithBlame.copy(
            description = "Why",
            reviewers = listOf(User("bob", "Bob")),
            approvals = listOf(Approval(User("carol", "Carol"))),
            changes = listOf(FileChange("src/main.kt", ChangeType.MODIFIED, blameData = listOf(oneLine))),
        )
        val prompt = module.buildPrompt(mr, "")
        assertTrue("Description: Why" in prompt)
        assertTrue("## Reviewers\n- Bob (bob)" in prompt)
        assertTrue("## Approvers\n- Carol (carol)" in prompt)
        assertTrue("Alice: 1 lines" in prompt)
        val bare = module.buildPrompt(mrWithBlame, "")
        assertFalse("## Reviewers" in bare || "## Approvers" in bare)
        assertTrue("(no description)" in bare)
    }

    @Test
    fun `analyze sends prompt with configured limits and applies configured thresholds`() =
        runTest {
            val service = RecordingAiService(ownershipJson(0.85))
            val config: JsonElement = JsonObject(
                mapOf(
                    "limits" to JsonObject(mapOf("ownershipMaxTokens" to JsonPrimitive(900))),
                    "thresholds" to JsonObject(mapOf("ownershipWarning" to JsonPrimitive(0.9))),
                ),
            )
            val context = AiAnalyzerContext(mrWithBlame, service, TokenBudget(50_000), "test", config = config)

            val findings = module.analyze(context)

            assertEquals(Severity.WARNING, findings.single().severity)
            assertEquals(900, service.requests.single().maxTokens)
            assertTrue(
                service.requests
                    .single()
                    .prompt
                    .contains("Alice")
            )
        }

    @Test
    fun `bug-fix confidence percentage is rounded not truncated by float error`() {
        // 0.29 * 100 == 28.999999999999996 in IEEE 754, so truncation printed 28%
        val corr = """{"file": "a.kt", "bugIntroducedBy": "A", "fixedBy": "B", "commitMessage": "fix", "confidence": 0.29}"""
        val message = module.parseResponse(ownershipJson(0.9, correlations = corr), "m", 1)[1].message
        assertTrue("confidence: 29%" in message, message)
    }

    @Test
    fun `module descriptor exposes identity and prompt wiring`() =
        runTest {
            assertEquals("AI Ownership Analysis", module.name)
            assertEquals(OwnershipAnalyzerModule.ID, module.id)
            assertEquals(OwnershipAnalyzerModule.ANALYZER_ID, module.analyzerId)
            assertEquals(OwnershipAnalyzerModule.FEATURE_KEY, module.featureKey)
            assertEquals("1.0.0", module.version)
            assertTrue(module.mrLevel)
            assertTrue(module.needsBlame)
            assertTrue(module.estimatedTokens > 0)
            assertTrue(module.defaultPrompt.orEmpty().isNotBlank())
            assertTrue(module.explanation.contains("ownership"), module.explanation)
            assertTrue(module.initialize(TestPluginContext()))
        }

    @Test
    fun `full ownership payload decodes every nested field`() {
        val json = """
            {"ownershipScore": 0.5, "crossTeamChanges": true,
             "contributors": [{"name": "Alice", "linesOwned": 10, "filesOwned": 2, "role": "owner"}, {"name": "Bob"}],
             "fileOwnership": [{"path": "a.kt", "primaryOwner": "Alice", "ownershipPercentage": 80.0, "contributorCount": 2}, {"path": "b.kt"}],
             "bugFixCorrelations": [{"file": "a.kt", "bugIntroducedBy": "A", "fixedBy": "B"}],
             "knowledgeRisks": ["r"],
             "statistics": {"totalFilesAnalyzed": 2, "uniqueFixers": 1, "crossTeamChangeCount": 1, "avgOwnershipScore": 0.4},
             "summary": "s"}
        """.trimIndent()
        val result = KarandashJson.Default.decodeFromString<OwnershipResult>(json)
        assertEquals(ContributorInfo("Alice", 10, 2, "owner"), result.contributors[0])
        assertEquals("contributor", result.contributors[1].role)
        assertEquals(FileOwnershipGroup("a.kt", "Alice", 80.0, 2), result.fileOwnership[0])
        assertEquals(FileOwnershipGroup("b.kt"), result.fileOwnership[1])
        assertEquals(BugFixCorrelation("a.kt", "A", "B"), result.bugFixCorrelations.single())
        assertEquals(1, result.statistics.uniqueFixers)
        assertEquals(1, result.statistics.crossTeamChangeCount)
        assertEquals(0.4, result.statistics.avgOwnershipScore)
        assertEquals(result, result.copy())
    }
}
