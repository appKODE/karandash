package io.karandash.platforms.azuredevops

import io.karandash.testfixtures.MockRoute
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.route
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpMethod
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Azure CI project id, line counts, unpaged threads, change entries. */
class AzureDevOpsRegressionTest {
    private suspend fun service(server: RecordingMockServer = RecordingMockServer(routes())) =
        AzureDevOpsPlatformService(server.engine).also {
            assertTrue(it.initialize(TestPluginContext(JsonObject(mapOf("token" to JsonPrimitive("t"))))))
        }

    @Test
    fun `CI project id is organization project repository`() =
        runTest {
            val env = mapOf(
                "TF_BUILD" to "True",
                "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://dev.azure.com/acme/",
                "SYSTEM_TEAMPROJECT" to "Shop",
                "BUILD_REPOSITORY_NAME" to "api",
            )
            val ci = AzureDevOpsPlatformService().detectCiEnvironment { env[it] }
            assertEquals("acme/Shop/api", ci?.projectId)
        }

    @Test
    fun `legacy visualstudio host gives the organization`() =
        runTest {
            val env = mapOf(
                "TF_BUILD" to "True",
                "SYSTEM_TEAMFOUNDATIONCOLLECTIONURI" to "https://acme.visualstudio.com/",
                "SYSTEM_TEAMPROJECT" to "Shop",
                "BUILD_REPOSITORY_NAME" to "api",
            )
            assertEquals("acme/Shop/api", AzureDevOpsPlatformService().detectCiEnvironment { env[it] }?.projectId)
        }

    @Test
    fun `additions and deletions come from the diff`() =
        runTest {
            val change = service().fetchMergeRequest(PROJECT, "7").changes.single()
            assertEquals(2, change.additions)
            assertEquals(1, change.deletions)
        }

    @Test
    fun `finding the summary reads the threads list once`() =
        runTest {
            val server = RecordingMockServer(routes())
            service(server).findExistingComment(PROJECT, "7", "<!-- s -->")
            assertEquals(
                1,
                server.requests().count {
                    it.url.encodedPath
                        .lowercase()
                        .endsWith("/threads")
                }
            )
        }

    @Test
    fun `vote 5 approved with suggestions stays an approval and a rejection is not`() {
        fun reviewer(
            id: String,
            vote: Int,
        ) = AzureDevOpsReviewer(id = id, displayName = id, uniqueName = "$id@x.test", vote = vote, isRequired = false)
        val pr = AzureDevOpsPullRequest(
            pullRequestId = 1,
            title = "t",
            status = "active",
            createdBy = AzureDevOpsUser(id = "a", displayName = "A"),
            creationDate = "2024-01-01T10:00:00Z",
            sourceRefName = "refs/heads/f",
            targetRefName = "refs/heads/main",
            reviewers = listOf(reviewer("suggest", 5), reviewer("approve", 10), reviewer("wait", -5), reviewer("reject", -10)),
        )
        val approvers = AzureDevOpsAdapter.toMergeRequest(pr, emptyList(), emptyList()).approvals.map { it.user.name }
        assertEquals(listOf("suggest", "approve"), approvers)
    }

    @Test
    fun `change entries without an item are skipped`() =
        runTest {
            val server = RecordingMockServer(routes(extraEntry = true))
            assertEquals(listOf("a.kt"), service(server).fetchMergeRequest(PROJECT, "7").changes.map { it.path })
        }

    private fun routes(extraEntry: Boolean = false): List<MockRoute> {
        fun get(
            suffix: String,
            body: String,
        ) = MockRoute({ matches(it, suffix) }, body)
        val extra = if (extraEntry) """,{"changeType":"edit"}""" else ""
        return listOf(
            MockRoute({ isContent(it, "head-blob") }, "x\nb\nc\n", TEXT),
            MockRoute({ isContent(it, "base-blob") }, "a\nb\n", TEXT),
            get(
                "$PR/iterations/1/changes",
                """{"changeEntries":[{"changeId":1,"changeType":"edit",""" +
                    """"item":{"objectId":"head-blob","originalObjectId":"base-blob","path":"/a.kt"}}$extra]}""",
            ),
            get(
                "$PR/iterations",
                """{"count":1,"value":[{"id":1,"sourceRefCommit":{"commitId":"h1"},""" +
                    """"targetRefCommit":{"commitId":"b1"},"commonRefCommit":{"commitId":"b1"}}]}""",
            ),
            get("$PR/commits", """{"count":0,"value":[]}"""),
            get("$PR/statuses", """{"count":0,"value":[]}"""),
            route(HttpMethod.Get, "$PR/threads", """{"count":0,"value":[]}"""),
            get(
                PR,
                """{"pullRequestId":7,"title":"Add key","description":"","status":"active",""" +
                    """"createdBy":{"id":"u1","displayName":"Dev","uniqueName":"dev@example.com"},""" +
                    """"creationDate":"2026-10-01T10:00:00Z","sourceRefName":"refs/heads/f","targetRefName":"refs/heads/main",""" +
                    """"isDraft":false,"reviewers":[],"labels":[],""" +
                    """"lastMergeSourceCommit":{"commitId":"h1"},"lastMergeTargetCommit":{"commitId":"b1"}}""",
            ),
        )
    }

    private fun matches(
        request: HttpRequestData,
        suffix: String,
    ) = request.method == HttpMethod.Get && request.url.encodedPath
        .lowercase()
        .endsWith(suffix.lowercase())

    private fun isContent(
        request: HttpRequestData,
        blobId: String,
    ) = request.url.encodedPath.endsWith("/blobs/$blobId")

    private companion object {
        const val PROJECT = "org/proj/demo"
        const val REPO = "/org/proj/_apis/git/repositories/demo"
        const val PR = "$REPO/pullrequests/7"
        const val TEXT = "text/plain"
    }
}
