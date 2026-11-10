@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.plugins.outputs

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.finding.FormattedReport
import io.karandash.api.finding.InlineCommentData
import io.karandash.api.module.ModuleScope
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.InlineCommentBatchPoster
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.support.mapToJsonElement
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

class PlatformOutputModuleCacheTest {
    private fun createContext(): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(emptyMap())
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }

    private fun makeReport(
        inlineComments: List<InlineCommentData> = emptyList(),
        changedFiles: Set<String>? = null,
    ) = FormattedReport(
        formatId = "markdown",
        body = "<!-- karandash -->\n# Test",
        inlineComments = inlineComments,
        commentMarker = "<!-- karandash -->",
        inlineMarker = "<!-- karandash-inline -->",
        changedFiles = changedFiles,
    )

    @Test
    fun noCacheContextResolvesAllAndPostsAll() =
        runTest {
            val platform = RecordingPlatformService()
            val module = PlatformOutputModule(FixedClock())
            module.initialize(createContext())
            module.configurePlatformService(
                platform,
                "proj",
                "1",
                DiffRefs(baseSha = "aaa", headSha = "bbb", startSha = "aaa"),
            )

            val report = makeReport(
                inlineComments = listOf(
                    InlineCommentData("Foo.kt", 10, body = "<!-- karandash-inline -->\nissue"),
                    InlineCommentData("Bar.kt", 20, body = "<!-- karandash-inline -->\nissue"),
                ),
                changedFiles = null, // no cache
            )

            module.output(report, mapToJsonElement(emptyMap<String, Any?>()))

            // Should resolve ALL (not file-scoped)
            assertEquals(1, platform.resolveAllCalls.size)
            assertEquals(0, platform.resolveForFilesCalls.size)
            // Should post ALL inline comments
            assertEquals(2, platform.inlineCommentsPosted.size)
        }

    @Test
    fun `a batching platform gets all inline comments in one call and the single path stays unused`() =
        runTest {
            val inner = RecordingPlatformService()
            val platform = BatchingPlatformService(inner)
            val module = PlatformOutputModule(FixedClock())
            module.initialize(createContext())
            module.configurePlatformService(platform, "proj", "1", DiffRefs(baseSha = "aaa", headSha = "bbb", startSha = "aaa"))
            val report = makeReport(
                inlineComments = listOf(
                    InlineCommentData("Foo.kt", 10, body = "issue"),
                    InlineCommentData("Bar.kt", 20, body = "issue"),
                ),
            )

            module.output(report, mapToJsonElement(emptyMap<String, Any?>()))

            assertEquals(listOf(2), platform.batches)
            assertEquals(0, inner.inlineCommentsPosted.size)
        }

    @Test
    fun `a failed batch falls back to one comment at a time`() =
        runTest {
            val inner = RecordingPlatformService()
            val platform = BatchingPlatformService(inner, failBatch = true)
            val module = PlatformOutputModule(FixedClock())
            module.initialize(createContext())
            module.configurePlatformService(platform, "proj", "1", DiffRefs(baseSha = "aaa", headSha = "bbb", startSha = "aaa"))
            val report = makeReport(
                inlineComments = listOf(InlineCommentData("Foo.kt", 10, body = "issue"), InlineCommentData("Bar.kt", 20, body = "issue")),
            )

            module.output(report, mapToJsonElement(emptyMap<String, Any?>()))

            assertEquals(2, inner.inlineCommentsPosted.size)
        }

    @Test
    fun partialCacheHitOnlyPostsInlineForChangedFiles() =
        runTest {
            val platform = RecordingPlatformService()
            val module = PlatformOutputModule(FixedClock())
            module.initialize(createContext())
            module.configurePlatformService(
                platform,
                "proj",
                "1",
                DiffRefs(baseSha = "aaa", headSha = "bbb", startSha = "aaa"),
            )

            val report = makeReport(
                inlineComments = listOf(
                    InlineCommentData("Foo.kt", 10, body = "<!-- karandash-inline -->\nissue"),
                    InlineCommentData("Bar.kt", 20, body = "<!-- karandash-inline -->\ncached issue"),
                ),
                changedFiles = setOf("Foo.kt"), // partial cache - only Foo.kt changed
            )

            module.output(report, mapToJsonElement(emptyMap<String, Any?>()))

            // Should resolve only for changed files
            assertEquals(0, platform.resolveAllCalls.size)
            assertEquals(1, platform.resolveForFilesCalls.size)
            assertEquals(setOf("Foo.kt"), platform.resolveForFilesCalls.first().filePaths)
            // Should only post Foo.kt inline comment (not Bar.kt)
            assertEquals(1, platform.inlineCommentsPosted.size)
            assertEquals("Foo.kt", platform.inlineCommentsPosted.first().filePath)
        }

    @Test
    fun fullCacheHitSkipsAllInlineActivity() =
        runTest {
            val platform = RecordingPlatformService()
            val module = PlatformOutputModule(FixedClock())
            module.initialize(createContext())
            module.configurePlatformService(
                platform,
                "proj",
                "1",
                DiffRefs(baseSha = "aaa", headSha = "bbb", startSha = "aaa"),
            )

            val report = makeReport(
                inlineComments = listOf(
                    InlineCommentData("Foo.kt", 10, body = "<!-- karandash-inline -->\nissue"),
                ),
                changedFiles = emptySet(), // full cache hit
            )

            module.output(report, mapToJsonElement(emptyMap<String, Any?>()))

            // Should NOT resolve anything
            assertEquals(0, platform.resolveAllCalls.size)
            assertEquals(0, platform.resolveForFilesCalls.size)
            // Should NOT post any inline comments
            assertEquals(0, platform.inlineCommentsPosted.size)
            // Should still update summary comment
            assertTrue(platform.summaryUpdated || platform.summaryCreated)
        }

    @Test
    fun eachRunHandleKeepsItsOwnPostingTargetAndShutdownDropsIt() =
        runTest {
            val shared = PlatformOutputModule(FixedClock())
            assertEquals(ModuleScope.PER_RUN, shared.scope)
            val first = shared.newHandle(createContext()) as PlatformOutputModule
            val second = shared.newHandle(createContext()) as PlatformOutputModule
            assertNotSame(first, second)
            val firstPlatform = RecordingPlatformService()
            val secondPlatform = RecordingPlatformService()
            listOf(first to firstPlatform, second to secondPlatform).forEach { (module, platform) ->
                module.initialize(createContext())
                module.configurePlatformService(platform, "proj", "1", diffRefs = null)
            }

            first.output(makeReport(), mapToJsonElement(emptyMap<String, Any?>()))
            assertTrue(firstPlatform.summaryUpdated)
            assertFalse(secondPlatform.summaryUpdated, "a run must not post through another run's target")

            second.shutdown()
            second.output(makeReport(), mapToJsonElement(emptyMap<String, Any?>()))
            assertFalse(secondPlatform.summaryUpdated, "shutdown drops the posting target")
        }

    @Test
    fun platformThatResolvesNothingNeverDeletesAndPostsOnlyChangedFiles() =
        runTest {
            val platform = RecordingPlatformService(deleteResult = 3)
            val module = configuredModule(platform)

            val report = makeReport(
                inlineComments = listOf(
                    InlineCommentData("Foo.kt", 10, body = "<!-- karandash-inline -->\nissue"),
                    InlineCommentData("Bar.kt", 20, body = "<!-- karandash-inline -->\ncached issue"),
                ),
                changedFiles = setOf("Foo.kt"),
            )

            module.output(report, mapToJsonElement(emptyMap<String, Any?>()))

            // the platform supersedes old comments itself; the cached Bar.kt comment is still on the MR
            assertTrue(platform.deleteCalls.isEmpty())
            assertEquals(listOf("Foo.kt"), platform.inlineCommentsPosted.map { it.filePath })
        }

    @Test
    fun platformThatResolvesNeverDeletes() =
        runTest {
            val platform = RecordingPlatformService(supported = setOf(PlatformOperation.RESOLVE_DISCUSSIONS))
            val module = configuredModule(platform)

            module.output(
                makeReport(inlineComments = listOf(InlineCommentData("Foo.kt", 10, body = "issue"))),
                mapToJsonElement(emptyMap<String, Any?>()),
            )

            assertEquals(1, platform.resolveAllCalls.size)
            assertTrue(platform.deleteCalls.isEmpty())
        }

    @Test
    fun suggestionRegionIsRenderedWithThePlatformSyntax() =
        runTest {
            val platform = RecordingPlatformService()
            val module = configuredModule(platform)
            val body = "issue\n\n${InlineCommentData.SUGGESTION_START}\n```diff\n+ val x = 1\n+ \n+ val y = 2\n```\n" +
                "${InlineCommentData.SUGGESTION_END}\n\nmore"

            module.output(
                makeReport(inlineComments = listOf(InlineCommentData("Foo.kt", 10, body = body))),
                mapToJsonElement(emptyMap<String, Any?>()),
            )

            assertEquals(
                "issue\n\n```suggestion\nval x = 1\n\nval y = 2\n```\n\nmore$STAMP",
                platform.inlineCommentsPosted.single().body,
            )
        }

    @Test
    fun `comments knob posts only the summary only the inline comments or nothing`() =
        runTest {
            val cases = mapOf("all" to (true to 1), "summary" to (true to 0), "inline" to (false to 1), "none" to (false to 0))
            cases.forEach { (mode, expected) ->
                val platform = RecordingPlatformService()
                val module = configuredModule(platform)
                module.output(
                    makeReport(inlineComments = listOf(InlineCommentData("Foo.kt", 10, body = "issue"))),
                    mapToJsonElement(mapOf("comments" to mode)),
                )
                assertEquals(expected.first, platform.summaryCreated || platform.summaryUpdated, "$mode: summary")
                assertEquals(expected.second, platform.inlineCommentsPosted.size, "$mode: inline")
            }
        }

    private suspend fun configuredModule(platform: PlatformServiceModule): PlatformOutputModule {
        val module = PlatformOutputModule(FixedClock())
        module.initialize(createContext())
        module.configurePlatformService(platform, "proj", "1", DiffRefs(baseSha = "aaa", headSha = "bbb", startSha = "aaa"))
        return module
    }
}

