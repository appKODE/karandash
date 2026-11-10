package io.karandash.cli.commands.cache

import com.github.ajalt.clikt.testing.test
import com.sun.net.httpserver.HttpServer
import io.karandash.cache.cas.CasBlobStore
import io.karandash.cli.errors.ExitCodes
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.Collections
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * `cache push --allow-localhost` reaches a loopback HTTP backend; without the flag SSRF protection blocks it.
 * A blob the backend refuses (non-2xx) counts as failed, not pushed. `cache.remote` from `--config` is used as is.
 */
class CachePushAllowLocalhostTest {
    private val dir = java.nio.file.Paths
        .get(TestFiles.createTestTempDirectory("karandash-cache-push").toString())

    private fun withBackend(
        putStatus: Int = HTTP_CREATED,
        block: (url: String, puts: List<String>) -> Unit,
    ) {
        val puts = Collections.synchronizedList(mutableListOf<String>())
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        server.createContext("/cachebk") { exchange ->
            if (exchange.requestMethod == "PUT") {
                puts += exchange.requestURI.path + " auth=" + exchange.requestHeaders.getFirst("Authorization")
            }
            exchange.requestBody.readAllBytes()
            val status = if (exchange.requestMethod == "PUT") putStatus else HTTP_NOT_FOUND
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        server.start()
        try {
            block("http://localhost:${server.address.port}/cachebk", puts)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `loopback backend is pushed to with allow-localhost and blocked without`() {
        val digest = CasBlobStore(Path(dir.toString(), "cas")).put("blob".encodeToByteArray())
        withBackend { url, puts ->
            val blocked = cachePushCommand().test(listOf("--cache-dir", dir.toString(), "--backend-url", url))
            assertEquals(ExitCodes.EXECUTION_ERROR, blocked.statusCode, blocked.output)
            assertTrue(puts.isEmpty(), "PUT sent without --allow-localhost: $puts")

            val allowed = cachePushCommand().test(listOf("--cache-dir", dir.toString(), "--backend-url", url, "--allow-localhost"))
            assertEquals(ExitCodes.SUCCESS, allowed.statusCode, allowed.output)
            assertTrue("1 pushed, 0 skipped, 0 failed" in allowed.output, allowed.output)
            val hex = digest.hex
            assertEquals(listOf("/cachebk/cas/${hex.take(2)}/${hex.drop(2)} auth=null"), puts.toList())
        }
    }

    @Test
    fun `cache remote from the config supplies url - auth and allow-localhost`() {
        val digest = CasBlobStore(Path(dir.toString(), "cas")).put("configured".encodeToByteArray())
        withBackend { url, puts ->
            val config = dir.resolve("karandash.yaml").toFile()
            config.writeText("allow-localhost: true\ncache:\n  remote:\n    backend: http\n    url: \"$url\"\n    auth: remote-token\n")
            val result = cachePushCommand().test(listOf("--cache-dir", dir.toString(), "--config", config.path))
            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            val hex = digest.hex
            assertEquals(listOf("/cachebk/cas/${hex.take(2)}/${hex.drop(2)} auth=Bearer remote-token"), puts.toList())
            assertTrue("remote-token" !in result.output, "auth leaked: ${result.output}")
        }
    }

    @Test
    fun `a blob the backend refuses counts as failed and exits non-zero`() {
        CasBlobStore(Path(dir.toString(), "cas")).put("refused".encodeToByteArray())
        withBackend(putStatus = HTTP_BAD_REQUEST) { url, puts ->
            val result = cachePushCommand().test(listOf("--cache-dir", dir.toString(), "--backend-url", url, "--allow-localhost"))
            assertEquals(ExitCodes.EXECUTION_ERROR, result.statusCode, result.output)
            assertTrue("0 pushed, 0 skipped, 1 failed" in result.output, result.output)
            assertEquals(1, puts.size, "the PUT is sent and refused: $puts")
        }
    }

    private companion object {
        const val HTTP_CREATED = 201
        const val HTTP_BAD_REQUEST = 400
        const val HTTP_NOT_FOUND = 404
    }
}
