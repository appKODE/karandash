package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.models.BlameCommit
import io.karandash.models.BlameEntry
import io.karandash.models.ChangeType
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BlameFillTest {
    private val entry = BlameEntry(1, 3, BlameCommit("abc", CommitAuthor("Ann", "ann@example.com"), "init", "2026-01-01T00:00:00Z"))

    private class BlamePlatform(
        private val entry: BlameEntry,
    ) : AbstractTypedPlatformServiceModule<JsonObject>(JsonObject.serializer()) {
        val calls = mutableListOf<String>()
        private var inFlight = 0
        var maxInFlight = 0

        override val id = "fake"
        override val name = "Fake"
        override val version = "1.0.0"

        override suspend fun initializeTyped(
            context: PluginContext,
            config: JsonObject,
        ): Boolean = true

        override suspend fun fetchFileBlame(
            projectId: String,
            filePath: String,
            ref: String,
        ): List<BlameEntry> {
            calls += "$projectId $filePath $ref"
            inFlight++
            maxInFlight = maxOf(maxInFlight, inFlight)
            delay(10)
            inFlight--
            if (filePath == "broken.kt") error("blame endpoint failed")
            return listOf(entry)
        }

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ): String = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) = Unit

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = null

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ): String = ""
    }

    private fun mr(
        changes: List<FileChange>,
        diffRefs: DiffRefs? = null,
    ) = MergeRequest(
        id = "1",
        iid = 7,
        title = "t",
        description = null,
        author = User(username = "dev", name = "Dev"),
        sourceBranch = "feature",
        targetBranch = "main",
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        changes = changes,
        commits = emptyList(),
        diffRefs = diffRefs,
    )

    @Test
    fun `modified and deleted files get blame at the base commit and added files are skipped`() =
        runTest {
            val platform = BlamePlatform(entry)
            val changes = listOf(
                FileChange("a.kt", ChangeType.MODIFIED),
                FileChange("b.kt", ChangeType.ADDED),
                FileChange("c.kt", ChangeType.DELETED),
                FileChange("broken.kt", ChangeType.MODIFIED),
            )

            val result = withBlame(mr(changes, DiffRefs("base1", "head1", "start1")), platform, "g/r", NoOpLogger)

            assertEquals(listOf("g/r a.kt base1", "g/r c.kt base1", "g/r broken.kt base1"), platform.calls)
            assertEquals(listOf(listOf(entry), emptyList(), listOf(entry), emptyList()), result.changes.map { it.blameData })
        }

    @Test
    fun `blame falls back to the target branch and stops at the file cap`() =
        runTest {
            val platform = BlamePlatform(entry)
            val changes = (1..MAX_BLAME_FILES + 5).map { FileChange("f$it.kt", ChangeType.MODIFIED) }

            val result = withBlame(mr(changes), platform, "g/r", NoOpLogger)

            assertEquals(MAX_BLAME_FILES, platform.calls.size)
            assertTrue(platform.calls.all { it.endsWith(" main") })
            assertTrue(result.changes.drop(MAX_BLAME_FILES).all { it.blameData.isEmpty() })
        }

    @Test
    fun `blame lookups run in parallel up to the limit`() =
        runTest {
            val platform = BlamePlatform(entry)
            val changes = (1..BLAME_PARALLELISM * 3).map { FileChange("f$it.kt", ChangeType.MODIFIED) }

            val result = withBlame(mr(changes), platform, "g/r", NoOpLogger)

            assertEquals(BLAME_PARALLELISM, platform.maxInFlight)
            assertEquals(changes.map { it.path }, result.changes.map { it.path })
            assertTrue(result.changes.all { it.blameData == listOf(entry) })
        }
}
