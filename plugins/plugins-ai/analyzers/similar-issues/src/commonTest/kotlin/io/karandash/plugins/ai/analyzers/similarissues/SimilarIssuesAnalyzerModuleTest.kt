@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.similarissues

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.api.run.MergeRequestSearch
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.AiFindingMetadataKeys
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SimilarIssuesAnalyzerModuleTest {
    private val module = SimilarIssuesAnalyzerModule()
    private val diff = "@@ -1 +1,2 @@\n+val user = userRepository.findUser(id)\n+userRepository.save(user)\n"
    private val pastFix =
        MergeRequestSearchHit(id = "42", title = "Fix SQL injection in userRepository", state = MergeRequestSearchHit.MERGED)

    private fun context(
        service: FakeAiService,
        skips: MutableList<String> = mutableListOf(),
    ) = AiAnalyzerContext(
        mr = createTestMergeRequest(
            title = "Fix SQL in UserRepository",
            changes = listOf(FileChange("src/UserRepository.kt", ChangeType.MODIFIED, 2, 0, diff)),
        ),
        aiService = service,
        budget = TokenBudget(50_000),
        model = "m",
        onSkip = { skips += it },
    )

    /** Runs [module] with a platform search that answers [hits] and records each query. */
    private suspend fun analyzeWithSearch(
        context: AiAnalyzerContext,
        hits: List<MergeRequestSearchHit>?,
        queries: MutableList<String> = mutableListOf(),
    ): List<Finding> {
        val session = ModuleRegistryImpl().forRun()
        session.provide(
            RunServiceKeys.MERGE_REQUEST_SEARCH,
            MergeRequestSearch { text, _, _ ->
                queries += text.orEmpty()
                hits
            },
        )
        return withContext(session.services) { module.analyze(context) }.also { session.close() }
    }

    @Test
    fun `similar-issues is off by default`() {
        assertFalse(module.defaultEnabled, "A7: similar-issues must be opted into with similarIssues: true")
    }

    /** history comes from the platform search on the diff keywords, and reaches the prompt. */
    @Test
    fun `similar-issues searches the platform with diff keywords and prompts with the hits`() =
        runTest {
            val service = FakeAiService("""{"patterns":[{"description":"!42 fixed the same query","confidence":0.9}],"searchTerms":[]}""")
            val queries = mutableListOf<String>()

            val finding = analyzeWithSearch(context(service), listOf(pastFix), queries).single()

            assertEquals("user", queries.first())
            assertTrue("!42 [merged] Fix SQL injection in userRepository" in service.requests.single().prompt)
            assertTrue("!42" in finding.message, finding.message)
        }

    /** past MR titles come from the platform unredacted; a secret in one never reaches the provider. */
    @Test
    fun `a secret in a past MR title never reaches the provider`() =
        runTest {
            val token = "ghp_" + "A".repeat(36)
            val service = FakeAiService("""{"patterns":[],"searchTerms":[]}""")

            analyzeWithSearch(context(service), listOf(pastFix.copy(title = "Rotate leaked $token")))

            assertFalse(token in service.requests.single().prompt, service.requests.single().prompt)
        }

    /** without a platform that can search, the rule is disclosed as skipped and no AI call is made. */
    @Test
    fun `similar-issues skips without platform history`() =
        runTest {
            val service = FakeAiService("""{"patterns":[],"searchTerms":[]}""")
            val skips = mutableListOf<String>()

            assertTrue(module.analyze(context(service, skips)).isEmpty())
            assertTrue(analyzeWithSearch(context(service, skips), null).isEmpty())
            assertTrue(analyzeWithSearch(context(service, skips), emptyList()).isEmpty())

            assertEquals(3, skips.size, skips.toString())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `keywords are the most frequent identifiers on added lines`() {
        val mr = context(FakeAiService("{}")).mr
        assertEquals(listOf("user", "userRepository", "findUser"), SimilarIssuesHistory.keywords(mr))
    }

    @Test
    fun `patterns become one info finding ordered by confidence`() =
        runTest {
            val service = FakeAiService(
                """{"patterns":[{"description":"Low pattern","confidence":0.3},{"description":"Interpolated SQL","confidence":0.9}],""" +
                    """"searchTerms":["sql injection","queryOne"]}""",
            )
            val finding = analyzeWithSearch(context(service), listOf(pastFix)).single()

            assertEquals(Severity.INFO, finding.severity)
            assertEquals("sql injection, queryOne", finding.metadata[AiFindingMetadataKeys.SEARCH_TERMS])
            assertTrue(finding.message.indexOf("Interpolated SQL") < finding.message.indexOf("Low pattern"), finding.message)
            assertTrue(
                service.requests
                    .single()
                    .prompt
                    .contains("Fix SQL in UserRepository")
            )
        }

    @Test
    fun `no patterns yields no finding`() =
        runTest {
            assertTrue(analyzeWithSearch(context(FakeAiService("""{"patterns":[],"searchTerms":[]}""")), listOf(pastFix)).isEmpty())
        }

    @Test
    fun `malformed response yields a parse error warning`() =
        runTest {
            assertEquals(Severity.INFO, analyzeWithSearch(context(FakeAiService("[]")), listOf(pastFix)).single().severity)
        }
}
