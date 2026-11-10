@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.ai.pipeline

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.errors.ProviderException
import io.karandash.api.run.MergeRequestValidator
import io.karandash.api.run.OpenMergeRequestLister
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.finding.inLanguage
import io.karandash.models.support.KarandashJson
import io.karandash.plugins.ai.core.architecture.RuleMutationTester
import io.karandash.plugins.ai.core.architecture.SemanticDiffChecker
import io.karandash.sdk.NoOpLogger
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.createTestMergeRequest
import io.karandash.translations.polish.PolishTranslationModule
import io.karandash.translations.russian.RussianTranslationModule
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock

class AiArchitectureChecksTest {
    private val checks = AiArchitectureChecks(NoOpLogger)

    private val removedApi = createTestMergeRequest(
        changes = listOf(
            FileChange(
                "src/Api.kt",
                ChangeType.MODIFIED,
                diffContent = "-fun login(user: String): Boolean {\n+fun signIn(user: String): Boolean {"
            ),
        ),
    )

    private fun config(json: String): JsonElement = KarandashJson.Default.parseToJsonElement(json)

    private fun options(json: String) = AiArchitectureOptions.from(config(json)) {}

    @Test
    fun `semanticDiff reports a removed public function`() {
        val findings = checks.apply(emptyList(), removedApi, options("""{"architecture":{"semanticDiff":true}}"""), null)
        assertEquals(listOf(SemanticDiffChecker.RULE_ID), findings.map { it.rule })
        assertTrue("login" in findings.single().message, findings.single().message)
    }

    @Test
    fun `semanticDiff is off by default and with enabled false`() {
        assertTrue(checks.apply(emptyList(), removedApi, options("{}"), null).isEmpty())
        val disabled = options("""{"ai":{"architecture":{"semanticDiff":{"enabled":false}}}}""")
        assertTrue(checks.apply(emptyList(), removedApi, disabled, null).isEmpty())
    }

    @Test
    fun `provenanceChain enables the report and links persisted entries`() {
        val out = Path(TestFiles.createTestTempDirectory("karandash-provenance"), "chain.jsonl")
        val json = """{"architecture":{"provenanceChain":{"persistChain":${JsonPrimitive(out.toString())}}}}"""
        assertEquals(setOf(AiReportGenerator.PROVENANCE_CHAIN_REPORT), options(json).reportIds)
        val finding = Finding(Severity.WARNING, "m", "r")
        checks.persistProvenance(listOf(finding), removedApi, config(json))
        checks.persistProvenance(emptyList(), removedApi, config(json))
        val lines = SystemFileSystem
            .source(out)
            .buffered()
            .use { it.readString() }
            .lines()
            .filter { it.isNotBlank() }
        val entries = lines.map { KarandashJson.Default.parseToJsonElement(it).jsonObject }
        assertEquals(2, entries.size)
        assertEquals("", entries[0]["previousHash"]?.jsonPrimitive?.content)
        assertEquals(entries[0]["chainHash"], entries[1]["previousHash"])
        assertEquals("1", entries[0]["findingCount"]?.jsonPrimitive?.content)
    }

    @Test
    fun `provenanceChain off adds no report`() {
        assertTrue(options("""{"architecture":{"provenanceChain":false}}""").reportIds.isEmpty())
    }

