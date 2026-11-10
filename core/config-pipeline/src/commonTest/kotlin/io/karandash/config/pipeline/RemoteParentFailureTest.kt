package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.manifest.ConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** A remote `extends:` parent that is missing, slow, or not YAML fails the chain with a clear config error. */
class RemoteParentFailureTest {
    private val root = "https://example.com/root.yaml"
    private val parent = "https://example.com/parent.yaml"

    /** Mirrors the YAML module: `extends: <url>` maps, anything starting with `<` is rejected as non-YAML. */
    private val yaml = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement {
            require(!content.startsWith("<")) { "Invalid YAML structure: expected map at root level" }
            val target = content.substringAfter("extends:", "").trim()
            return JsonObject(if (target.isEmpty()) emptyMap() else mapOf("extends" to JsonPrimitive(target)))
        }
    }

    private suspend fun buildChain(
        fetchTimeoutMs: Long = 5_000,
        parentResponse: suspend () -> Pair<HttpStatusCode, String>,
    ) {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val (status, body) = if (request.url.toString() == root) HttpStatusCode.OK to "extends: $parent" else parentResponse()
                    respond(ByteReadChannel(body), status)
                }
            }
        }
        val fetcher = RemoteConfigFetcher(client, fetchTimeoutMs = fetchTimeoutMs)
        ConfigChainResolver(ConfigFormatRegistry(listOf(yaml)), fetcher).buildChain(ConfigSource(url = root))
    }

    @Test
    fun `parent returning 404 fails with the HTTP status and the parent URL`() =
        runTest {
            val error = assertFailsWith<ConfigException> { buildChain { HttpStatusCode.NotFound to "Not Found" } }
            val message = error.message.orEmpty()
            assertTrue("HTTP 404" in message && parent in message, message)
        }

    @Test
    fun `parent slower than the fetch timeout fails instead of hanging`() =
        runTest {
            val error = assertFailsWith<ConfigException> {
                buildChain(fetchTimeoutMs = 100) {
                    delay(60_000)
                    HttpStatusCode.OK to "# late"
                }
            }
            assertTrue(parent in error.message.orEmpty(), error.message)
        }

    @Test
    fun `parent serving HTML instead of YAML fails naming the parent`() =
        runTest {
            val error = assertFailsWith<ConfigException> { buildChain { HttpStatusCode.OK to "<!DOCTYPE html><html>Sign in</html>" } }
            val message = error.message.orEmpty()
            assertTrue("Failed to parse config $parent" in message, message)
        }
}
