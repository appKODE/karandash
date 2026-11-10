package io.karandash.platforms.awscodecommit

import io.karandash.crypto.AwsCredentialChain
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** `endpoint` / `stsEndpoint` replace the regional AWS hosts; both are SSRF-checked and honour allowLocalhost. */
class AwsEndpointOverrideTest {
    private fun client(seen: MutableList<HttpRequestData>): AwsCodeSuiteClient {
        val engine = MockEngine { request ->
            seen += request
            val sts = request.url.encodedPath.startsWith("/sts")
            val body = if (sts) "<Arn>arn:aws:iam::1:user/a</Arn>" else PULL_REQUEST
            respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        return AwsCodeSuiteClient(
            region = "eu-west-1",
            accessKeyId = "AKIDEXAMPLE",
            secretAccessKey = "secret",
            httpClient = HttpClient(engine),
            allowLocalhost = true,
            endpoint = "http://localhost:4566/aws",
            stsEndpoint = "http://localhost:4566/sts",
        )
    }

    @Test
    fun codeCommitCallsGoToTheEndpointSignedForItsHost() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            client(seen).fetchPullRequest("1")
            val request = seen.single()
            assertEquals("localhost", request.url.host)
            assertEquals(4566, request.url.port)
            assertEquals("/aws/", request.url.encodedPath)
            val auth = request.headers[HttpHeaders.Authorization].orEmpty()
            assertTrue(auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/"), auth)
            assertTrue("/eu-west-1/codecommit/aws4_request, SignedHeaders=" in auth, auth)
            assertTrue("host" in auth.substringAfter("SignedHeaders=").substringBefore(",").split(';'), auth)
        }

    @Test
    fun credentialCheckGoesToTheStsEndpoint() =
        runTest {
            val seen = mutableListOf<HttpRequestData>()
            assertEquals(true, client(seen).checkCallerIdentity())
            val request = seen.single()
            assertEquals("/sts/", request.url.encodedPath)
            assertTrue("/eu-west-1/sts/aws4_request" in request.headers[HttpHeaders.Authorization].orEmpty())
        }

    @Test
    fun loopbackEndpointsNeedAllowLocalhost() {
        assertFails { AwsCodeSuiteClient(endpoint = "http://localhost:4566") }
        assertFails { AwsCodeSuiteClient(stsEndpoint = "http://127.0.0.1:4566") }
    }

    @Test
    fun credentialsCarryTheEndpointsFromConfig() {
        val config = buildJsonObject {
            put("endpoint", JsonPrimitive("https://codecommit.example.com"))
            put("stsEndpoint", JsonPrimitive("https://sts.example.com"))
        }
        val resolved = AwsCredentials.resolve(config, AwsCredentialChain(env = { null }))
        assertEquals("https://codecommit.example.com", resolved.endpoint)
        assertEquals("https://sts.example.com", resolved.stsEndpoint)
        assertNull(AwsCredentials.resolve(buildJsonObject { }, AwsCredentialChain(env = { null })).endpoint)
    }

    private companion object {
        const val PULL_REQUEST =
            """{"pullRequest":{"pullRequestId":"1","title":"t","pullRequestStatus":"OPEN","authorArn":"a",""" +
                """"creationDate":1700000000}}"""
    }
}
