@file:OptIn(ExperimentalPathApi::class)

package io.karandash.cacheserver

import io.karandash.cache.cas.CasDigest
import io.karandash.logger.NoOpLogger
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.header
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsBytes
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import java.nio.file.Path
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.deleteRecursively
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class CacheServerModuleTest {
    private val root: Path = tempDir("cache-server-test")
    private val content = "cached analysis".encodeToByteArray()
    private val digest = CasDigest.of(content)
    private val path = "/team-a/cas/${digest.shard}/${digest.remainder}"

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun server(
        config: CacheServerConfig = CacheServerConfig(dataDir = root.toString()),
        test: suspend ApplicationTestBuilder.(HttpClient, CacheMetrics) -> Unit,
    ) = testApplication {
        val metrics = CacheMetrics()
        val store = BlobStore(root, config.maxSizeBytes, NoOpLogger, metrics)
        application { cacheServerModule(config, store, metrics, NoOpLogger) }
        test(client, metrics)
    }

    @Test
    fun `put then get head and delete round trip`() =
        server { client, _ ->
            assertEquals(HttpStatusCode.NotFound, client.head(path).status)
            assertEquals(HttpStatusCode.NotFound, client.get(path).status)
            assertEquals(HttpStatusCode.Created, client.put(path) { setBody(content) }.status)
            assertEquals(HttpStatusCode.OK, client.head(path).status)
            val response = client.get(path)
            assertEquals(HttpStatusCode.OK, response.status)
            assertContentEquals(content, response.bodyAsBytes())
            assertEquals(HttpStatusCode.NoContent, client.delete(path).status)
            assertEquals(HttpStatusCode.NotFound, client.delete(path).status)
        }

    @Test
    fun `put whose body does not hash to the key is rejected`() =
        server { client, metrics ->
            assertEquals(HttpStatusCode.BadRequest, client.put(path) { setBody("tampered".encodeToByteArray()) }.status)
            assertEquals(HttpStatusCode.NotFound, client.get(path).status)
            assertEquals(1L, metrics.rejected.value)
        }

    @Test
    fun `put over the blob limit is 413`() =
        server(CacheServerConfig(dataDir = root.toString(), maxBlobBytes = 4)) { client, _ ->
            assertEquals(HttpStatusCode.PayloadTooLarge, client.put(path) { setBody(content) }.status)
        }

    @Test
    fun `a path that is not a cas digest is 400`() =
        server { client, _ ->
            assertEquals(HttpStatusCode.BadRequest, client.get("/team-a/cas/ab/not-hex").status)
            assertEquals(HttpStatusCode.BadRequest, client.get("/team-a/blobs/${digest.shard}/${digest.remainder}").status)
        }

    @Test
    fun `namespaces keep projects apart and the root namespace works`() =
        server { client, _ ->
            client.put(path) { setBody(content) }
            assertEquals(HttpStatusCode.NotFound, client.get("/team-b/cas/${digest.shard}/${digest.remainder}").status)
            val rootPath = "/cas/${digest.shard}/${digest.remainder}"
            assertEquals(HttpStatusCode.NotFound, client.get(rootPath).status)
            assertEquals(HttpStatusCode.Created, client.put(rootPath) { setBody(content) }.status)
            assertEquals(HttpStatusCode.OK, client.get(rootPath).status)
        }

    @Test
    fun `missing or wrong token is 401 while health stays open`() =
        server(CacheServerConfig(dataDir = root.toString(), tokens = listOf("one", "two"))) { client, _ ->
            assertEquals(HttpStatusCode.Unauthorized, client.put(path) { setBody(content) }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(HttpHeaders.Authorization, "Bearer three") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get(path) { header(HttpHeaders.Authorization, "Basic two") }.status)
            assertEquals(HttpStatusCode.Unauthorized, client.get("/metrics").status)
            val put = client.put(path) {
                header(HttpHeaders.Authorization, "Bearer two")
                setBody(content)
            }
            assertEquals(HttpStatusCode.Created, put.status)
            assertEquals(HttpStatusCode.OK, client.get(path) { header(HttpHeaders.Authorization, "Bearer one") }.status)
            assertEquals("ok", client.get("/health").bodyAsText())
        }

    @Test
    fun `metrics count hits misses puts and bytes`() =
        server { client, _ ->
            client.get(path)
            client.put(path) { setBody(content) }
            client.get(path)
            val snapshot = KarandashJson.Default.decodeFromString(MetricsSnapshot.serializer(), client.get("/metrics").bodyAsText())
            val size = content.size.toLong()
            assertEquals(MetricsSnapshot(1, 1, 1, size, size, size, 0, 0), snapshot)
        }

    @Test
    fun `config toString never prints tokens`() {
        val text = CacheServerConfig(tokens = listOf("s3cr3t")).toString()
        assertEquals(false, "s3cr3t" in text)
    }
}
