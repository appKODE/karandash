@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.analyzers.outdateddocs

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.run.RepositoryFileReader
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.ChangeType
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.TokenBudget
import io.karandash.testfixtures.FakeAiService
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class OutdatedDocsAnalyzerModuleTest {
    private val module = OutdatedDocsAnalyzerModule()
    private val sourceDocs = mapOf("docs/payments.md" to "Call charge() to bill", "README.md" to "# Shop")
    private val reads = mutableListOf<String>()

    /** Runs [module] with a repository reader that serves [docs] at any ref and records each `ref:path` read. */
    private suspend fun analyzeWithDocs(
        context: AiAnalyzerContext,
        docs: Map<String, String> = sourceDocs,
    ): List<Finding> {
        val session = ModuleRegistryImpl().forRun()
        session.provide(
            RunServiceKeys.REPOSITORY_FILE,
            RepositoryFileReader { ref, path ->
                reads += "$ref:$path"
                docs[path]
            },
        )
        return withContext(session.services) { module.analyze(context) }.also { session.close() }
    }

    private fun context(
        service: FakeAiService,
        paths: List<String> = listOf("src/payments/PaymentsApi.kt"),
        skips: MutableList<String> = mutableListOf(),
    ) = AiAnalyzerContext(
        mr = createTestMergeRequest(changes = paths.map { FileChange(it, ChangeType.MODIFIED, additions = 1, deletions = 1) })
            .copy(diffRefs = DiffRefs(baseSha = "base1", headSha = "head1")),
        aiService = service,
        budget = TokenBudget(50_000),
        model = "m",
        onSkip = { skips += it },
    )

    /** docs near the changed code are read at the source SHA, reach the prompt, and issues are WARNINGs on them. */
    @Test
    fun `outdated-docs reads docs at the source SHA and reports warnings on them`() =
        runTest {
            val service = FakeAiService(
                """{"issues":[{"file":"docs/payments.md","message":"Mentions removed charge()","severity":"info","confidence":0.8},""" +
                    """{"file":"README.md","message":"Maybe stale","severity":"error","confidence":0.4},""" +
                    """{"file":"docs/invented.md","message":"Not a doc the model saw","confidence":0.9}]}""",
            )
            val findings = analyzeWithDocs(context(service))

            assertEquals(listOf("docs/payments.md", "README.md"), findings.map { (it.location as Location.FileRef).path })
            assertEquals(listOf(Severity.WARNING, Severity.WARNING), findings.map { it.severity })
            assertTrue("head1:docs/payments.md" in reads, reads.toString())
            assertTrue("head1:src/payments/README.md" in reads, reads.toString())
            val prompt = service.requests.single().prompt
            assertTrue("src/payments/PaymentsApi.kt" in prompt)
            assertTrue("--- docs/payments.md ---" in prompt && "Call charge() to bill" in prompt, prompt)
        }

    /** docs are read from the repository unredacted; a secret in one never reaches the provider. */
    @Test
    fun `a secret in a doc at the source SHA never reaches the provider`() =
        runTest {
            val token = "ghp_" + "A".repeat(36)
            val service = FakeAiService("""{"issues":[]}""")

            analyzeWithDocs(context(service), mapOf("docs/payments.md" to "export TOKEN=$token"))

            val prompt = service.requests.single().prompt
            assertTrue("--- docs/payments.md ---" in prompt, prompt)
            assertFalse(token in prompt, prompt)
        }

    @Test
    fun `outdated-docs skips when no doc near the code can be read`() =
        runTest {
            val service = FakeAiService("""{"issues":[]}""")
            val skips = mutableListOf<String>()

            assertTrue(module.analyze(context(service, skips = skips)).isEmpty())
            assertTrue(analyzeWithDocs(context(service, skips = skips), emptyMap()).isEmpty())

            assertEquals(2, skips.size, skips.toString())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `doc-only change skips the AI call`() =
        runTest {
            val service = FakeAiService("{}")
            assertTrue(module.analyze(context(service, listOf("README.md", "docs/guide.rst"))).isEmpty())
            assertTrue(service.requests.isEmpty())
        }

    @Test
    fun `malformed response yields a parse error finding`() =
        runTest {
            val finding = analyzeWithDocs(context(FakeAiService("```oops```"))).single()
            assertEquals("parse_error", finding.metadata["error"])
        }

    @Test
    fun `extra instructions reach the system prompt and the base prompt survives`() =
        runTest {
            val service = FakeAiService("""{"issues":[]}""")
            analyzeWithDocs(context(service).copy(extraInstructions = "Project rule: never log card numbers"))

            val system = service.requests
                .single()
                .systemPrompt
                .orEmpty()
            assertTrue(system.contains("Project rule: never log card numbers"), system)
            assertTrue(system.contains("Required JSON format"), system)
        }

    @Test
    fun `prompt shows the description and caps long code lists`() {
        val many = List(32) { "src/F$it.kt" }
        val mr = createTestMergeRequest(
            description = "Drops charge",
            changes = (many + "docs/a.md").map { FileChange(it, ChangeType.MODIFIED, additions = 1, deletions = 1) },
        )
        val prompt = module.buildPrompt(
            AiAnalyzerContext(mr = mr, aiService = FakeAiService("{}"), budget = TokenBudget(50_000), model = "m")
        )
        assertTrue("**MR Description:** Drops charge" in prompt)
        assertTrue("- docs/a.md (MODIFIED)" in prompt)
        assertTrue("... and 2 more files" in prompt)
        val bare = module.buildPrompt(context(FakeAiService("{}")))
        assertTrue("(none)" in bare)
        assertTrue("MR Description" !in bare && "more files" !in bare)
    }
}
