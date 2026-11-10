package io.karandash.api

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.FetchOptions
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull

class PlatformServiceDefaultsTest {
    private class StubPlatform(
        private val existing: String? = null,
        private val ci: CIEnvironment? = null,
        private val resolved: Int = 0,
    ) : PlatformServiceModule {
        override val id = "stub"
        override val name = "Stub"
        override val version = "1.0.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun shutdown() = Unit

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = error("fetched $projectId/$mrId")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("fetched $url")

        override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? = ci

        override suspend fun resolveMarkedInlineComments(
            projectId: String,
            mrId: String,
            marker: String,
        ): Int = resolved

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) = Unit

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) = Unit

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) = Unit

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) = Unit

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) = Unit

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) = Unit

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
        ) = Unit

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = existing

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ) = ""
    }

    @Test
    fun `suggestion block prefixes every line as an addition`() =
        runTest {
            assertEquals("```diff\n+ a\n+ b\n```", StubPlatform().renderSuggestionBlock("a\nb"))
            assertEquals("```diff\n+ \n```", StubPlatform().renderSuggestionBlock(""))
        }

    @Test
    fun `matching comments and file scoped resolution delegate to the single variants`() =
        runTest {
            assertEquals(listOf("c1"), StubPlatform(existing = "c1").findAllMatchingComments("p", "1", "m"))
            assertEquals(emptyList(), StubPlatform().findAllMatchingComments("p", "1", "m"))
            assertEquals(3, StubPlatform(resolved = 3).resolveMarkedInlineCommentsForFiles("p", "1", "m", setOf("a.kt")))
        }

    @Test
    fun `optional comment operations default to no-ops`() =
        runTest {
            val stub = StubPlatform()
            assertEquals(0, stub.deleteMarkedInlineComments("p", "1", "m"))
            assertEquals(0, stub.resolveMarkedInlineComments("p", "1", "m"))
            assertFailsWith<UnsupportedPlatformOperationException> { stub.listDismissedInlineComments("p", "1", "m") }
            assertFailsWith<UnsupportedPlatformOperationException> { stub.listReviewThreads("p", "1") }
            stub.deleteComment("p", "1", "c")
        }

    @Test
    fun `thread operations fail as unsupported by default`() =
        runTest {
            val stub = StubPlatform()
            val results = mapOf(
                "resolveThread" to stub.resolveThread("p", "1", "t"),
                "replyToThread" to stub.replyToThread("p", "1", "t", "b"),
                "updateNote" to stub.updateNote("p", "1", "t", "n", "b"),
            )
            results.forEach { (operation, result) ->
                val error = assertIs<UnsupportedPlatformOperationException>(result.exceptionOrNull(), operation)
                assertEquals(operation, error.operation)
                assertEquals("$operation is not supported by this platform", error.message)
            }
        }

    @Test
    fun `reviewer and merge operations fail as unsupported by default`() =
        runTest {
            val stub = StubPlatform()
            val results = mapOf(
                "addReviewers" to stub.addReviewers("p", "1", listOf("u")),
                "removeReviewers" to stub.removeReviewers("p", "1", listOf("u")),
                "mergeMr" to stub.mergeMr("p", "1"),
                "closeMr" to stub.closeMr("p", "1"),
                "reopenMr" to stub.reopenMr("p", "1"),
            )
            results.forEach { (operation, result) ->
                val error = assertIs<UnsupportedPlatformOperationException>(result.exceptionOrNull(), operation)
                assertEquals(operation, error.operation)
            }
        }

    @OptIn(ExperimentalKarandashApi::class)
    @Test
    fun `feedback and capability lookups default to empty`() =
        runTest {
            val stub = StubPlatform()
            assertEquals(emptyList(), stub.fetchFindingFeedback("p", "1").getOrThrow())
            assertEquals(emptySet(), stub.unavailableMergeRequestFields())
            assertFalse(stub.supports(PlatformOperation.FILE_CONTENT))
        }

    @Test
    fun `detect confidence is null without a ci environment`() =
        runTest {
            assertNull(StubPlatform().detectConfidence { null })
        }

    @Test
    fun `detect confidence scores a detected ci environment high`() =
        runTest {
            val ci = CIEnvironment("stub", "p", "1", null, null)
            val score = checkNotNull(StubPlatform(ci = ci).detectConfidence { null })

            assertEquals("stub", score.providerId)
            assertEquals(0.9, score.score)
            assertEquals("ci:stub", score.source)
            assertEquals(ci, score.ciEnvironment)
        }

    @Test
    fun `fetch with options delegates to the plain fetch`() =
        runTest {
            val stub = StubPlatform()
            val byId = assertFailsWith<IllegalStateException> { stub.fetchMergeRequest("p", "7", FetchOptions()) }
            val byUrl = assertFailsWith<IllegalStateException> { stub.fetchMergeRequestByUrl("u", FetchOptions()) }

            assertEquals("fetched p/7", byId.message)
            assertEquals("fetched u", byUrl.message)
        }

    @Test
    fun `provider lookups default to absent`() =
        runTest {
            val stub = StubPlatform()
            assertFalse(stub.supportsFileContent)
            assertFailsWith<UnsupportedPlatformOperationException> { stub.fetchFileContent("p", "main", "a.kt") }
            assertEquals(emptyList(), stub.fetchFileBlame("p", "a.kt", "main"))
            assertNull(stub.isProjectMember("p", "u"))
            assertFailsWith<UnsupportedPlatformOperationException> { stub.fetchApprovalSignals("p", "1") }
            assertNull(stub.currentUsername())
            assertNull(stub.parseMergeRequestUrl("u"))
            assertNull(stub.parseGitRemoteUrl("u"))
            assertNull(stub.authTokenHint())
            assertNull(stub.permissionScope())
            assertNull(stub.tokenLocationHint())
            assertNull(stub.notFoundGuidance())
            assertEquals(emptySet(), stub.aliases())
            assertNull(stub.rateLimitStatus())
            assertNull(stub.checkAuth())
            assertFailsWith<UnsupportedPlatformOperationException> { stub.listOpenMergeRequestIds("p", "main", 5) }
            assertFailsWith<UnsupportedPlatformOperationException> { stub.searchMergeRequests("p", null, null, 5) }
        }
}
