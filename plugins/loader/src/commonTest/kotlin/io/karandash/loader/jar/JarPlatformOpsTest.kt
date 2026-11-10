package io.karandash.loader.jar

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.CIEnvironment
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.support.KarandashJson
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The platform JAR bridge ops decode the host's arguments and encode "no answer" for each optional result. */
class JarPlatformOpsTest {
    /** Answers every optional platform call when [answers], otherwise keeps the interface defaults' "no answer". */
    private class FakePlatform(
        private val answers: Boolean,
    ) : PlatformServiceModule {
        override val id = "fake-platform"
        override val name = id
        override val version = "1.0"
        val calls = mutableListOf<String>()

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = createTestMergeRequest(title = "$projectId!$mrId")

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = createTestMergeRequest(title = url)

        override suspend fun detectCiEnvironment(env: (String) -> String?): CIEnvironment? =
            env("CI_PROJECT")?.let { CIEnvironment("fake", it, env("CI_MR"), null, null) }

        override suspend fun parseMergeRequestUrl(url: String) = if (answers) ParsedMergeRequestUrl("fake", "https://h", "p", "1") else null

        override suspend fun defaultApiUrl() = if (answers) "https://api" else null

        override suspend fun authTokenHint() = if (answers) "FAKE_TOKEN" else null

        override suspend fun listOpenMergeRequestIds(
            projectId: String,
            targetBranch: String,
            limit: Int,
        ) = if (answers) List(limit) { "$it" } else null

        override suspend fun listDismissedInlineComments(
            projectId: String,
            mrId: String,
            marker: String,
        ) = if (answers) listOf(marker) else null

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {
            calls += "add $labels"
        }

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {
            calls += "remove $labels"
        }

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) {
            calls += "draft $draft"
        }

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) {
            calls += "title $title"
        }

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) {
            calls += "description $description"
        }

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) {
            calls += "approve"
        }

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) {
            calls += "unapprove"
        }

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ) = "c-$body"

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) {
            calls += "update $commentId $body"
        }

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
        ) = "$filePath:$line:$startLine:${diffRefs.headSha}"
    }

    private suspend fun FakePlatform.invoke(
        op: String,
        vararg args: String,
        context: PluginContext = TestPluginContext(),
    ): String? = JarBridgeDispatcher.invoke(this, op, JarInvocation(args.toList()) { context })

    @Test
    fun `optional platform answers encode as no answer or empty string`() =
        runTest {
            val silent = FakePlatform(answers = false)
            assertNull(silent.invoke("parseMergeRequestUrl", "u"))
            assertEquals("", silent.invoke("defaultApiUrl"))
            assertEquals("", silent.invoke("authTokenHint"))
            assertNull(silent.invoke("listOpenMergeRequestIds", "p", "main", "2"))
            assertNull(silent.invoke("listDismissedInlineComments", "p", "1", "m"))
            assertNull(silent.invoke("findExistingComment", "p", "1", "m"))
            assertEquals("[]", silent.invoke("findAllMatchingComments", "p", "1", "m"))
            assertEquals("0", silent.invoke("deleteMarkedInlineComments", "p", "1", "m"))
            assertEquals("0", silent.invoke("resolveMarkedInlineComments", "p", "1", "m"))

            val answering = FakePlatform(answers = true)
            val parsed = KarandashJson.Default.decodeFromString(
                ParsedMergeRequestUrl.serializer(),
                checkNotNull(answering.invoke("parseMergeRequestUrl", "u")),
            )
            assertEquals("p", parsed.projectId)
            assertEquals("https://api", answering.invoke("defaultApiUrl"))
            assertEquals("FAKE_TOKEN", answering.invoke("authTokenHint"))
            assertEquals("""["0","1"]""", answering.invoke("listOpenMergeRequestIds", "p", "main", "2"))
            assertEquals("""["m"]""", answering.invoke("listDismissedInlineComments", "p", "1", "m"))
        }

    @Test
    fun `ci detection reads sent values first and the plugin context env for the rest`() =
        runTest {
            val platform = FakePlatform(answers = true)
            val context = TestPluginContext(envValues = mapOf("CI_PROJECT" to "from-env", "CI_MR" to "7"))
            val sent = platform.invoke("detectCiEnvironment", """{"CI_PROJECT":"sent"}""", context = context)
            val detected = KarandashJson.Default.decodeFromString(CIEnvironment.serializer(), checkNotNull(sent))
            assertEquals(CIEnvironment("fake", "sent", "7", null, null), detected)
            val fromEnv = platform.invoke("detectCiEnvironment", "", context = context)
            assertEquals("from-env", KarandashJson.Default.decodeFromString(CIEnvironment.serializer(), checkNotNull(fromEnv)).projectId)
            assertNull(platform.invoke("detectCiEnvironment", "[]"))
        }

    @Test
    fun `mutators decode labels and flags and answer true`() =
        runTest {
            val platform = FakePlatform(answers = false)
            assertEquals("true", platform.invoke("addLabels", "p", "1", "a,,b"))
            assertEquals("true", platform.invoke("addLabels", "p", "1", """["x,y","z"]"""))
            assertEquals("true", platform.invoke("removeLabels", "p", "1", ""))
            assertEquals("true", platform.invoke("setDraft", "p", "1", "true"))
            assertEquals("true", platform.invoke("setDraft", "p", "1", "0"))
            assertEquals("true", platform.invoke("updateTitle", "p", "1", "T"))
            assertEquals("true", platform.invoke("updateDescription", "p", "1", "D"))
            assertEquals("true", platform.invoke("approve", "p", "1"))
            assertEquals("true", platform.invoke("unapprove", "p", "1"))
            assertEquals("true", platform.invoke("updateComment", "p", "1", "c9", "B"))
            assertEquals("true", platform.invoke("deleteComment", "p", "1", "c9"))
            assertEquals(
                listOf(
                    "add [a, b]",
                    "add [x,y, z]",
                    "remove []",
                    "draft true",
                    "draft false",
                    "title T",
                    "description D",
                    "approve",
                    "unapprove",
                    "update c9 B"
                ),
                platform.calls,
            )
        }

    @Test
    fun `comments fetches and inline start line zero mean a single line`() =
        runTest {
            val platform = FakePlatform(answers = false)
            val refs = """{"base_sha":"b","head_sha":"h"}"""
            assertEquals("a.kt:3:null:h", platform.invoke("postInlineComment", "p", "1", "body", "a.kt", "3", refs, "0"))
            assertEquals("a.kt:3:2:h", platform.invoke("postInlineComment", "p", "1", "body", "a.kt", "3", refs, "2"))
            assertEquals("c-hi", platform.invoke("postComment", "p", "1", "hi"))
            assertEquals("```diff\n+ x\n```", platform.invoke("renderSuggestionBlock", "x"))
            assertEquals("[]", platform.invoke("aliases"))
            val mr = KarandashJson.Default.decodeFromString(
                MergeRequest.serializer(),
                checkNotNull(platform.invoke("fetchMergeRequest", "p", "1"))
            )
            assertEquals("p!1", mr.title)
            val byUrl = platform.invoke("fetchMergeRequestByUrl", "https://h/mr/1")
            assertEquals("https://h/mr/1", KarandashJson.Default.decodeFromString(MergeRequest.serializer(), checkNotNull(byUrl)).title)
        }

    @Test
    fun `data ops answer the SDK defaults and live ops stay out of the snapshot`() =
        runTest {
            val platform = FakePlatform(answers = false)
            val unsupported = """{"err":"mergeMr is not supported by this platform","unsupported":true}"""
            assertEquals(unsupported, platform.invoke("mergeMr", "p", "1", "{}"))
            assertEquals("false", platform.invoke("supports", "MERGE"))
            assertEquals("false", platform.invoke("supports", "NOT_AN_OPERATION"))
            assertEquals("false", platform.invoke("supportsFileContent"))
            assertEquals("0", platform.invoke("resolveMarkedInlineCommentsForFiles", "p", "1", "m", """["a"]"""))
            assertEquals("[]", platform.invoke("fetchFileBlame", "p", "a", "r"))
            assertNull(platform.invoke("currentUsername"))
            val search = platform.invoke("searchMergeRequests", "p", """{"text":"x"}""", "5")
            assertEquals("""{"err":"searchMergeRequests is not supported by this platform","unsupported":true}""", search)
            val snapshot = JarBridgeDispatcher.snapshot(platform) { TestPluginContext() }
            assertEquals("false", snapshot["supportsFileContent"])
            assertNull(snapshot["checkAuth"])
            assertNull(snapshot["currentUsername"])
        }

    @Test
    fun `queries whose SDK default throws answer the unsupported marker`() =
        runTest {
            val platform = FakePlatform(answers = false)
            val answers =
                mapOf(
                    "fetchFileContent" to listOf("p", "r", "a"),
                    "fetchApprovalSignals" to listOf("p", "1"),
                    "searchMergeRequests" to listOf("p", """{"author":"u"}""", "5"),
                    "listReviewThreads" to listOf("p", "1"),
                ).mapValues { (op, args) -> platform.invoke(op, *args.toTypedArray()) }
            answers.forEach { (op, answer) ->
                assertEquals("""{"err":"$op is not supported by this platform","unsupported":true}""", answer)
            }
        }
}
