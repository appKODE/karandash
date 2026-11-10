package io.karandash.platforms.awscodecommit

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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AwsCodeCommitApprovalTest {
    @Test
    fun `approve sets APPROVE on the current revision when the caller has not approved`() =
        runTest {
            val server = RecordingMockServer(routes(approvalState = "REVOKE"))

            service(server).approve("demo", "7")

            assertEquals(UPDATE, targets(server).last())
            val body = text(server.requests().last())
            assertTrue(""""approvalState":"APPROVE"""" in body && """"revisionId":"rev-1"""" in body)
        }

    @Test
    fun `approve is a no-op when the caller already approved`() =
        runTest {
            val server = RecordingMockServer(routes(approvalState = "APPROVE"))

            service(server).approve("demo", "7")

            assertTrue(UPDATE !in targets(server))
        }

    @Test
    fun `unapprove revokes the approval of the caller`() =
        runTest {
            val server = RecordingMockServer(routes(approvalState = "APPROVE"))

            service(server).unapprove("demo", "7")

            assertEquals(UPDATE, targets(server).last())
            assertTrue(""""approvalState":"REVOKE"""" in text(server.requests().last()))
        }

    @Test
    fun `unapprove is a no-op when the caller has not approved`() =
        runTest {
            val server = RecordingMockServer(routes(approvalState = "REVOKE"))

            service(server).unapprove("demo", "7")

            assertTrue(UPDATE !in targets(server))
        }

    private suspend fun service(server: RecordingMockServer) =
        AwsCodeCommitPlatformService(server.engine).also {
            val config = mapOf("region" to "us-east-1", "accessKeyId" to "AKIDEXAMPLE", "secretAccessKey" to "secret")
            it.initialize(TestPluginContext(JsonObject(config.mapValues { e -> JsonPrimitive(e.value) })))
        }

    private suspend fun targets(server: RecordingMockServer) = server.requests().map { it.headers[AMZ_TARGET].orEmpty() }

    private fun text(request: HttpRequestData) = (request.body as? TextContent)?.text.orEmpty()

    private fun routes(approvalState: String) =
        listOf(
            MockRoute(
                { "GetCallerIdentity" in text(it) },
                "<GetCallerIdentityResponse><GetCallerIdentityResult><Arn>$ARN</Arn></GetCallerIdentityResult></GetCallerIdentityResponse>",
                "text/xml",
            ),
            amzTarget(
                "$OP.GetPullRequest",
                """{"pullRequest":{"pullRequestId":"7","title":"t","pullRequestStatus":"OPEN","revisionId":"rev-1",""" +
                    """"authorArn":"$ARN","creationDate":"2026-10-01T10:00:00Z",""" +
                    """"pullRequestTargets":[]}}""",
            ),
            amzTarget(
                "$OP.GetPullRequestApprovalStates",
                """{"approvals":[{"userArn":"$ARN","approvalState":"$approvalState"}]}""",
            ),
            amzTarget(UPDATE, "{}"),
        )

    private companion object {
        const val OP = "CodeCommit_20150413"
        const val UPDATE = "$OP.UpdatePullRequestApprovalState"
        const val ARN = "arn:aws:iam::123456789012:user/bot"
    }
}
