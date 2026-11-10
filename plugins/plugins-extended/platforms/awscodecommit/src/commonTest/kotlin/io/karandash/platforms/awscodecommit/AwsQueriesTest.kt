@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.awscodecommit

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.testfixtures.TestPluginContext
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pull request search, the caller username and approval signals from pull request comments. */
class AwsQueriesTest {
    private val listBodies = mutableListOf<String>()

    private fun pullRequest(
        id: String,
        title: String,
        status: String,
        author: String,
        merged: Boolean = false,
    ) = """{"pullRequest":{"pullRequestId":"$id","title":"$title","pullRequestStatus":"$status",""" +
        """"authorArn":"arn:aws:iam::1:user/$author","creationDate":"1","pullRequestTargets":[{"repositoryName":"repo",""" +
        """"sourceReference":"refs/heads/f","destinationReference":"refs/heads/main","mergeMetadata":{"isMerged":$merged}}]}}"""

    private suspend fun service(): AwsCodeCommitPlatformService {
        val engine = MockEngine { request ->
            val target = request.headers["X-Amz-Target"].orEmpty()
            val body = (request.body as? TextContent)?.text.orEmpty()
            val answer = when {
                target.isEmpty() -> "<GetCallerIdentityResult><Arn>arn:aws:iam::1:user/bot</Arn></GetCallerIdentityResult>"
                target.endsWith("ListPullRequests") -> """{"pullRequestIds":["1","3","2"]}""".also { listBodies += body }
                "\"3\"" in body -> pullRequest("3", "Fix login", "CLOSED", "ann", merged = true)
                "\"2\"" in body -> pullRequest("2", "fix LOGIN again", "OPEN", "bob")
                else -> pullRequest("1", "Fix login first", "CLOSED", "ann")
            }
            respond(answer, HttpStatusCode.OK)
        }
        val service = AwsCodeCommitPlatformService(engine)
        val config = mapOf("region" to "us-east-1", "accessKeyId" to "AKIDEXAMPLE", "secretAccessKey" to "secret")
        assertTrue(service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) }))))
        return service
    }

    @Test
    fun `search reads every status newest first and matches text and author`() =
        runTest {
            val service = service()

            val hits = service.searchMergeRequests("repo", "fix login", "ann", 5)

            assertEquals(listOf("3", "1"), hits.map { it.id })
            assertEquals(listOf(MergeRequestSearchHit.MERGED, MergeRequestSearchHit.CLOSED), hits.map { it.state })
            assertEquals("ann", hits.first().author)
            assertTrue("pullRequestStatus" !in listBodies.single())
            assertEquals(listOf("3"), service.searchMergeRequests("repo", "login", null, 1).map { it.id })
        }

    @Test
    fun `current username is the user part of the caller ARN`() =
        runTest {
            assertEquals("bot", service().currentUsername())
        }

    @Test
    fun `comments with an author and content become comment signals`() {
        val comments = listOf(
            AwsComment(commentId = "c1", content = "LGTM", authorArn = "arn:aws:iam::1:user/ann", creationDate = "t1"),
            AwsComment(commentId = "c2", content = " ", authorArn = "arn:aws:iam::1:user/ann"),
            AwsComment(commentId = "c3", content = "anon"),
        )

        val signals = approvalSignals(comments)

        assertEquals(1, signals.size)
        assertEquals(ApprovalMethod.COMMENT_KEYWORD, signals.single().method)
        assertEquals("ann", signals.single().user.username)
        assertEquals("t1", signals.single().signaledAt)
        assertNull(signals.single().user.email)
    }
}
