package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.testing.test
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `cache push` against a private-network backend under `allow_private_network: true`, and the rule that a
 * `--backend-url` other than `cache.remote.url` gets none of the config's credentials.
 */
class CachePushRemoteConfigTest {
    private val dir = TestFiles.createTestTempDirectory("karandash-cache-push-config")

    /** Runs `cache push` with [args] against a MockEngine; returns the exit result and each PUT as `url auth=<Authorization>`. */
    private fun push(
        config: String,
        vararg args: String,
        awsEnv: Map<String, String> = emptyMap(),
    ): Pair<com.github.ajalt.clikt.testing.CliktCommandTestResult, List<String>> {
        CasBlobStore(Path(dir.toString(), "cas")).put("blob".encodeToByteArray())
        val file = Path(dir, "karandash.yaml").also { TestFiles.writeText(it, config) }
        val puts = mutableListOf<String>()
        val engine = MockEngine { request ->
            if (request.method == HttpMethod.Put) {
                puts += "${request.url} auth=${request.headers[HttpHeaders.Authorization]}"
                respond(ByteArray(0), HttpStatusCode.Created)
            } else {
                respond(ByteArray(0), HttpStatusCode.NotFound)
            }
        }
        val result = cachePushCommand(engine, awsEnv).test(listOf("--cache-dir", dir.toString(), "--config", file.toString()) + args)
        return result to puts
    }

    private fun remote(
        url: String,
        allowPrivateNetwork: Boolean = false,
    ) = (if (allowPrivateNetwork) "allow_private_network: true\n" else "") +
        "cache:\n  remote:\n    backend: http\n    url: \"$url\"\n    auth: remote-token\n"

    @Test
    fun `private network backend is pushed to under allow_private_network`() {
        val (blocked, blockedPuts) = push(remote(PRIVATE_URL))
        assertEquals(ExitCodes.EXECUTION_ERROR, blocked.statusCode, blocked.output)
        assertTrue(blockedPuts.isEmpty(), "PUT sent to a private host without allow_private_network: $blockedPuts")

        val (allowed, puts) = push(remote(PRIVATE_URL, allowPrivateNetwork = true))
        assertEquals(ExitCodes.SUCCESS, allowed.statusCode, allowed.output)
        assertEquals(1, puts.size, puts.toString())
        assertTrue(puts.single().startsWith("$PRIVATE_URL/cas/") && puts.single().endsWith("auth=Bearer remote-token"), puts.toString())
    }

    @Test
    fun `backend-url overriding the configured url gets no config credentials`() {
        val (result, puts) = push(remote(CONFIGURED_URL), "--backend-url", OTHER_URL)
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().startsWith("$OTHER_URL/cas/") && puts.single().endsWith("auth=null"), puts.toString())
        assertTrue("remote-token" !in result.output, "auth leaked: ${result.output}")
    }

    @Test
    fun `backend-url naming the configured url keeps the config credentials`() {
        val (result, puts) = push(remote(CONFIGURED_URL), "--backend-url", "HTTPS://Cache.Example.com/")
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().endsWith("auth=Bearer remote-token"), puts.toString())
    }

    private fun s3Remote(url: String = S3_URL) = "cache:\n  remote:\n    backend: s3\n    url: \"$url\"\n"

    @Test
    fun `s3 without config keys signs with the AWS env credentials`() {
        val (result, puts) = push(s3Remote(), awsEnv = AWS_ENV)
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().contains("auth=AWS4-HMAC-SHA256 Credential=AKIDENV/"), puts.toString())
        assertTrue("env-secret" !in result.output && "env-token" !in result.output, result.output)
    }

    @Test
    fun `s3 without config keys or AWS credentials stays anonymous`() {
        val (result, puts) = push(s3Remote())
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().endsWith("auth=null"), puts.toString())
    }

    @Test
    fun `backend-url overriding the configured url gets no AWS chain credentials`() {
        val (result, puts) = push(s3Remote(), "--backend-url", OTHER_S3_URL, awsEnv = AWS_ENV)
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().startsWith("$OTHER_S3_URL/") && puts.single().endsWith("auth=null"), puts.toString())
    }

    @Test
    fun `s3 on a non-AWS host gets no AWS chain credentials`() {
        val (result, puts) = push(s3Remote(CONFIGURED_URL), awsEnv = AWS_ENV)
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().endsWith("auth=null"), puts.toString())
    }

    @Test
    fun `cache remote inherited from an extends parent signs with the AWS env credentials`() {
        TestFiles.writeText(Path(dir, "parent.yaml"), s3Remote())
        val (result, puts) = push("extends:\n  - url: parent.yaml\n", awsEnv = AWS_ENV)
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().startsWith("$S3_URL/") && puts.single().contains("Credential=AKIDENV/"), puts.toString())
    }

    @Test
    fun `profile picked by an extends parent is used like the root own`() {
        SystemFileSystem.createDirectories(Path(dir, ".aws"))
        TestFiles.writeText(Path(dir, ".aws", "credentials"), "[prod]\naws_access_key_id=AKIDPROD\naws_secret_access_key=prod-secret\n")
        val home = mapOf("HOME" to dir.toString())
        TestFiles.writeText(Path(dir, "parent.yaml"), "cache:\n  remote:\n    profile: prod\n")
        val (inherited, inheritedPuts) = push("extends:\n  - url: parent.yaml\n" + s3Remote(), awsEnv = home)
        assertEquals(ExitCodes.SUCCESS, inherited.statusCode, inherited.output)
        assertTrue(inheritedPuts.single().contains("Credential=AKIDPROD/"), inheritedPuts.toString())
    }

    @Test
    fun `gcs backend pushes under the bucket prefix with the config auth`() {
        val config = "cache:\n  remote:\n    backend: gcs\n    url: \"$GCS_URL/\"\n    prefix: /ci/\n    auth: gcs-token\n"
        val (result, puts) = push(config)
        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(puts.single().startsWith("$GCS_URL/ci/cas/") && puts.single().endsWith("auth=Bearer gcs-token"), puts.toString())
    }

    private companion object {
        val AWS_ENV =
            mapOf("AWS_ACCESS_KEY_ID" to "AKIDENV", "AWS_SECRET_ACCESS_KEY" to "env-secret", "AWS_SESSION_TOKEN" to "env-token")
        const val PRIVATE_URL = "https://10.0.0.5/cache"
        const val CONFIGURED_URL = "https://cache.example.com"
        const val OTHER_URL = "https://other.example.com"
        const val S3_URL = "https://bucket.s3.amazonaws.com"
        const val OTHER_S3_URL = "https://other.s3.amazonaws.com"
        const val GCS_URL = "https://storage.googleapis.com/karandash-cache"
    }
}
