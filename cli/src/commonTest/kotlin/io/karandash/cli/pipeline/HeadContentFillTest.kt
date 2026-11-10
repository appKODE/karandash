package io.karandash.cli.pipeline

import io.karandash.api.PluginContext
import io.karandash.models.ChangeType
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.karandash.sdk.typed.AbstractTypedPlatformServiceModule
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HeadContentFillTest {
    private class ContentPlatform(
        private val size: Int = 3,
        override val supportsFileContent: Boolean = true,
    ) : AbstractTypedPlatformServiceModule<JsonObject>(JsonObject.serializer()) {
        val calls = mutableListOf<String>()

        override val id = "fake"
        override val name = "Fake"
        override val version = "1.0.0"

        override suspend fun initializeTyped(
            context: PluginContext,
            config: JsonObject,
        ): Boolean = true

        override suspend fun fetchFileContent(
            projectId: String,
            ref: String,
            path: String,
        ): String? {
            calls += "$projectId $ref $path"
            if (path.startsWith("broken")) error("raw endpoint failed")
            return if (path == "missing.kt") null else "x".repeat(size)
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

    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
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
    fun `changed files get head content at the head sha and deleted files are skipped`() =
        runTest {
            val platform = ContentPlatform()
            val changes = listOf(
                FileChange("a.kt", ChangeType.MODIFIED),
                FileChange("b.kt", ChangeType.ADDED),
                FileChange("c.kt", ChangeType.DELETED),
                FileChange("d.kt", ChangeType.RENAMED),
                FileChange("missing.kt", ChangeType.MODIFIED),
                FileChange("e.kt", ChangeType.MODIFIED, newContent = "kept"),
            )

            val result = withHeadContent(mr(changes, DiffRefs("base1", "head1", "start1")), platform, "g/r", NoOpLogger)

            assertEquals(listOf("a.kt", "b.kt", "d.kt", "missing.kt"), platform.calls.map { it.substringAfterLast(' ') })
            assertTrue(platform.calls.all { it.startsWith("g/r head1 ") })
            assertEquals(listOf("xxx", "xxx", null, "xxx", null, "kept"), result.changes.map { it.newContent })
        }

    @Test
    fun `head content falls back to the source branch and stops at the file cap`() =
        runTest {
            val platform = ContentPlatform()
            val changes = (1..MAX_HEAD_CONTENT_FILES + 5).map { FileChange("f$it.kt", ChangeType.MODIFIED) }

            val result = withHeadContent(mr(changes), platform, "g/r", NoOpLogger)

            assertEquals(MAX_HEAD_CONTENT_FILES, platform.calls.size)
            assertTrue(platform.calls.all { it.startsWith("g/r feature ") })
            assertTrue(result.changes.drop(MAX_HEAD_CONTENT_FILES).all { it.newContent == null })
        }

    @Test
    fun `head content fetched past the total byte cap is dropped`() =
        runTest {
            val platform = ContentPlatform(size = MAX_HEAD_CONTENT_TOTAL_BYTES / 2)
            val changes = (1..3).map { FileChange("f$it.kt", ChangeType.MODIFIED) }

            val result = withHeadContent(mr(changes), platform, "g/r", NoOpLogger)

            assertEquals(MAX_HEAD_CONTENT_TOTAL_BYTES / 2, result.changes[1].newContent?.length)
            assertNull(result.changes[2].newContent)
        }

    @Test
    fun `a platform without file content support gets no content calls`() =
        runTest {
            val platform = ContentPlatform(supportsFileContent = false)
            val changes = listOf(FileChange("a.kt", ChangeType.MODIFIED))

            val result = withHeadContent(mr(changes), platform, "g/r", NoOpLogger)

            assertTrue(platform.calls.isEmpty())
            assertNull(result.changes.single().newContent)
        }

    @Test
    fun `one failed lookup among files does not stop the rest`() =
        runTest {
            val platform = ContentPlatform()
            val logger = WarnLogger()
            val changes = listOf("a.kt", "broken.kt", "c.kt", "d.kt").map { FileChange(it, ChangeType.MODIFIED) }

            val result = withHeadContent(mr(changes), platform, "g/r", logger)

            assertEquals(4, platform.calls.size)
            assertEquals(listOf("xxx", null, "xxx", "xxx"), result.changes.map { it.newContent })
            assertTrue(logger.warnings.none { it.contains("partial") })
        }

    @Test
    fun `three consecutive failed lookups warn once that head content is partial and stop the rest`() =
        runTest {
            val platform = ContentPlatform()
            val logger = WarnLogger()
            val changes = listOf("a.kt", "broken1.kt", "broken2.kt", "broken3.kt", "e.kt", "f.kt")
                .map { FileChange(it, ChangeType.MODIFIED) }

            val result = withHeadContent(mr(changes), platform, "g/r", logger)

            assertEquals(listOf("a.kt", "broken1.kt", "broken2.kt", "broken3.kt"), platform.calls.map { it.substringAfterLast(' ') })
            assertEquals(1, logger.warnings.count { it.contains("partial") })
            assertEquals(listOf("xxx", null, null, null, null, null), result.changes.map { it.newContent })
        }

    @Test
    fun `base files are read at the base sha and never at the MR head`() =
        runTest {
            val platform = ContentPlatform()
            val m = mr(emptyList(), DiffRefs("base1", "head1", "start1"))

            val files = withBaseFiles(m, platform, "g/r", setOf("duo.yaml", "missing.kt"), NoOpLogger)

            assertEquals(listOf("g/r base1 duo.yaml", "g/r base1 missing.kt"), platform.calls.sorted())
            assertEquals(mapOf("duo.yaml" to "xxx"), files)
        }

    @Test
    fun `base files fall back to the target branch without diff refs`() =
        runTest {
            val platform = ContentPlatform()

            withBaseFiles(mr(emptyList()), platform, "g/r", setOf("duo.yaml"), NoOpLogger)

            assertEquals(listOf("g/r main duo.yaml"), platform.calls)
        }

    @Test
    fun `a platform without file content and a failed lookup yield no base file`() =
        runTest {
            val logger = WarnLogger()
            assertTrue(withBaseFiles(mr(emptyList()), ContentPlatform(supportsFileContent = false), "g/r", setOf("a"), logger).isEmpty())

            val files = withBaseFiles(mr(emptyList()), ContentPlatform(), "g/r", setOf("broken.yaml"), logger)

            assertTrue(files.isEmpty())
            assertTrue(logger.warnings.any { "broken.yaml" in it }, "failed lookup not warned about: ${logger.warnings}")
        }
}