/**
 * Recording stub for [PlatformServiceModule] that tracks API calls
 * without making any real HTTP requests.
 */
private class BatchingPlatformService(
    private val inner: RecordingPlatformService,
    private val failBatch: Boolean = false,
) : PlatformServiceModule by inner,
    InlineCommentBatchPoster {
    val batches = mutableListOf<Int>()

    override suspend fun postInlineComments(
        projectId: String,
        mrId: String,
        comments: List<InlineCommentData>,
        diffRefs: DiffRefs,
    ): String {
        check(!failBatch) { "batch rejected" }
        batches += comments.size
        return "review"
    }
}

private class RecordingPlatformService(
    private val supported: Set<PlatformOperation> = emptySet(),
    private val deleteResult: Int = 0,
) : PlatformServiceModule {
    override val id: String = "test-platform"
    override val name: String = "Test Platform"
    override val version: String = "1.0.0"
    override val type: ModuleType = ModuleType.PLATFORM_SERVICE

    @Serializable
    data class ResolveForFilesCall(
        val marker: String,
        val filePaths: Set<String>
    )

    @Serializable
    data class InlineCommentCall(
        val filePath: String,
        val line: Int,
        val body: String
    )

    val resolveAllCalls = mutableListOf<String>()
    val resolveForFilesCalls = mutableListOf<ResolveForFilesCall>()
    val inlineCommentsPosted = mutableListOf<InlineCommentCall>()
    val deleteCalls = mutableListOf<String>()
    var summaryUpdated = false
    var summaryCreated = false

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest = error("Not implemented in test")

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = error("Not implemented in test")

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
    ): String {
        summaryCreated = true
        return "comment-1"
    }

    override suspend fun updateComment(
        projectId: String,
        mrId: String,
        commentId: String,
        body: String,
    ) {
        summaryUpdated = true
    }

    override suspend fun findExistingComment(
        projectId: String,
        mrId: String,
        marker: String
    ): String? = "existing-1" // always report existing so update path is taken

    override suspend fun findAllMatchingComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): List<String> = listOf("existing-1")

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String {
        inlineCommentsPosted.add(InlineCommentCall(filePath, line, body))
        return "inline-${inlineCommentsPosted.size}"
    }

    override suspend fun resolveMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int {
        resolveAllCalls.add(marker)
        return 0
    }

    override suspend fun resolveMarkedInlineCommentsForFiles(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>,
    ): Int {
        resolveForFilesCalls.add(ResolveForFilesCall(marker, filePaths))
        return 0
    }

    override suspend fun supports(operation: PlatformOperation): Boolean = operation in supported

    override suspend fun deleteMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int {
        deleteCalls.add(marker)
        return deleteResult
    }

    override suspend fun renderSuggestionBlock(suggestedCode: String): String = "```suggestion\n$suggestedCode\n```"
}