    @Test
    fun `dependencies reports a sibling MR touching the same file`() =
        runTest {
            val statsDir = TestFiles.createTestTempDirectory("karandash-deps").toString()
            val opts = options("""{"dependencies":{"detectionWindow":1}}""")
            val first = createTestMergeRequest(
                title = "First",
                changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED))
            ).copy(id = "1")
            val second = createTestMergeRequest(
                title = "Second",
                changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED))
            ).copy(id = "2")
            assertTrue(checks.crossMrDependencies(first, opts, statsDir).isEmpty())
            val findings = checks.crossMrDependencies(second, opts, statsDir)
            assertEquals(listOf(AiArchitectureChecks.CROSS_MR_RULE), findings.map { it.rule })
            assertTrue("First" in findings.single().message && "src/A.kt" in findings.single().message, findings.single().message)
            val noConflicts = options("""{"dependencies":{"checkConflicts":false}}""")
            assertTrue(checks.crossMrDependencies(second, noConflicts, statsDir).isEmpty())
            assertTrue(checks.crossMrDependencies(second, options("{}"), statsDir).isEmpty())
        }

    @Test
    fun `dependencies lists open siblings through the platform before the snapshot file`() =
        runTest {
            val statsDir = TestFiles.createTestTempDirectory("karandash-deps-api").toString()
            val opts = options("""{"dependencies":{"detectionWindow":1}}""")
            val mr = createTestMergeRequest(title = "Mine", changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED))).copy(id = "2")
            val remote = createTestMergeRequest(
                title = "Remote",
                changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED))
            ).copy(id = "9")
            val asked = mutableListOf<Pair<String, Int>>()
            val session = ModuleRegistryImpl().forRun()
            session.provide(
                RunServiceKeys.OPEN_MERGE_REQUESTS,
                OpenMergeRequestLister { branch, limit ->
                    asked += branch to limit
                    listOf(mr, remote)
                },
            )
            val findings = withContext(session.services) { checks.crossMrDependencies(mr, opts, statsDir) }
            session.close()
            assertEquals(listOf(mr.targetBranch to AiArchitectureChecks.MAX_PLATFORM_SIBLINGS + 1), asked)
            assertTrue("Remote" in findings.single().message, findings.single().message)

            val failing = ModuleRegistryImpl().forRun()
            failing.provide(RunServiceKeys.OPEN_MERGE_REQUESTS, OpenMergeRequestLister { _, _ -> throw ProviderException("down") })
            val fallback = withContext(failing.services) { checks.crossMrDependencies(remote, opts, statsDir) }
            failing.close()
            assertTrue("Mine" in fallback.single().message, "falls back to the snapshot recorded above")
        }

    @Test
    fun `mutationTesting scores the run rules against synthetic MRs`() =
        runTest {
            val session = ModuleRegistryImpl().forRun()
            session.provide(
                RunServiceKeys.RULE_VALIDATOR,
                MergeRequestValidator { mr ->
                    if (mr.draft) listOf(Finding(Severity.WARNING, "draft", "draft")) else emptyList()
                },
            )
            val opts = options("""{"mutationTesting":true}""")
            val findings = withContext(session.services) { checks.mutationTesting(opts) }
            session.close()
            val total = RuleMutationTester.generateMutations().size
            assertTrue(findings.all { it.rule == AiArchitectureChecks.MUTATION_TESTING_RULE })
            assertTrue("(1/$total caught)" in findings.first().message, findings.first().message)
            assertEquals(total - 1, findings.size - 1)
            assertTrue(findings.none { "'draft-mr'" in it.message })
            assertTrue(checks.mutationTesting(opts).isEmpty())
            assertTrue(checks.mutationTesting(options("{}")).isEmpty())
        }

    @Test
    fun `policyVerification reports contradicting policies`() {
        val raw = config(
            """
            {"policies":[
              {"name":"strict","when":"label('hotfix')","modules":["size"],"severity":"error"},
              {"name":"lax","when":"label('hotfix')","modules":["size"],"severity":"info"}
            ]}
            """.trimIndent(),
        )
        val findings = checks.apply(emptyList(), removedApi, options("""{"architecture":{"policyVerification":true}}"""), raw)
        assertTrue(findings.isNotEmpty())
        assertTrue(findings.all { it.rule == AiArchitectureChecks.POLICY_VERIFICATION_RULE })
        val logged = checks.apply(
            emptyList(),
            removedApi,
            options("""{"architecture":{"policyVerification":{"reportIssues":false}}}"""),
            raw
        )
        assertTrue(logged.isEmpty())
    }

    @Test
    fun `dependencies and mutation findings follow the report language`() =
        runTest {
            RussianTranslationModule.register()
            PolishTranslationModule.register()
            val statsDir = TestFiles.createTestTempDirectory("karandash-deps-ru").toString()
            val opts = options("""{"dependencies":{"detectionWindow":1}}""")
            val sibling = createTestMergeRequest(
                title = "Other",
                changes = listOf(FileChange("src/A.kt", ChangeType.MODIFIED))
            ).copy(id = "1")
            val mine = createTestMergeRequest(title = "Mine", changes = listOf(FileChange("src/A.kt", ChangeType.DELETED))).copy(id = "2")
            checks.crossMrDependencies(sibling, opts, statsDir)
            val findings = checks.crossMrDependencies(mine, opts, statsDir)
            assertEquals(
                listOf(
                    "MR 'Other' (1) also changes src/A.kt: coordinate the merge order",
                    "Current MR deletes/renames files that MR 'Other' modifies: src/A.kt",
                ),
                findings.map { it.message },
            )
            assertEquals(
                listOf(
                    "MR «Other» (1) тоже меняет src/A.kt: согласуйте порядок слияния",
                    "Этот MR удаляет или переименовывает файлы, которые меняет MR «Other»: src/A.kt",
                ),
                findings.map { it.inLanguage("ru").message },
            )
            assertEquals(
                "Ten MR usuwa lub zmienia nazwy plików, które modyfikuje MR „Other”: src/A.kt",
                findings.last().inLanguage("pl").message,
            )

            val session = ModuleRegistryImpl().forRun()
            session.provide(RunServiceKeys.RULE_VALIDATOR, MergeRequestValidator { emptyList() })
            val mutation = withContext(session.services) { checks.mutationTesting(options("""{"mutationTesting":true}""")) }
            session.close()
            val total = RuleMutationTester.generateMutations().size
            assertEquals("Оценка мутационного тестирования правил: 0% (поймано 0 из $total)", mutation.first().inLanguage("ru").message)
            assertTrue(mutation[1].inLanguage("ru").message.startsWith("Мутация «"), mutation[1].inLanguage("ru").message)
        }
}
