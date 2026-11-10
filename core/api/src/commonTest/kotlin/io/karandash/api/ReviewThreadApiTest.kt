package io.karandash.api

import io.karandash.api.finding.ReviewAward
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReviewThreadApiTest {
    private open class StubPlatform : PlatformServiceModule {
        override val id = "stub"
        override val name = "Stub"
        override val version = "1.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("stub")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("stub")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {}

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {}

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) {}

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) {}

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) {}

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) {}

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
        ) = ""

        override suspend fun shutdown() {}
    }

    private val thread =
        ReviewThread(
            id = "d1",
            resolved = true,
            position = ReviewThreadPosition(newPath = "a.kt", oldPath = "a.kt", newLine = 3, oldLine = null),
            notes =
                listOf(
                    ReviewNote(
                        id = "7",
                        body = "hello",
                        author = "bot",
                        createdAt = "2026-10-20T10:00:00Z",
                        resolvable = true,
                        resolved = true,
                        resolvedBy = "alice",
                        awards = listOf(ReviewAward(name = "thumbsdown", user = "alice")),
                    ),
                ),
        )

    @Test
    fun `thread operations default to unsupported`() =
        runTest {
            val platform = StubPlatform()
            assertFailsWith<UnsupportedPlatformOperationException> { platform.listReviewThreads("p", "1") }
            assertIs<UnsupportedOperationException>(platform.resolveThread("p", "1", "d1", "done").exceptionOrNull())
            assertIs<UnsupportedOperationException>(platform.updateNote("p", "1", "d1", "7", "body").exceptionOrNull())
            assertIs<UnsupportedOperationException>(platform.replyToThread("p", "1", "d1", "body").exceptionOrNull())
            assertNull(platform.isProjectMember("p", "alice"))
            assertFailsWith<UnsupportedPlatformOperationException> { platform.fetchFileContent("p", "abc", "a.kt") }
            assertEquals(false, platform.supportsFileContent)
        }

    @OptIn(ExperimentalKarandashApi::class)
    @Test
    fun `pipeline extensions need no head content by default`() {
        val extension =
            object : PipelineExtension {
                override val id = "x"
                override val type = ModuleType.PIPELINE_EXTENSION
                override val name = "X"
                override val version = "1"

                override suspend fun initialize(context: PluginContext) = true
            }
        assertEquals(false, extension.needsHeadContent(null))
        assertEquals(
            JsonPrimitive(false),
            ModuleDescriptor.of(extension).properties[ModuleDescriptor.NEEDS_HEAD_CONTENT]
        )
    }

    @Test
    fun `thread operations return what a platform overrides`() =
        runTest {
            val platform =
                object : StubPlatform() {
                    override suspend fun listReviewThreads(
                        projectId: String,
                        mrId: String,
                    ) = listOf(thread)

                    override suspend fun resolveThread(
                        projectId: String,
                        mrId: String,
                        threadId: String,
                        note: String?,
                    ) = Result.success(Unit)

                    override suspend fun fetchFileContent(
                        projectId: String,
                        ref: String,
                        path: String,
                    ) = "content"
                }
            assertEquals(listOf(thread), platform.listReviewThreads("p", "1"))
            assertTrue(platform.resolveThread("p", "1", "d1").isSuccess)
            assertEquals("content", platform.fetchFileContent("p", "abc", "a.kt"))
        }

    @Test
    fun `review thread round-trips through JSON`() {
        val json = KarandashJson.Default.encodeToString(ReviewThread.serializer(), thread)
        assertEquals(thread, KarandashJson.Default.decodeFromString(ReviewThread.serializer(), json))
    }
}
