package io.karandash.plugins.ai.analyzers.reviewcomments

import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.context.PrecomputedDiffContext
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewCommentsAnalyzeConfigTest {
    private val module = ReviewCommentsAnalyzerModule()

    private val mr = createTestMergeRequest(
        title = "Add validation",
        changes = listOf(
            FileChange(
                path = "src/Main.kt",
                changeType = ChangeType.MODIFIED,
                additions = 3,
                deletions = 0,
                diffContent = "@@ -1,1 +1,3 @@\n+fun validate() {\n+    check(true)\n+}",
            ),
        ),
    )

    private val twoComments =
        """{"comments":[
            {"file":"src/Main.kt","line":1,"category":"style","severity":"warning","message":"first"},
            {"file":"src/Main.kt","line":2,"category":"style","severity":"error","message":"second"}
        ]}"""

    private suspend fun run(
        config: JsonObject,
        service: FakeAiService = FakeAiService(twoComments, totalTokens = 100),
        budget: TokenBudget = TokenBudget(10_000),
    ) = module.analyze(
        AiAnalyzerContext(
            mr = mr,
            aiService = service,
            budget = budget,
            model = "m",
            config = config,
            diffContext = PrecomputedDiffContext(preparedDiff = "### src/Main.kt\n+fun validate() {"),
        ),
    ) to service.requests.single()

    @Test
    fun `maxComments leaves the cut to the whole-MR cap while reviewMaxTokens caps the response`() =
        runTest {
            val budget = TokenBudget(10_000)
            val config = buildJsonObject {
                put("maxComments", 1)
                putJsonObject("limits") { put("reviewMaxTokens", 700) }
            }

            val (findings, request) = run(config, budget = budget)

            assertEquals(listOf("first", "second"), findings.map { it.message })
            assertEquals(700, request.maxTokens)
            assertEquals(0.1, request.temperature)
            assertNull(request.responseSchema, "the dispatcher attaches the schema")
            assertTrue("Title: Add validation" in request.contextBlocks.single())
            assertTrue("- src/Main.kt [MODIFIED] (+3/-0)" in request.prompt, request.prompt)
            assertTrue("### src/Main.kt" in request.prompt)
            assertEquals(10_000, budget.remaining())
        }

    @Test
    fun `default config keeps both findings`() =
        runTest {
            val (findings, request) = run(JsonObject(emptyMap()))

            assertEquals(2, findings.size)
        }

    @Test
    fun `fixSuggestionMinSeverity picks the suggestion instruction`() =
        runTest {
            fun cfg(severity: String?) = buildJsonObject { if (severity != null) put("fixSuggestionMinSeverity", severity) }

            val error = run(cfg("error")).second.systemPrompt.orEmpty()
            val info = run(cfg("INFO")).second.systemPrompt.orEmpty()
            val default = run(cfg(null)).second.systemPrompt.orEmpty()

            assertTrue("Only include suggestedCode for findings with severity \"error\". " in error)
            assertTrue("Include suggestedCode for findings of any severity" in info)
            assertTrue("severity \"error\" or \"warning\"" in default)
        }

    @Test
    fun `includeFixSuggestions false uses the plain review prompt`() =
        runTest {
            val withFixes = run(buildJsonObject { put("includeFixSuggestions", true) }).second.systemPrompt.orEmpty()
            val plain = run(buildJsonObject { put("includeFixSuggestions", false) }).second.systemPrompt.orEmpty()

            assertTrue("concrete fix suggestions" in withFixes)
            assertFalse("concrete fix suggestions" in plain)
        }

    @Test
    fun `bug checklist with the kotlin line is in the default prompt and review bugChecklist false drops it`() =
        runTest {
            val withChecklist = run(buildJsonObject {}).second.systemPrompt.orEmpty()
            val without = run(buildJsonObject { putJsonObject("review") { put("bugChecklist", false) } }).second.systemPrompt.orEmpty()

            assertTrue("Bug checklist:" in withChecklist, withChecklist)
            assertTrue("- Kotlin: LocalDateTime.now()" in withChecklist, withChecklist)
            assertFalse("Bug checklist:" in without, without)
        }

    @Test
    fun `default prompt asks for behaviour defects only and reads removed lines`() =
        runTest {
            val prompt = run(buildJsonObject {}).second.systemPrompt.orEmpty()

            assertTrue("Report only defects that change behaviour" in prompt, prompt)
            assertTrue("Read removed (`-`) lines as carefully as added ones" in prompt, prompt)
            assertTrue("An empty comments list is a correct and common answer" in prompt, prompt)
            assertFalse("Style issues" in prompt, prompt)
        }
}
