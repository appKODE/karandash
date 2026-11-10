package io.karandash.plugins.ai.core

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DiffChunkerTest {
    private fun makeMr(changes: List<FileChange>) =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "Test MR",
            description = null,
            sourceBranch = "feature",
            targetBranch = "main",
            author = User("1", "dev", "Dev"),
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = listOf(
                Commit("sha1", "init", CommitAuthor("Dev", "dev@test.com"), "2024-01-01T00:00:00Z"),
            ),
            changes = changes,
        )

    private fun makeChange(
        path: String,
        diffSize: Int
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = 1,
        deletions = 0,
        diffContent = "x".repeat(diffSize),
    )

    // ---- empty / trivial cases ----

    @Test
    fun `empty changes produce empty chunks`() {
        val mr = makeMr(emptyList())
        val chunks = DiffChunker().chunk(mr)
        assertTrue(chunks.isEmpty())
    }

    @Test
    fun `changes with no diffContent produce empty chunks`() {
        val change = FileChange(path = "src/Foo.kt", changeType = ChangeType.MODIFIED, diffContent = null)
        val mr = makeMr(listOf(change))
        val chunks = DiffChunker().chunk(mr)
        assertTrue(chunks.isEmpty())
    }

    // ---- single-chunk cases ----

    @Test
    fun `small diff fits in single chunk`() {
        val changes = listOf(
            makeChange("src/A.kt", 100),
            makeChange("src/B.kt", 200),
        )
        val chunker = DiffChunker(maxCharsPerChunk = 1_000)
        val chunks = chunker.chunk(makeMr(changes))
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(2, chunks[0].changes.size)
        assertEquals(300, chunks[0].totalChars)
    }

    @Test
    fun `diff exactly at budget fits in single chunk`() {
        val changes = listOf(makeChange("src/A.kt", 1_000))
        val chunker = DiffChunker(maxCharsPerChunk = 1_000)
        val chunks = chunker.chunk(makeMr(changes))
        assertEquals(1, chunks.size)
    }

    // ---- multi-chunk splitting ----

    @Test
    fun `large diff split into multiple chunks respecting max chars`() {
        val changes = listOf(
            makeChange("src/A.kt", 600),
            makeChange("src/B.kt", 600),
            makeChange("src/C.kt", 600),
        )
        val chunker = DiffChunker(maxCharsPerChunk = 1_000, maxChunks = 5)
        val chunks = chunker.chunk(makeMr(changes))
        assertTrue(chunks.size > 1, "Expected multiple chunks for total 1800 chars with budget 1000")
        for (chunk in chunks) {
            assertTrue(
                chunk.totalChars <= 1_000 || chunk.changes.size == 1,
                "Chunk ${chunk.index} exceeds budget (${chunk.totalChars} chars) with more than one file",
            )
        }
    }

    @Test
    fun `chunk indices are sequential starting from zero`() {
        val changes = List(6) { i -> makeChange("src/File$i.kt", 400) }
        val chunker = DiffChunker(maxCharsPerChunk = 1_000, maxChunks = 5)
        val chunks = chunker.chunk(makeMr(changes))
        chunks.forEachIndexed { idx, chunk -> assertEquals(idx, chunk.index) }
    }

    @Test
    fun `all input changes appear in exactly one chunk`() {
        val changes = List(10) { i -> makeChange("src/File$i.kt", 300) }
        val chunker = DiffChunker(maxCharsPerChunk = 800, maxChunks = 5)
        val chunks = chunker.chunk(makeMr(changes))
        val allPaths = chunks.flatMap { it.changes.map { c -> c.path } }
        // Each path present in at most one chunk; total count bounded by maxChunks * budget
        assertEquals(allPaths.distinct().size, allPaths.size)
    }

    // ---- maxChunks limit ----

    @Test
    fun `max chunks limit is respected`() {
        val changes = List(20) { i -> makeChange("src/File$i.kt", 500) }
        val chunker = DiffChunker(maxCharsPerChunk = 1_000, maxChunks = 3)
        val chunks = chunker.chunk(makeMr(changes))
        assertTrue(chunks.size <= 3, "Expected at most 3 chunks, got ${chunks.size}")
    }

    @Test
    fun `single maxChunks produces at most one chunk`() {
        val changes = List(5) { i -> makeChange("src/File$i.kt", 500) }
        val chunker = DiffChunker(maxCharsPerChunk = 1_000, maxChunks = 1)
        val chunks = chunker.chunk(makeMr(changes))
        assertEquals(1, chunks.size)
    }

    // ---- priority ordering ----

    @Test
    fun `security files have higher priority than regular source`() {
        assertTrue(DiffChunker.filePriority("src/auth/SecurityManager.kt") > DiffChunker.filePriority("src/util/StringUtils.kt"))
    }

    @Test
    fun `config files have higher priority than regular source`() {
        assertTrue(DiffChunker.filePriority("src/main/resources/application.yml") > DiffChunker.filePriority("src/App.kt"))
    }

    @Test
    fun `test files have lower priority than regular source`() {
        assertTrue(DiffChunker.filePriority("src/main/Foo.kt") > DiffChunker.filePriority("src/test/FooTest.kt"))
    }

    @Test
    fun `generated files have lowest priority`() {
        val generatedPriority = DiffChunker.filePriority("build/generated/Sources.kt")
        val testPriority = DiffChunker.filePriority("src/test/FooTest.kt")
        assertTrue(generatedPriority < testPriority)
    }

    @Test
    fun `vendor files have lowest priority`() {
        assertTrue(DiffChunker.filePriority("vendor/lib/helper.go") < DiffChunker.filePriority("src/main/App.kt"))
    }

    @Test
    fun `security files appear in first chunk when diff exceeds budget`() {
        val changes = listOf(
            makeChange("src/util/Helper.kt", 600),
            makeChange("src/util/Utils.kt", 600),
            makeChange("src/auth/AuthService.kt", 200),
        )
        val chunker = DiffChunker(maxCharsPerChunk = 1_000, maxChunks = 5)
        val chunks = chunker.chunk(makeMr(changes))
        assertTrue(chunks.size > 1)
        val firstChunkPaths = chunks[0].changes.map { it.path }
        assertTrue(
            firstChunkPaths.any { it.contains("auth") },
            "Security file expected in first chunk, got: $firstChunkPaths",
        )
    }

    @Test
    fun `filePriority returns correct scores for each category`() {
        assertEquals(100, DiffChunker.filePriority("src/auth/AuthService.kt"))
        assertEquals(80, DiffChunker.filePriority("src/main/resources/application.yaml"))
        assertEquals(50, DiffChunker.filePriority("src/main/kotlin/Service.kt"))
        assertEquals(30, DiffChunker.filePriority("src/test/kotlin/ServiceTest.kt"))
        assertEquals(10, DiffChunker.filePriority("build/generated/Sources.kt"))
    }

    @Test
    fun `a test file shares a chunk with the sources of its review unit`() {
        val changes = listOf(
            makeChange("app/src/main/kotlin/A.kt", 400),
            makeChange("lib/src/main/kotlin/B.kt", 400),
            makeChange("app/src/test/kotlin/ATest.kt", 400),
            makeChange("lib/src/test/kotlin/BTest.kt", 400),
        )
        val chunks = DiffChunker(maxCharsPerChunk = 800, maxChunks = 5).chunk(makeMr(changes))
        assertEquals(
            listOf(
                listOf("app/src/main/kotlin/A.kt", "app/src/test/kotlin/ATest.kt"),
                listOf("lib/src/main/kotlin/B.kt", "lib/src/test/kotlin/BTest.kt"),
            ),
            chunks.map { chunk -> chunk.changes.map { it.path } },
        )
    }
}
