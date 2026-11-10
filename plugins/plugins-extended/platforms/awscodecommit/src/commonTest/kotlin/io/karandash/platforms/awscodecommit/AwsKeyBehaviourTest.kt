package io.karandash.platforms.awscodecommit

import io.karandash.crypto.AwsCredentialChain
import io.karandash.testfixtures.RecordingMockServer
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.pathEndsWith
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Each AWS CodeCommit key changes the signed request: host, credential scope, signature or session token header. */
class AwsKeyBehaviourTest {
    private val explicit = mapOf("accessKeyId" to "AKIDEXAMPLE", "secretAccessKey" to "secret")

    private suspend fun requests(
        config: Map<String, String>,
        chain: AwsCredentialChain = AwsCredentialChain(env = { null }, readFile = { null }),
        call: suspend (AwsCodeCommitPlatformService) -> Unit = { it.findExistingComment("demo", "7", "marker") },
    ): List<HttpRequestData> {
        val server = RecordingMockServer(listOf(pathEndsWith("/", "{}")))
        val service = AwsCodeCommitPlatformService({ chain }, server.engine)
        assertTrue(service.initialize(TestPluginContext(JsonObject(config.mapValues { JsonPrimitive(it.value) }))))
        runCatching { call(service) }
        return server.requests()
    }

    private fun auth(request: HttpRequestData) = request.headers[HttpHeaders.Authorization].orEmpty()

    @Test
    fun `region selects the host and the signing scope`() =
        runTest {
            val request = requests(explicit + ("region" to "eu-west-1")).first()
            assertEquals("codecommit.eu-west-1.amazonaws.com", request.url.host)
            assertTrue("/eu-west-1/codecommit/aws4_request" in auth(request), auth(request))
        }

    @Test
    fun `accessKeyId is the signing credential`() =
        runTest {
            assertTrue("Credential=AKIDEXAMPLE/" in auth(requests(explicit).first()))
        }

    @Test
    fun `secretAccessKey changes the request signature`() =
        runTest {
            suspend fun signature(secret: String) =
                auth(requests(explicit + ("secretAccessKey" to secret)).first()).substringAfter("Signature=").takeWhile { it != ',' }
            assertTrue(signature("secret-a").isNotEmpty())
            assertTrue(signature("secret-a") != signature("secret-b"))
        }

    @Test
    fun `sessionToken is sent as the security token header`() =
        runTest {
            val with = requests(explicit + ("sessionToken" to "tok-123")).first()
            assertEquals("tok-123", with.headers["X-Amz-Security-Token"])
            assertNull(requests(explicit).first().headers["X-Amz-Security-Token"])
        }

    @Test
    fun `profile takes the signing keys from the shared credentials file`() =
        runTest {
            val file = "[ci]\naws_access_key_id=FILEID\naws_secret_access_key=file-secret"
            val chain = AwsCredentialChain(env = { if (it == "HOME") "/h" else null }, readFile = { file })
            assertTrue("Credential=FILEID/" in auth(requests(mapOf("profile" to "ci"), chain).first()))
        }

    @Test
    fun `endpoint replaces the CodeCommit host`() =
        runTest {
            val request = requests(explicit + ("endpoint" to "https://codecommit.vpc.example.com")).first()
            assertEquals("codecommit.vpc.example.com", request.url.host)
        }

    @Test
    fun `stsEndpoint replaces the host of the credential check`() =
        runTest {
            val request = requests(explicit + ("stsEndpoint" to "https://sts.vpc.example.com")) { it.checkAuth() }.first()
            assertEquals("sts.vpc.example.com", request.url.host)
            assertSigned(request)
        }

    @Test
    fun `the credential check reaches the default STS host signed`() =
        runTest {
            val request = requests(explicit) { it.checkAuth() }.first()
            assertEquals("sts.us-east-1.amazonaws.com", request.url.host)
            assertSigned(request)
        }

    private fun assertSigned(request: HttpRequestData) {
        assertTrue("/us-east-1/sts/aws4_request" in auth(request), "STS request lost its signature: ${request.headers}")
        assertTrue(request.headers["X-Amz-Date"] != null, "${request.headers}")
    }
}
