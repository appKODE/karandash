package io.karandash.cache.remote

import io.karandash.cache.cas.CasDigest
import io.karandash.crypto.AwsSigV4Request
import io.karandash.crypto.AwsSigV4Signer
import io.karandash.crypto.AwsStaticCredentials
import io.karandash.logger.Logger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Instant

/** Each backend the factory builds resolves its object URLs, signs (S3) and delegates every operation over HTTP. */
class RemoteCacheBackendFactoryTest {
    private val digest = CasDigest.of("blob".encodeToByteArray())
    private val sent = mutableListOf<Triple<String, String, Map<String, String>>>()
    private val warnings = mutableListOf<String>()
    private val logger = object : Logger {
        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private fun fetcher(status: Int) =
        RemoteCacheFetcher { method, url, _, headers ->
            sent += Triple(method, url, headers)
            RemoteCacheResponse(status, if (method == "GET") "blob".encodeToByteArray() else null)
        }

    private fun backend(
        config: RemoteCacheConfig,
        status: Int = 200,
    ) = RemoteCacheBackendFactory.create(config, fetcher(status), logger)

    @Test
    fun `s3 with explicit keys signs every operation and encodes the prefix`() =
        runTest {
            val config = RemoteCacheConfig(
                backend = "s3",
                url = "https://bucket.s3.amazonaws.com/",
                prefix = "/team cache/v1/",
                region = "eu-west-1",
                accessKeyId = "AKID",
                secretAccessKey = "secret",
                sessionToken = "session",
            )
            val s3 = backend(config)

            assertTrue(s3.exists(digest))
            assertContentEquals("blob".encodeToByteArray(), s3.pull(digest))
            assertTrue(s3.push(digest, "blob".encodeToByteArray()))
            s3.delete(digest)
            s3.shutdown()

            assertEquals(listOf("HEAD", "GET", "PUT", "DELETE"), sent.map { it.first })
            sent.forEach { (_, url, headers) ->
                assertTrue(url.startsWith("https://bucket.s3.amazonaws.com/team%20cache/v1/cas/"), url)
                assertTrue(headers.getValue("Authorization").contains("Credential=AKID/"))
                assertTrue(headers.getValue("Authorization").contains("/eu-west-1/s3/"))
                assertEquals("session", headers["X-Amz-Security-Token"])
                assertTrue(headers.containsKey("X-Amz-Date"))
                assertTrue(headers.containsKey("X-Amz-Content-Sha256"))
            }
        }

    @Test
    fun `s3 signature is computed from the configured secret access key`() =
        runTest {
            val blob = "blob".encodeToByteArray()
            val authorizations = listOf("secret-one", "secret-two").map { secret ->
                sent.clear()
                val config = RemoteCacheConfig(
                    backend = "s3",
                    url = "https://bucket.s3.amazonaws.com",
                    region = "eu-west-1",
                    accessKeyId = "AKID",
                    secretAccessKey = secret,
                )
                assertTrue(backend(config).push(digest, blob))
                val (_, url, headers) = sent.single()
                val amz = headers.getValue("X-Amz-Date")
                val expected = AwsSigV4Signer.sign(
                    AwsSigV4Request("PUT", "bucket.s3.amazonaws.com", url.substringAfter(".com"), "eu-west-1", "s3"),
                    blob,
                    AwsStaticCredentials("AKID", secret),
                    Instant.parse(
                        "${amz.take(4)}-${amz.substring(4, 6)}-${amz.substring(6, 8)}T" +
                            "${amz.substring(9, 11)}:${amz.substring(11, 13)}:${amz.substring(13, 15)}Z",
                    ),
                )
                assertEquals(expected.authorization, headers.getValue("Authorization"))
                expected.authorization
            }
            assertNotEquals(authorizations[0], authorizations[1])
        }

    @Test
    fun `s3 without keys is anonymous`() =
        runTest {
            backend(RemoteCacheConfig(backend = "s3", url = "https://minio.example.com:9000/bucket")).pull(digest)
            assertNull(sent.single().third["Authorization"])
            assertTrue(sent.single().second.startsWith("https://minio.example.com:9000/bucket/cas/"))
        }

    @Test
    fun `s3 with only one key warns and stays anonymous`() =
        runTest {
            backend(RemoteCacheConfig(backend = "s3", url = "https://minio.example.com", accessKeyId = "AKID")).pull(digest)
            assertNull(sent.single().third["Authorization"])
            assertTrue(warnings.single().contains("set both access-key-id and secret-access-key"))
        }

    @Test
    fun `s3 signs a url with a non default port and query`() =
        runTest {
            val config = RemoteCacheConfig(
                backend = "s3",
                url = "http://user@minio.local:8080",
                accessKeyId = "AKID",
                secretAccessKey = "secret",
            )
            backend(config).pull(digest)
            val headers = sent.single().third
            assertTrue(headers.getValue("Authorization").contains("Credential=AKID/"))
            assertNull(headers["X-Amz-Security-Token"])
        }

    @Test
    fun `gcs joins bucket and prefix and delegates every operation`() =
        runTest {
            val gcs = backend(RemoteCacheConfig(backend = "gcs", url = "https://storage.googleapis.com/b/", prefix = "/p/"), status = 404)

            assertFalse(gcs.exists(digest))
            assertNull(gcs.pull(digest))
            assertFalse(gcs.push(digest, ByteArray(1)))
            gcs.delete(digest)
            gcs.shutdown()

            assertEquals(4, sent.size)
            sent.forEach { assertTrue(it.second.startsWith("https://storage.googleapis.com/b/p/cas/"), it.second) }
        }

    @Test
    fun `gcs and http without prefix use the bare url`() =
        runTest {
            backend(RemoteCacheConfig(backend = "gcs", url = "https://storage.googleapis.com/b")).pull(digest)
            backend(RemoteCacheConfig(backend = "http", url = "https://cache.example.com")).pull(digest)
            backend(RemoteCacheConfig(backend = "http", url = "https://cache.example.com/", prefix = "/x/")).pull(digest)
            assertEquals(
                listOf("https://storage.googleapis.com/b/cas/", "https://cache.example.com/cas/", "https://cache.example.com/x/cas/"),
                sent.map { it.second.substringBefore(digest.shard) },
            )
        }

    @Test
    fun `unknown backend names the supported ones`() {
        val error = assertFailsWith<IllegalStateException> { backend(RemoteCacheConfig(backend = "ftp", url = "ftp://x")) }
        assertTrue(error.message.orEmpty().contains("Supported: http, s3, gcs"))
    }

    @Test
    fun `config toString never prints secrets`() {
        val text = RemoteCacheConfig("s3", "https://x", auth = "tok", secretAccessKey = "sec", sessionToken = "ses").toString()
        assertFalse(listOf("tok", "sec", "ses").any { it in text })
    }
}
