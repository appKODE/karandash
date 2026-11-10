package io.karandash.platforms.awscodecommit

import io.karandash.models.DiffRefs
import io.karandash.testfixtures.AMZ_TARGET
import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.amzTarget
import io.ktor.client.request.HttpRequestData
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** One review run over the engine seam: fetch the PR, post an inline comment, then post and edit the summary. */
class AwsCodeCommitCommentScenarioTest {
    @Test
    fun `fetch then inline comment then summary call the expected operations with sigv4`() =
        runTest {
            val server = RecordingMockServer(routes())
            val service = AwsCodeCommitPlatformService(server.engine)
            val config = mapOf("region" to "us-east-1", "accessKeyId" to "AKIDEXAMPLE", "secretAccessKey" to "secret")
            assertTrue(service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) }))))

            val mr = service.fetchMergeRequest("demo", "7")
            val inlineId = service.postInlineComment("demo", "7", "inline", "a.kt", 2, DiffRefs(BASE, HEAD, BASE))
            val inlineEdit = service.updateNote("demo", "7", inlineId, inlineId, "inline v2")
            val summaryId = service.findExistingComment("demo", "7", MARKER) ?: service.postComment("demo", "7", "$MARKER v1")
            service.updateComment("demo", "7", summaryId, "$MARKER v2")

            assertEquals("Add key", mr.title)
            assertEquals(listOf("a.kt"), mr.changes.map { it.path })
            assertEquals("c11", inlineId)
            assertTrue(inlineEdit.isSuccess)
            assertTrue(server.requests().all { it.method.value == "POST" && it.url.encodedPath == "/" })
            val calls = server.requests().map { it.headers[AMZ_TARGET].orEmpty().removePrefix("$OP.") }
            assertEquals(
                listOf("GetBlob", "GetBlob", "GetCommit", "GetDifferences", "GetPullRequest", "GetPullRequestApprovalStates"),
                calls.take(FETCH_CALLS).sorted(),
            )
            assertEquals(
                listOf(
                    "GetPullRequest",
                    "PostCommentForPullRequest",
                    "UpdateComment",
                    "GetCommentsForPullRequest",
                    "PostCommentForPullRequest",
                    "UpdateComment",
                ),
                calls.drop(FETCH_CALLS),
            )
            assertTrue(
                server.requests().all {
                    it.headers["Authorization"].orEmpty().startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/")
                },
            )
        }

    @Test
    fun `the summary state reads back from the newest marked comment`() =
        runTest {
            val server = RecordingMockServer(listOf(amzTarget("$OP.GetCommentsForPullRequest", MARKED_COMMENTS)) + routes())
            val service = AwsCodeCommitPlatformService(server.engine)
            assertTrue(service.initialize(TestPluginContext(JsonObject(AWS_CONFIG.mapValues { JsonPrimitive(it.value) }))))

            assertEquals("$MARKER new", service.findCommentBody("demo", "7", MARKER))
            assertNull(service.findCommentBody("demo", "7", "<!-- absent -->"))
        }

    private fun routes() =
        listOf(
            amzTarget(
                "$OP.GetPullRequest",
                """{"pullRequest":{"pullRequestId":"7","title":"Add key","description":"","pullRequestStatus":"OPEN",""" +
                    """"authorArn":"arn:aws:iam::123456789012:user/dev","creationDate":"2026-10-01T10:00:00Z",""" +
                    """"revisionId":"rev-1","pullRequestTargets":[{"repositoryName":"demo","sourceReference":"refs/heads/f",""" +
                    """"destinationReference":"refs/heads/main","sourceCommit":"$HEAD","destinationCommit":"$BASE"}]}}""",
            ),
            amzTarget(
                "$OP.GetDifferences",
                """{"differences":[{"changeType":"M","beforeBlob":{"blobId":"blob-base","path":"a.kt","mode":"100644"},""" +
                    """"afterBlob":{"blobId":"blob-head","path":"a.kt","mode":"100644"}}]}""",
            ),
            amzTarget(
                "$OP.GetCommit",
                """{"commit":{"commitId":"$HEAD","treeId":"t1","parents":["$BASE"],"message":"add key",""" +
                    """"author":{"name":"Dev","email":"dev@example.com","date":"1700000000 +0000"},""" +
                    """"committer":{"name":"Dev","email":"dev@example.com","date":"1700000000 +0000"}}}""",
            ),
            blob("blob-head", "a\nb\n"),
            blob("blob-base", "a\n"),
            MockRoute({ it.headers[AMZ_TARGET] == "$OP.PostCommentForPullRequest" && "location" in text(it) }, comment("c11")),
            amzTarget("$OP.PostCommentForPullRequest", comment("c21")),
            amzTarget("$OP.GetCommentsForPullRequest", """{"commentsForPullRequestData":[]}"""),
            amzTarget("$OP.UpdateComment", comment("c21")),
            // Optional fetch data (commits, approvals) answers an empty object.
            MockRoute({ true }, "{}", JSON11),
        )

    private fun blob(
        blobId: String,
        content: String,
    ) = MockRoute(
        { it.headers[AMZ_TARGET] == "$OP.GetBlob" && blobId in text(it) },
        """{"content":"${Base64.encode(content.encodeToByteArray())}"}""",
        JSON11,
    )

    private fun comment(id: String) = """{"comment":{"commentId":"$id","content":"x"}}"""

    private fun text(request: HttpRequestData) = (request.body as? TextContent)?.text.orEmpty()

    private companion object {
        const val MARKER = "<!-- s -->"
        const val MARKED_COMMENTS =
            """{"commentsForPullRequestData":[{"comments":[{"commentId":"c1","content":"$MARKER old"},""" +
                """{"commentId":"c2","content":"plain"},{"commentId":"c3","content":"$MARKER new"}]}]}"""
        val AWS_CONFIG = mapOf("region" to "us-east-1", "accessKeyId" to "AKIDEXAMPLE", "secretAccessKey" to "secret")
        const val OP = "CodeCommit_20150413"
        const val JSON11 = "application/x-amz-json-1.1"
        const val HEAD = "h1"
        const val BASE = "b1"
        const val FETCH_CALLS = 6
    }
}
