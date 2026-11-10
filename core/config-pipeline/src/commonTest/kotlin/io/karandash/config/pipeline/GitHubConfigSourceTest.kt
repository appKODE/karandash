package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.manifest.ConfigSource
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** `extends:` sources hosted on GitHub: the contents API (raw media type) and raw.githubusercontent.com. */
class GitHubConfigSourceTest {
    /** Parses only a single-line `extends: <url>`. */
    private val stubYaml = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement {
            val value = content
                .lines()
                .firstOrNull { it.startsWith("extends:") }
                ?.substringAfter("extends:")
                ?.trim()
            return JsonObject(if (value == null) emptyMap() else mapOf("extends" to JsonPrimitive(value)))
        }
    }

    /** Serves [responses] by URL and records the Accept header of each request. */
    private fun fetcher(
        responses: Map<String, String>,
        accepts: MutableMap<String, String?>,
    ): RemoteConfigFetcher {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    accepts[url] = request.headers[HttpHeaders.Accept]
                    val body = responses[url]
                    if (body != null) {
                        respond(ByteReadChannel(body), HttpStatusCode.OK)
                    } else {
                        respond(ByteReadChannel("Not Found"), HttpStatusCode.NotFound)
                    }
                }
            }
        }
        return RemoteConfigFetcher(client)
    }

    @Test
    fun `contents API URL is fetched with the GitHub raw media type`() =
        runTest {
            val url = "https://api.github.com/repos/acme/configs/contents/base/.karandash/config.yaml?ref=v1"
            val accepts = mutableMapOf<String, String?>()
            val content = fetcher(mapOf(url to "# base"), accepts).fetch(ConfigSource(url))
            assertEquals("# base", content)
            assertEquals("application/vnd.github.raw", accepts[url])
        }

    @Test
    fun `raw githubusercontent URL is fetched without a GitHub media type`() =
        runTest {
            val url = "https://raw.githubusercontent.com/acme/configs/main/base.yaml"
            val accepts = mutableMapOf<String, String?>()
            fetcher(mapOf(url to "# base"), accepts).fetch(ConfigSource(url))
            assertNull(accepts[url]?.takeIf { "vnd.github" in it })
        }

    @Test
    fun `relative extends in a contents API parent resolves to a contents API URL on the same ref`() =
        runTest {
            val child = "https://api.github.com/repos/acme/configs/contents/team/.karandash/config.yaml?ref=v1"
            val parent = "https://api.github.com/repos/acme/configs/contents/base/.karandash/config.yaml?ref=v1"
            val accepts = mutableMapOf<String, String?>()
            val resolver = ConfigChainResolver(
                formatRegistry = ConfigFormatRegistry(listOf(stubYaml)),
                fetcher = fetcher(mapOf(child to "extends: ../../base/.karandash/config.yaml", parent to "# base"), accepts),
            )
            val chain = resolver.buildChain(ConfigSource(child))
            assertEquals(listOf(parent, child), chain.entries.map { it.source.url })
            assertEquals("application/vnd.github.raw", accepts[parent])
        }

    @Test
    fun `relative extends in a raw githubusercontent parent resolves next to it`() =
        runTest {
            val child = "https://raw.githubusercontent.com/acme/configs/v1/team/config.yaml"
            val parent = "https://raw.githubusercontent.com/acme/configs/v1/team/base.yaml"
            val resolver = ConfigChainResolver(
                formatRegistry = ConfigFormatRegistry(listOf(stubYaml)),
                fetcher = fetcher(mapOf(child to "extends: base.yaml", parent to "# base"), mutableMapOf()),
            )
            val chain = resolver.buildChain(ConfigSource(child))
            assertEquals(listOf(parent, child), chain.entries.map { it.source.url })
        }
}
