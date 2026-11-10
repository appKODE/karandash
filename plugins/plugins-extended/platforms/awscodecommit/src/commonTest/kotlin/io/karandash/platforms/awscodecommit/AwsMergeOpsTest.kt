package io.karandash.platforms.awscodecommit

import io.karandash.api.PluginContext
import io.karandash.api.platform.MergeOptions
import io.karandash.api.platform.PlatformOperation
import io.karandash.api.support.mapToJsonElement
import io.karandash.crypto.AwsCredentialChain
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AwsMergeOpsTest {
    @Serializable
    private data class Captured(
        val host: String,
        val target: String?,
        val authorization: String?,
        val body: String,
    ) {
        val json: JsonObject get() = Json.parseToJsonElement(body).jsonObject

        fun field(name: String): String = json.getValue(name).jsonPrimitive.content
    }

    private val prJson =
        """
        {"pullRequest": {"pullRequestId": "7", "title": "t", "pullRequestStatus": "OPEN",
          "authorArn": "arn:aws:iam::1:user/dev", "creationDate": 1.7E9, "clientRequestToken": "x", "revisionId": "rev-1",
          "pullRequestTargets": [{"repositoryName": "repo", "sourceReference": "refs/heads/feature/x",
            "destinationReference": "refs/heads/main", "sourceCommit": "src123", "destinationCommit": "dst456"}]}}
        """.trimIndent()

    private suspend fun serviceWith(
        captured: MutableList<Captured>,
        status: HttpStatusCode = HttpStatusCode.OK,
        stsStatus: HttpStatusCode = HttpStatusCode.OK,
        credentials: Boolean = true,
    ): AwsCodeCommitPlatformService {
        val engine = MockEngine { request ->
            val target = request.headers["X-Amz-Target"]
            captured += Captured(request.url.host, target, request.headers[HttpHeaders.Authorization], (request.body as TextContent).text)
            when {
                request.url.host.startsWith("sts.") -> respond(STS_RESPONSE, stsStatus)

                target == "CodeCommit_20150413.GetPullRequest" -> respond(
                    prJson,
                    headers = headersOf(HttpHeaders.ContentType, "application/json")
                )

                else -> respond("""{"message": "x"}""", status)
            }
        }
        val service = AwsCodeCommitPlatformService(credentialChain = { AwsCredentialChain(env = { null }) })
        val config = if (credentials) mapOf("accessKeyId" to "AKIDEXAMPLE", "secretAccessKey" to "secret") else emptyMap()
        assertTrue(service.initialize(context(config)))
        service.client.rebuild("test") {
            AwsCodeSuiteClient(
                region = "us-east-1",
                accessKeyId = config["accessKeyId"],
                secretAccessKey = config["secretAccessKey"],
                engine = engine,
            )
        }
        return service
    }

    @Test
    fun `squash merge with branch deletion merges at the source tip then deletes the branch`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)

            val options = MergeOptions(squash = true, deleteSourceBranch = true, commitMessage = "m")
            assertTrue(service.mergeMr("repo", "7", options).isSuccess)

            val merge = captured.single { it.target == "CodeCommit_20150413.MergePullRequestBySquash" }
            assertEquals("7", merge.field("pullRequestId"))
            assertEquals("repo", merge.field("repositoryName"))
            assertEquals("src123", merge.field("sourceCommitId"))
            assertEquals("m", merge.field("commitMessage"))
            val delete = captured.last()
            assertEquals("CodeCommit_20150413.DeleteBranch", delete.target)
            assertEquals("feature/x", delete.field("branchName"))
        }

    @Test
    fun `default merge is three-way and keeps the branch`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)

            assertTrue(service.mergeMr("repo", "7").isSuccess)

            assertEquals(
                listOf("CodeCommit_20150413.GetPullRequest", "CodeCommit_20150413.MergePullRequestByThreeWay"),
                captured.map { it.target },
            )
            assertFalse(captured.last().json.containsKey("commitMessage"))
        }

    @Test
    fun `closeMr and reopenMr update the pull request status`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val service = serviceWith(captured)

            assertTrue(service.closeMr("repo", "7").isSuccess)
            assertTrue(service.reopenMr("repo", "7").isSuccess)

            assertTrue(captured.all { it.target == "CodeCommit_20150413.UpdatePullRequestStatus" })
            assertEquals(listOf("CLOSED", "OPEN"), captured.map { it.field("pullRequestStatus") })
        }

    @Test
    fun `merge and status changes fail on an API error`() =
        runTest {
            val service = serviceWith(mutableListOf(), status = HttpStatusCode.BadRequest)

            assertFalse(service.mergeMr("repo", "7").isSuccess)
            assertFalse(service.closeMr("repo", "7").isSuccess)
        }

    @Test
    fun `checkAuth signs STS GetCallerIdentity and maps 403 to false`() =
        runTest {
            val captured = mutableListOf<Captured>()
            assertEquals(true, serviceWith(captured).checkAuth())
            assertEquals(false, serviceWith(captured, stsStatus = HttpStatusCode.Forbidden).checkAuth())

            val call = captured.first()
            assertEquals("sts.us-east-1.amazonaws.com", call.host)
            assertEquals("Action=GetCallerIdentity&Version=2011-06-15", call.body)
            assertTrue(call.authorization.orEmpty().contains("/us-east-1/sts/aws4_request"), call.authorization)
        }

    @Test
    fun `checkAuth is null without explicit credentials`() =
        runTest {
            val captured = mutableListOf<Captured>()
            assertNull(serviceWith(captured, credentials = false).checkAuth())
            assertTrue(captured.isEmpty())
        }

    @Test
    fun `approve is skipped when STS reports the caller already approved`() =
        runTest {
            val captured = mutableListOf<Captured>()
            val engine = MockEngine { request ->
                val target = request.headers["X-Amz-Target"]
                captured += Captured(request.url.host, target, null, (request.body as TextContent).text)
                val body = when (target) {
                    null -> STS_RESPONSE
                    "CodeCommit_20150413.GetPullRequest" -> prJson
                    else -> """{"approvals": [{"userArn": "$CALLER_ARN", "approvalState": "APPROVE"}]}"""
                }
                respond(body)
            }
            val client = AwsCodeSuiteClient(accessKeyId = "AKIDEXAMPLE", secretAccessKey = "secret", engine = engine)

            assertEquals(CALLER_ARN, client.getCallerArn())
            assertEquals(true, client.isApprovedByCurrentCaller("7", "rev-1"))
        }

    @Test
    fun `capabilities advertise merge and inline comments`() =
        runTest {
            val caps = AwsCodeCommitPlatformService()
            assertTrue(caps.supports(PlatformOperation.MERGE))
            assertTrue(caps.supports(PlatformOperation.POST_INLINE_COMMENTS))
        }

    @Test
    fun `reinitialize keeps the client until the credentials change`() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            service.initialize(context(mapOf("region" to "us-east-1")))
            val first = service.client.value

            service.initialize(context(mapOf("region" to "us-east-1")))
            assertSame(first, service.client.value, "unchanged config rebuilt the client")

            service.initialize(context(mapOf("region" to "eu-west-1")))
            assertNotSame(first, service.client.value)

            service.shutdown()
            assertNull(service.client.value)
        }

    @Test
    fun `initialize after shutdown builds a fresh client`() =
        runTest {
            val service = AwsCodeCommitPlatformService()
            assertTrue(service.initialize(context(mapOf("region" to "us-east-1"))))
            val first = assertNotNull(service.client.value)

            service.shutdown()
            service.shutdown()
            assertNull(service.client.value)

            assertTrue(service.initialize(context(mapOf("region" to "us-east-1"))))
            val second = assertNotNull(service.client.value, "re-initialize after shutdown left no client")
            assertNotSame(first, second, "re-initialize after shutdown reused the closed client")
        }

    private fun context(config: Map<String, String>): PluginContext =
        object : PluginContext {
            override val pluginConfig = mapToJsonElement(config)
            override val hostVersion = "1.0.0-test"
            override val workingDirectory = "/tmp/test"

            override fun env(name: String): String? = null

            override fun log(message: String) {}

            override fun warn(message: String) {}
        }

    private companion object {
        const val CALLER_ARN = "arn:aws:iam::123456789012:user/bot"
        const val STS_RESPONSE =
            "<GetCallerIdentityResponse><GetCallerIdentityResult><Arn>$CALLER_ARN</Arn></GetCallerIdentityResult></GetCallerIdentityResponse>"
    }
}
