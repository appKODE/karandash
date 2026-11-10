package io.karandash.plugins.ai

import io.karandash.api.ModuleRegistry
import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.ai.AiAnalyzerContext
import io.karandash.api.ai.AiAnalyzerModule
import io.karandash.api.finding.ReviewCoverage
import io.karandash.api.finding.SkipCategory
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Finding
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** An MR whose every file is whitespace-only, a lockfile, generated or docs skips AI review with a typed reason. */
class AiTrivialDiffSkipTest {
    private fun context(extra: Map<String, Any?> = emptyMap()): PluginContext =
        object : PluginContext {
            override val pluginConfig: JsonElement = mapToJsonElement(
                mapOf("provider" to "custom", "baseUrl" to "http://localhost:1234/v1", "model" to "test-model") + extra,
            )

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}

            override val hostVersion: String = "1.0.0-test"
            override val workingDirectory: String = "/tmp/test"
        }

    private suspend fun calls(
        changes: List<FileChange>,
        extra: Map<String, Any?> = emptyMap(),
        check: (AiRuleModule) -> Unit = {},
    ): Int {
        var calls = 0
        val analyzer = CountingAnalyzer { calls++ }
        val registry = object : ModuleRegistry by buildTestRegistry() {
            override fun aiAnalyzers(): List<AiAnalyzerModule> = listOf(analyzer)

            override fun findAiAnalyzer(analyzerId: String): AiAnalyzerModule? = analyzer.takeIf { it.analyzerId == analyzerId }
        }
        val module = AiRuleModule(registry)
        assertTrue(module.initialize(context(extra)))
        module.validate(RULE, createTestMergeRequest(changes = changes), JsonObject(emptyMap()))
        check(module)
        module.shutdown()
        return calls
    }

    @Test
    fun `docs only MR is skipped with a typed reason and zero coverage`() =
        runTest {
            val calls = calls(listOf(change("README.md", CODE_DIFF), change("docs/guide.rst", CODE_DIFF))) { module ->
                val info = module.skippedRuleReasons().getValue(RULE)
                assertEquals(SkipCategory.AI_TRIVIAL_DIFF, info.category)
                assertEquals("trivial diff: docs-only", info.reason)
                assertEquals(ReviewCoverage(totalFiles = 2, reviewedFiles = 0), module.reviewProvenance()?.coverage)
            }

            assertEquals(0, calls)
        }

    @Test
    fun `code change is reviewed`() =
        runTest {
            val calls = calls(listOf(change("src/A.kt", CODE_DIFF))) { module ->
                assertTrue(module.skippedRuleReasons().isEmpty(), module.skippedRuleReasons().toString())
            }

            assertEquals(1, calls)
        }

    @Test
    fun `whitespace only and lockfile changes are skipped`() =
        runTest {
            val whitespace = change("src/A.kt", "@@ -1,2 +1,2 @@\n-fun a()  {\n-\treturn\n+fun a() {\n+    return")
            val calls = calls(listOf(whitespace, change("yarn.lock", CODE_DIFF), change("build/generated/Api.kt", CODE_DIFF))) {
                assertEquals("trivial diff: whitespace-only, lockfile, generated", it.skippedRuleReasons().getValue(RULE).reason)
            }

            assertEquals(0, calls)
        }

    @Test
    fun `a lockfile next to a code change is reviewed`() =
        runTest {
            assertEquals(1, calls(listOf(change("yarn.lock", CODE_DIFF), change("src/A.kt", CODE_DIFF))))
        }

    @Test
    fun `a code file without a diff or an empty MR is not treated as trivial`() =
        runTest {
            val noDiff = FileChange("src/A.kt", ChangeType.MODIFIED, additions = 400, deletions = 0, diffContent = null)

            assertEquals(1, calls(listOf(noDiff)))
            assertEquals(1, calls(emptyList()))
        }

    @Test
    fun `a lockfile or doc whose diff the platform left out is still trivial`() =
        runTest {
            val lock = FileChange("yarn.lock", ChangeType.MODIFIED, additions = 4000, deletions = 3000, diffContent = null)
            val doc = FileChange("README.md", ChangeType.MODIFIED, additions = 400, deletions = 0, diffContent = null)

            assertEquals(0, calls(listOf(lock, doc)))
        }

    @Test
    fun `swapped statements are reviewed while a reindent is skipped`() =
        runTest {
            val swap = change("src/A.kt", "@@ -1,2 +1,2 @@\n-val a = 1\n-val b = 2\n+val b = 2\n+val a = 1")
            val reindent = change("src/A.kt", "@@ -1,2 +1,2 @@\n-val a = 1\n-val b = 2\n+  val a = 1\n+  val b = 2")

            assertEquals(1, calls(listOf(swap)))
            assertEquals(0, calls(listOf(reindent)))
        }

    @Test
    fun `removing the space between two words is reviewed while collapsing a double space is skipped`() =
        runTest {
            assertEquals(1, calls(listOf(change("src/A.kt", "@@ -1,1 +1,1 @@\n-val x = 1\n+valx = 1"))))
            assertEquals(0, calls(listOf(change("src/A.kt", "@@ -1,1 +1,1 @@\n-val  x = 1\n+val x = 1"))))
        }

    @Test
    fun `a reindent in an indentation significant file is reviewed`() =
        runTest {
            val reindent = "@@ -1,2 +1,2 @@\n-if a:\n-    go()\n+if a:\ngo()"
            val shifted = "@@ -1,1 +1,1 @@\n-  key: 1\n+key: 1"

            assertEquals(1, calls(listOf(change("app/main.py", reindent))))
            assertEquals(1, calls(listOf(change("conf/app.yaml", shifted))))
            assertEquals(1, calls(listOf(change("ci.yml", shifted))))
            assertEquals(1, calls(listOf(change("Makefile", "@@ -1,1 +1,1 @@\n-\tgo build\n+    go build"))))
            assertEquals(1, calls(listOf(change("rules.mk", "@@ -1,1 +1,1 @@\n-\tgo build\n+    go build"))))
        }

    @Test
    fun `sizePolicy skip trivialDiffs false reviews a docs only MR`() =
        runTest {
            val off = mapOf("sizePolicy" to mapOf("skip" to mapOf("trivialDiffs" to false)))

            assertEquals(1, calls(listOf(change("README.md", CODE_DIFF)), off))
        }

    private fun change(
        path: String,
        diff: String,
    ) = FileChange(path, ChangeType.MODIFIED, additions = 1, deletions = 1, diffContent = diff)

    private class CountingAnalyzer(
        private val onCall: () -> Unit,
    ) : AiAnalyzerModule {
        override val analyzerId: String = RULE
        override val id: String = RULE
        override val name: String = RULE
        override val version: String = "test"
        override val type: ModuleType get() = ModuleType.AI_ANALYZER
        override val featureKey: String = RULE
        override val defaultEnabled: Boolean = true
        override val estimatedTokens: Int = 100

        override suspend fun initialize(context: PluginContext): Boolean = true

        override suspend fun analyze(context: AiAnalyzerContext): List<Finding> {
            onCall()
            return emptyList()
        }
    }

    private companion object {
        const val RULE = "review"
        const val CODE_DIFF = "@@ -1,1 +1,1 @@\n-val a = 1\n+val a = 2"
    }
}
