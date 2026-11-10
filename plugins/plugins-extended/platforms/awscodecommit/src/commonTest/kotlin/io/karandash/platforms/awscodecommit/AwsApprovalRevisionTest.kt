package io.karandash.platforms.awscodecommit

import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class AwsApprovalRevisionTest {
    @Test
    fun `approval names the pull request's current revision`() =
        runTest {
            val bodies = mutableListOf<Pair<String?, String>>()
            val engine = MockEngine { request ->
                bodies += request.headers["X-Amz-Target"] to (request.body as TextContent).text
                val body = if (request.headers["X-Amz-Target"] == "CodeCommit_20150413.GetPullRequest") {
                    """
                    {"pullRequest": {"pullRequestId": "7", "title": "t", "pullRequestStatus": "OPEN",
                      "authorArn": "arn:aws:iam::1:user/dev", "creationDate": 1.7E9, "clientRequestToken": "x",
                      "revisionId": "rev-42", "pullRequestTargets": []}}
                    """.trimIndent()
                } else {
                    "{}"
                }
                respond(body, headers = headersOf(HttpHeaders.ContentType, "application/x-amz-json-1.1"))
            }
            val client = AwsCodeSuiteClient(region = "us-east-1", httpClient = HttpClient(engine))

            val revisionId = client.currentRevisionId("7")
            client.approvePullRequest("7", revisionId)

            val (target, payload) = bodies.last()
            assertEquals("CodeCommit_20150413.UpdatePullRequestApprovalState", target)
            val json = Json.parseToJsonElement(payload).jsonObject
            assertEquals("rev-42", json.getValue("revisionId").jsonPrimitive.content)
            assertEquals("APPROVE", json.getValue("approvalState").jsonPrimitive.content)
        }
}
