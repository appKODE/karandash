package io.karandash.api

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.platform.RateLimitInfo
import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlatformServiceExtensionsTest {
    /** Minimal PlatformServiceModule for testing default implementations. */
    private val testModule = object : PlatformServiceModule {
        override val id = "test-platform"
        override val name = "Test Platform"
        override val version = "1.0.0"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String
        ): MergeRequest = error("stub")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("stub")

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>
        ) {}

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean
        ) {}

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String
        ) {}

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String
        ) {}

        override suspend fun approve(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun unapprove(
            projectId: String,
            mrId: String
        ) {}

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String
        ) = ""

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String
        ) {}

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String
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

    @Test
    fun `default supports is false for every operation`() =
        runTest {
            assertTrue(PlatformOperation.entries.none { testModule.supports(it) })
        }

    // ── MergeOptions tests ──

    @Test
    fun `MergeOptions defaults are sensible`() {
        val opts = MergeOptions()
        assertFalse(opts.squash)
        assertFalse(opts.deleteSourceBranch)
        assertNull(opts.commitMessage)
    }

    @Test
    fun `MergeOptions serializes round-trip`() {
        val opts = MergeOptions(squash = true, deleteSourceBranch = true, commitMessage = "Merge!")
        val json = KarandashJson.Default.encodeToString(opts)
        val decoded = KarandashJson.Default.decodeFromString<MergeOptions>(json)
        assertEquals(opts, decoded)
    }

    // ── RateLimitInfo tests ──

    @Test
    fun `RateLimitInfo isNearLimit true when remaining below 10 percent`() {
        val info = RateLimitInfo(remaining = 5, limit = 100, resetEpochSeconds = 1000L)
        assertTrue(info.isNearLimit)
    }

    @Test
    fun `RateLimitInfo isNearLimit false when remaining above 10 percent`() {
        val info = RateLimitInfo(remaining = 50, limit = 100, resetEpochSeconds = 1000L)
        assertFalse(info.isNearLimit)
    }

    @Test
    fun `RateLimitInfo isNearLimit boundary at exactly 10 percent`() {
        val info = RateLimitInfo(remaining = 10, limit = 100, resetEpochSeconds = 1000L)
        assertFalse(info.isNearLimit)
    }

    @Test
    fun `RateLimitInfo serializes round-trip`() {
        val info = RateLimitInfo(remaining = 42, limit = 5000, resetEpochSeconds = 1700000000L)
        val json = KarandashJson.Default.encodeToString(info)
        val decoded = KarandashJson.Default.decodeFromString<RateLimitInfo>(json)
        assertEquals(info, decoded)
    }

    // ── Default implementation tests ──

    @Test
    fun `default reviewer management reports unsupported`() =
        runTest {
            val add = testModule.addReviewers("proj", "1", listOf("user1")).exceptionOrNull()
            val remove = testModule.removeReviewers("proj", "1", listOf("user1")).exceptionOrNull()

            assertEquals("addReviewers", assertIs<UnsupportedPlatformOperationException>(add).operation)
            assertEquals("removeReviewers", assertIs<UnsupportedPlatformOperationException>(remove).operation)
        }

    @Test
    fun `default merge state operations report the typed unsupported failure`() =
        runTest {
            listOf(testModule.mergeMr("proj", "1"), testModule.closeMr("proj", "1"), testModule.reopenMr("proj", "1"))
                .forEach { assertIs<UnsupportedPlatformOperationException>(it.exceptionOrNull()) }
        }

    @Test
    fun `default mergeMr returns failure`() =
        runTest {
            val result = testModule.mergeMr("proj", "1")
            assertTrue(result.isFailure)
            assertIs<UnsupportedOperationException>(result.exceptionOrNull())
        }

    @Test
    fun `default closeMr returns failure`() =
        runTest {
            val result = testModule.closeMr("proj", "1")
            assertTrue(result.isFailure)
            assertIs<UnsupportedOperationException>(result.exceptionOrNull())
        }

    @Test
    fun `default reopenMr returns failure`() =
        runTest {
            val result = testModule.reopenMr("proj", "1")
            assertTrue(result.isFailure)
            assertIs<UnsupportedOperationException>(result.exceptionOrNull())
        }

    @Test
    fun `default rateLimitStatus returns null`() =
        runTest {
            assertNull(testModule.rateLimitStatus())
        }
}
