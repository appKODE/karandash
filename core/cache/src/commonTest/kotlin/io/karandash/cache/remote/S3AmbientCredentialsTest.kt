package io.karandash.cache.remote

import io.karandash.cache.cas.CasDigest
import io.karandash.crypto.AwsCredentialChain
import io.karandash.logger.NoOpLogger
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The ambient AWS chain signs S3 cache requests only for AWS hosts, and supplies the region only when config has none. */
class S3AmbientCredentialsTest {
    private val chain =
        AwsCredentialChain(
            env = mapOf("AWS_ACCESS_KEY_ID" to "ENVID", "AWS_SECRET_ACCESS_KEY" to "env-secret", "AWS_REGION" to "eu-north-1")::get,
            readFile = { null },
        )

    private suspend fun authorization(
        config: RemoteCacheConfig,
        regionExplicit: Boolean = true,
    ): String? {
        val auth = mutableListOf<String?>()
        val fetcher = RemoteCacheFetcher { _, _, _, headers ->
            auth += headers["Authorization"]
            RemoteCacheResponse(404)
        }
        RemoteCacheBackendFactory.create(config, fetcher, NoOpLogger, chain, regionExplicit).pull(CasDigest.of("x".encodeToByteArray()))
        return auth.first()
    }

    @Test
    fun `chain credentials are not sent to a non-AWS host`() =
        runTest {
            assertNull(authorization(RemoteCacheConfig(backend = "s3", url = "https://minio.example.com/bucket")))
        }

    @Test
    fun `chain credentials sign for an AWS S3 host`() =
        runTest {
            val auth = authorization(RemoteCacheConfig(backend = "s3", url = "https://bucket.s3.amazonaws.com"))
            assertTrue(auth?.contains("Credential=ENVID/") == true && "/us-east-1/s3/" in auth, auth)
        }

    @Test
    fun `absent region falls back to the chain region`() =
        runTest {
            val auth = authorization(RemoteCacheConfig(backend = "s3", url = "https://bucket.s3.amazonaws.com"), regionExplicit = false)
            assertTrue(auth?.contains("/eu-north-1/s3/") == true, auth)
        }

    @Test
    fun `explicit keys still sign for a non-AWS host`() =
        runTest {
            val config =
                RemoteCacheConfig(backend = "s3", url = "https://minio.example.com", accessKeyId = "CFG", secretAccessKey = "cfg-secret")
            assertEquals(true, authorization(config)?.contains("Credential=CFG/"))
        }
}
