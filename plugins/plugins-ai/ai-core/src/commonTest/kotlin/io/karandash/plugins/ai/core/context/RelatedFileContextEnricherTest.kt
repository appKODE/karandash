package io.karandash.plugins.ai.core.context

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RelatedFileContextEnricherTest {
    // ── isRelatedFileContextEnabled gating ──────────────────────────────────

    @Test
    fun `disabled by default when config is JsonNull`() {
        assertFalse(isRelatedFileContextEnabled(JsonNull))
    }

    @Test
    fun `disabled by default when contextQuality block absent`() {
        val config = Json.parseToJsonElement("""{"provider":"custom"}""")
        assertFalse(isRelatedFileContextEnabled(config))
    }

    @Test
    fun `enabled true is respected`() {
        val config = Json.parseToJsonElement(
            """{"contextQuality":{"relatedFiles":{"enabled":true}}}""",
        )
        assertTrue(isRelatedFileContextEnabled(config))
    }

    @Test
    fun `enabled false is respected`() {
        val config = Json.parseToJsonElement(
            """{"contextQuality":{"relatedFiles":{"enabled":false}}}""",
        )
        assertFalse(isRelatedFileContextEnabled(config))
    }

    // ── provideBlock respects the gate ──────────────────────────────────────

    private fun testContext(workingDir: String = "/tmp/repo") = TestPluginContext(JsonNull, workingDirectory = workingDir)

    private fun mrWith(vararg changes: FileChange): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            author = User(username = "u", name = "U"),
            sourceBranch = "feat",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = changes.toList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun change(path: String): FileChange = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1)

    @Test
    fun `provideBlock returns null when not enabled via config`() =
        runTest {
            val enricher = RelatedFileContextEnricher()
            enricher.initialize(testContext())
            val mr = mrWith(change("src/Foo.kt"))
            val result = enricher.provideBlock(mr, mr.changes, JsonNull)
            assertNull(result)
        }

    @Test
    fun `finds the test file and lists each directory once per run`() =
        runTest {
            val repo = TestFiles.createTestTempDirectory("related")
            try {
                val test = Path(repo, "src/test/kotlin/FooTest.kt")
                TestFiles.writeText(test, "class FooTest")
                val enricher = RelatedFileContextEnricher()
                enricher.initialize(testContext(repo.toString()))
                val mr = mrWith(change("src/main/kotlin/Foo.kt"), change("src/main/kotlin/Bar.kt"))
                val config = Json.parseToJsonElement("""{"contextQuality":{"relatedFiles":{"enabled":true}}}""")

                val first = enricher.provideBlock(mr, mr.changes, config)
                SystemFileSystem.delete(test)
                val second = enricher.provideBlock(mr, mr.changes, config)

                assertNotNull(first)
                assertTrue("src/test/kotlin/FooTest.kt" in first.content)
                assertFalse("BarTest" in first.content)
                assertEquals(first.content, second?.content) // directory listing reused, not re-probed
            } finally {
                TestFiles.deleteRecursively(repo)
            }
        }

    // ── testCandidates path heuristics ──────────────────────────────────────

    @Test
    fun `testCandidates generates maven layout test path for Kotlin source`() {
        val candidates = testCandidates("src/main/kotlin/com/example/Foo.kt")
        assertTrue(candidates.any { it.endsWith("FooTest.kt") && it.contains("src/test/") })
    }

    @Test
    fun `testCandidates generates flat layout sibling test for Kotlin source`() {
        val candidates = testCandidates("src/Foo.kt")
        assertTrue(candidates.any { it == "src/FooTest.kt" })
    }

    @Test
    fun `testCandidates generates python test path`() {
        val candidates = testCandidates("app/utils.py")
        assertTrue(candidates.any { it.contains("test_utils.py") })
    }

    @Test
    fun `testCandidates generates js test paths`() {
        val candidates = testCandidates("src/Button.tsx")
        assertTrue(candidates.any { it.contains("Button.test.tsx") || it.contains("Button.spec.tsx") })
    }

    @Test
    fun `testCandidates handles top-level file`() {
        val candidates = testCandidates("Main.kt")
        assertTrue(candidates.any { it == "MainTest.kt" })
    }

    @Test
    fun `testCandidates returns distinct candidates`() {
        val candidates = testCandidates("src/Foo.kt")
        assertEquals(candidates.size, candidates.distinct().size)
    }

    @Test
    fun `testCandidates does not crash on unusual input`() {
        assertNotNull(testCandidates(""))
        assertNotNull(testCandidates("nodirectory"))
        assertNotNull(testCandidates("a/b/c/d/e/f/g.rs"))
    }

    @Test
    fun `module id is stable`() {
        assertEquals("related-file-context", RelatedFileContextEnricher.MODULE_ID)
    }

    @Test
    fun `enricher priority is 120`() {
        assertEquals(120, RelatedFileContextEnricher.ENRICHER_PRIORITY)
    }

    @Test
    fun `testCandidates generates spec variant for typescript`() {
        val candidates = testCandidates("src/api.ts")
        assertTrue(candidates.any { it.contains("api.spec.ts") })
    }

    // ── sources.callers ─────────────────────────────────────────────────────

    @Test
    fun `callers lists call sites of changed and removed symbols only when sources callers is on`() =
        runTest {
            val root = TestFiles.createTestTempDirectory("callers")
            try {
                TestFiles.writeText(Path(root, "src/Billing.kt"), "fun computeTotal(x: Int) = x\n")
                TestFiles.writeText(Path(root, "src/Cart.kt"), "fun pay() {\n    val t = computeTotal(1)\n    legacyFee (t)\n}\n")
                TestFiles.writeText(Path(root, "src/Other.kt"), "val computeTotalCount = 2\n")
                TestFiles.writeText(Path(root, "src/Overload.kt"), "fun computeTotal(x: Double) = x\n")
                TestFiles.writeText(Path(root, "native/util.cpp"), "#include \"util.h\"\nint run() { return scaleBy(2); }\n")
                val enricher = RelatedFileContextEnricher()
                enricher.initialize(testContext(root.toString()))
                val diff = "@@ -1,2 +1,1 @@\n-fun legacyFee(x: Int) = x\n-fun computeTotal(x: Long) = x\n+fun computeTotal(x: Int) = x"
                val cDiff = "@@ -0,0 +1 @@\n+static int scaleBy(int n) {"
                val changes = listOf(
                    FileChange("src/Billing.kt", ChangeType.MODIFIED, 1, 2, diff),
                    FileChange("native/scale.cpp", ChangeType.ADDED, 1, 0, cDiff),
                )
                val on = """{"contextQuality":{"relatedFiles":{"enabled":true,"sources":{"callers":true}}}}"""
                val off = """{"contextQuality":{"relatedFiles":{"enabled":true}}}"""

                val block = assertNotNull(enricher.provideBlock(mrWith(), changes, Json.parseToJsonElement(on)))
                assertEquals(RelatedFileContextEnricher.CALLERS_HEADING, block.heading)
                assertTrue("src/Cart.kt:2: val t = computeTotal(1)" in block.content, block.content)
                assertTrue("src/Cart.kt:3: legacyFee (t)" in block.content, block.content)
                assertTrue("native/util.cpp:2:" in block.content, block.content)
                assertFalse("Other.kt" in block.content || "Billing.kt:" in block.content || "Overload.kt" in block.content, block.content)
                assertNull(enricher.provideBlock(mrWith(), changes, Json.parseToJsonElement(off)))
            } finally {
                TestFiles.deleteRecursively(root)
            }
        }
}
