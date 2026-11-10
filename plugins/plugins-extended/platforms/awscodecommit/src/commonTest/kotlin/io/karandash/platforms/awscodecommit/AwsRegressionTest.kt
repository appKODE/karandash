@file:OptIn(ExperimentalKarandashApi::class)

package io.karandash.platforms.awscodecommit

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.platform.MergeRequestField
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
import kotlin.test.assertTrue

/** AWS line counts from the diff, GovCloud regions, commit walk. */
class AwsRegressionTest {
    private suspend fun service(region: String = "us-east-1") =
        AwsCodeCommitPlatformService(RecordingMockServer(routes()).engine).also {
            val config = mapOf("region" to region, "accessKeyId" to "AKIDEXAMPLE", "secretAccessKey" to "secret")
            assertTrue(it.initialize(TestPluginContext(JsonObject(config.mapValues { e -> JsonPrimitive(e.value) }))))
        }

    @Test
    fun `additions and deletions come from the diff`() =
        runTest {
            val change = service().fetchMergeRequest("demo", "7").changes.single()
            assertEquals(2, change.additions)
            assertEquals(1, change.deletions)
        }

    @Test
    fun `GovCloud region is accepted`() =
        runTest {
            val mr = service("us-gov-west-1").fetchMergeRequest("demo", "7")
            assertEquals("Add key", mr.title)
        }

    @Test
    fun `labels are declared unavailable`() {
        assertTrue(MergeRequestField.LABELS in AwsCodeCommitPlatformService().unavailableMergeRequestFields())
    }

    @Test
    fun `commits come from the first parent walk`() =
        runTest {
            assertEquals(listOf("h1"), service().fetchMergeRequest("demo", "7").commits.map { it.sha })
        }

    private fun routes() =
        listOf(
            amzTarget(
                "$OP.GetPullRequest",
                """{"pullRequest":{"pullRequestId":"7","title":"Add key","description":"","pullRequestStatus":"OPEN",""" +
                    """"authorArn":"arn:aws:iam::123456789012:user/dev","creationDate":"2026-10-01T10:00:00Z",""" +
                    """"revisionId":"rev-1","pullRequestTargets":[{"repositoryName":"demo","sourceReference":"refs/heads/f",""" +
                    """"destinationReference":"refs/heads/main","sourceCommit":"h1","destinationCommit":"b1"}]}}""",
            ),
            amzTarget(
                "$OP.GetDifferences",
                """{"differences":[{"changeType":"M","beforeBlob":{"blobId":"blob-base","path":"a.kt","mode":"100644"},""" +
                    """"afterBlob":{"blobId":"blob-head","path":"a.kt","mode":"100644"}}]}""",
            ),
            amzTarget(
                "$OP.GetCommit",
                """{"commit":{"commitId":"h1","treeId":"t1","parents":["b1"],"message":"add key",""" +
                    """"author":{"name":"Dev","email":"dev@example.com","date":"1700000000 +0000"},""" +
                    """"committer":{"name":"Dev","email":"dev@example.com","date":"1700000000 +0000"}}}""",
            ),
            blob("blob-head", "x\nb\nc\n"),
            blob("blob-base", "a\nb\n"),
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

    private fun text(request: HttpRequestData) = (request.body as? TextContent)?.text.orEmpty()

    private companion object {
        const val OP = "CodeCommit_20150413"
        const val JSON11 = "application/x-amz-json-1.1"
    }
}
