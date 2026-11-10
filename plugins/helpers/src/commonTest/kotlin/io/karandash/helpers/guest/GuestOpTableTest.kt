package io.karandash.helpers.guest

import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.module.ReporterModule
import io.karandash.api.module.RuleModule
import io.karandash.api.platform.ParsedMergeRequestUrl
import io.karandash.models.DiffRefs
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GuestOpTableTest {
    private val mr = MergeRequest(
        id = "1",
        iid = 1,
        title = "t",
        description = null,
        sourceBranch = "s",
        targetBranch = "main",
        author = User("a", "A", "a@x.io"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = emptyList(),
        changes = emptyList(),
    )

    private val finding = Finding(severity = Severity.WARNING, message = "m", rule = "r")

    private class Fake(
        private val mr: MergeRequest,
    ) : PlatformServiceModule {
        val calls = mutableListOf<String>()
        override val id = "fake"
        override val name = "Fake"
        override val version = "1"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun fetchMergeRequest(
            projectId: String,
            mrId: String,
        ): MergeRequest = mr.also { calls += "fetch:$projectId:$mrId" }

        override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest = mr.also { calls += "fetchUrl:$url" }

        override suspend fun defaultApiUrl(): String? = "https://api.x"

        override suspend fun aliases(): Set<String> = setOf("a", "b")

        override suspend fun parseMergeRequestUrl(url: String): ParsedMergeRequestUrl? =
            if (url == "ok") ParsedMergeRequestUrl("fake", "https://x", "g/p", "7") else null

        override suspend fun parseGitRemoteUrl(url: String): String? = "remote:$url"

        override suspend fun addLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {
            calls += "addLabels:$projectId:$mrId:$labels"
        }

        override suspend fun removeLabels(
            projectId: String,
            mrId: String,
            labels: List<String>,
        ) {
            calls += "removeLabels:$labels"
        }

        override suspend fun setDraft(
            projectId: String,
            mrId: String,
            draft: Boolean,
        ) {
            calls += "setDraft:$draft"
        }

        override suspend fun updateTitle(
            projectId: String,
            mrId: String,
            title: String,
        ) {
            calls += "title:$title"
        }

        override suspend fun updateDescription(
            projectId: String,
            mrId: String,
            description: String,
        ) {
            calls += "desc:$description"
        }

        override suspend fun approve(
            projectId: String,
            mrId: String,
        ) {
            calls += "approve:$projectId:$mrId"
        }

        override suspend fun unapprove(
            projectId: String,
            mrId: String,
        ) {
            calls += "unapprove:$projectId:$mrId"
        }

        override suspend fun postComment(
            projectId: String,
            mrId: String,
            body: String,
        ): String = "posted:$body"

        override suspend fun updateComment(
            projectId: String,
            mrId: String,
            commentId: String,
            body: String,
        ) {
            calls += "updateComment:$commentId:$body"
        }

        override suspend fun findExistingComment(
            projectId: String,
            mrId: String,
            marker: String,
        ): String? = "found:$marker"

        override suspend fun postInlineComment(
            projectId: String,
            mrId: String,
            body: String,
            filePath: String,
            line: Int,
            diffRefs: DiffRefs,
            startLine: Int?,
        ): String = "inline:$filePath:$line:${diffRefs.headSha}:$startLine"

        override suspend fun deleteMarkedInlineComments(
            projectId: String,
            mrId: String,
            marker: String,
        ): Int = 3

        override suspend fun resolveMarkedInlineComments(
            projectId: String,
            mrId: String,
            marker: String,
        ): Int = 4

        override suspend fun deleteComment(
            projectId: String,
            mrId: String,
            commentId: String,
        ) {
            calls += "deleteComment:$commentId"
        }
    }

    private class FakeRule(
        val seen: MutableList<String>,
    ) : RuleModule {
        override val id = "rule"
        override val name = "Rule"
        override val version = "1"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun contributedRules(): Set<String> = setOf("r")

        override suspend fun validate(
            ruleId: String,
            mr: MergeRequest,
            configElement: JsonElement,
        ): List<Finding> {
            seen += "$ruleId:${mr.title}:$configElement"
            return listOf(Finding(severity = Severity.ERROR, message = "bad", rule = ruleId))
        }
    }

    private class FakeReporter(
        val seen: MutableList<String>,
    ) : ReporterModule {
        override val id = "reporter"
        override val name = "Reporter"
        override val version = "1"
        override val formatId = "fake"

        override suspend fun initialize(context: PluginContext) = true

        override suspend fun report(
            findings: List<Finding>,
            passed: Boolean,
            config: JsonElement,
        ): String? {
            seen += "${findings.size}:$passed:$config"
            return "out"
        }
    }

    @Test
    fun `codec reads blank element as null and optional null as absent`() {
        assertEquals(JsonNull, GuestCodec.element(" "))
        assertNull(GuestCodec.optionalElement("null"))
        assertEquals(JsonObject(mapOf("k" to JsonPrimitive(1))), GuestCodec.optionalElement("""{"k":1}"""))
    }

    @Test
    fun `codec int args read true as 1 and junk as 0`() {
        assertEquals(5, GuestCodec.intArg("5"))
        assertEquals(1, GuestCodec.intArg("true"))
        assertEquals(0, GuestCodec.intArg("false"))
        assertEquals(0, GuestCodec.intArg(""))
    }

    @Test
    fun `codec labels read a json list and the legacy comma form`() {
        assertEquals(listOf("a", "b"), GuestCodec.labels("""["a","b"]"""))
        assertEquals(listOf("a", "b"), GuestCodec.labels("a,,b"))
        assertEquals(listOf("x", "y"), GuestCodec.strings(GuestCodec.encodeStrings(listOf("x", "y"))))
    }

    @Test
    fun `codec findings and merge request round trip`() {
        assertEquals(listOf(finding), GuestCodec.findings(GuestCodec.encodeFindings(listOf(finding))))
        assertEquals(mr, GuestCodec.mr(KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr)))
    }

    @Test
    fun `missing guest args read as empty strings`() {
        assertEquals("", GuestArgs(listOf("a"))[3])
        assertEquals("a", GuestArgs(listOf("a"))[0])
    }

    @Test
    fun `rule op decodes the merge request and answers the encoded findings`() =
        runTest {
            val seen = mutableListOf<String>()
            val mrJson = KarandashJson.Default.encodeToString(MergeRequest.serializer(), mr)

            val out = GuestOpTable.run(FakeRule(seen), "validate", "r1", mrJson, """{"on":true}""")

            assertEquals(listOf("r1:t:{\"on\":true}"), seen)
            assertEquals("bad", GuestCodec.findings(checkNotNull(out)).single().message)
        }

    @Test
    fun `report op passes findings and passed flag`() =
        runTest {
            val seen = mutableListOf<String>()

            val out = GuestOpTable.run(FakeReporter(seen), "report", GuestCodec.encodeFindings(listOf(finding)), "1", "{}")

            assertEquals("out", out)
            assertEquals(listOf("1:true:{}"), seen)
        }

    @Test
    fun `fetch ops answer the platform values`() =
        runTest {
            val p = Fake(mr)

            val fetched = GuestOpTable.run(p, "fetchMergeRequest", "g/p", "1")
            val byUrl = GuestOpTable.run(p, "fetchMergeRequestByUrl", "https://x/1")

            assertEquals(mr, KarandashJson.Default.decodeFromString(MergeRequest.serializer(), checkNotNull(fetched)))
            assertEquals(fetched, byUrl)
            assertEquals(listOf("fetch:g/p:1", "fetchUrl:https://x/1"), p.calls)
            assertEquals("https://api.x", GuestOpTable.run(p, "defaultApiUrl"))
            assertEquals("", GuestOpTable.run(p, "authTokenHint"))
            assertEquals("remote:u", GuestOpTable.run(p, "parseGitRemoteUrl", "u"))
            assertEquals(setOf("a", "b"), GuestCodec.strings(checkNotNull(GuestOpTable.run(p, "aliases"))).toSet())
        }

    @Test
    fun `parseMergeRequestUrl answers null for an unknown url and json for a known one`() =
        runTest {
            val p = Fake(mr)

            assertNull(GuestOpTable.run(p, "parseMergeRequestUrl", "nope"))
            val parsed = KarandashJson.Default.decodeFromString(
                ParsedMergeRequestUrl.serializer(),
                checkNotNull(GuestOpTable.run(p, "parseMergeRequestUrl", "ok")),
            )
            assertEquals("g/p", parsed.projectId)
        }

    @Test
    fun `mutator ops decode their arguments and answer true`() =
        runTest {
            val p = Fake(mr)

            val answers = listOf(
                GuestOpTable.run(p, "addLabels", "g", "1", """["x"]"""),
                GuestOpTable.run(p, "removeLabels", "g", "1", "y,z"),
                GuestOpTable.run(p, "setDraft", "g", "1", "1"),
                GuestOpTable.run(p, "updateTitle", "g", "1", "T"),
                GuestOpTable.run(p, "updateDescription", "g", "1", "D"),
                GuestOpTable.run(p, "approve", "g", "1"),
                GuestOpTable.run(p, "unapprove", "g", "1"),
                GuestOpTable.run(p, "updateComment", "g", "1", "c9", "B"),
                GuestOpTable.run(p, "deleteComment", "g", "1", "c9"),
            )

            assertEquals(List(9) { "true" }, answers)
            assertEquals(
                listOf(
                    "addLabels:g:1:[x]",
                    "removeLabels:[y, z]",
                    "setDraft:true",
                    "title:T",
                    "desc:D",
                    "approve:g:1",
                    "unapprove:g:1",
                    "updateComment:c9:B",
                    "deleteComment:c9",
                ),
                p.calls,
            )
        }

    @Test
    fun `comment ops answer ids counts and lists`() =
        runTest {
            val p = Fake(mr)
            val refs = KarandashJson.Default.encodeToString(DiffRefs.serializer(), DiffRefs("b", "h"))

            assertEquals("posted:B", GuestOpTable.run(p, "postComment", "g", "1", "B"))
            assertEquals("found:M", GuestOpTable.run(p, "findExistingComment", "g", "1", "M"))
            assertEquals("inline:f.kt:5:h:null", GuestOpTable.run(p, "postInlineComment", "g", "1", "B", "f.kt", "5", refs, "0"))
            assertEquals("inline:f.kt:5:h:3", GuestOpTable.run(p, "postInlineComment", "g", "1", "B", "f.kt", "5", refs, "3"))
            assertEquals("3", GuestOpTable.run(p, "deleteMarkedInlineComments", "g", "1", "M"))
            assertEquals("4", GuestOpTable.run(p, "resolveMarkedInlineComments", "g", "1", "M"))
            assertEquals(listOf("found:M"), GuestCodec.strings(checkNotNull(GuestOpTable.run(p, "findAllMatchingComments", "g", "1", "M"))))
            assertTrue(checkNotNull(GuestOpTable.run(p, "renderSuggestionBlock", "code")).contains("+ code"))
        }

    @Test
    fun `unsupported list ops answer null`() =
        runTest {
            val p = Fake(mr)

            assertNull(GuestOpTable.run(p, "listOpenMergeRequestIds", "g", "main", "5"))
            assertNull(GuestOpTable.run(p, "listDismissedInlineComments", "g", "1", "M"))
        }

    @Test
    fun `an unknown op or a module of the wrong kind is refused`() =
        runTest {
            assertFailsWith<IllegalArgumentException> { GuestOpTable.run(Fake(mr), "nope") }
            assertFailsWith<IllegalArgumentException> { GuestOpTable.run(Fake(mr), "validate", "r", "{}", "{}") }
        }
}
