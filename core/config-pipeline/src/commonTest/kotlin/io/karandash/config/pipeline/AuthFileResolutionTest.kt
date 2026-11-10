package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.manifest.ConfigSource
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `auth.<name>.file-resolution` is the default `file-resolution` of every extends entry that uses that
 * named auth (`auth: <name>`) and sets none of its own. Driven through the real chain build and merge.
 */
class AuthFileResolutionTest {
    private val rootUrl = "https://example.com/root.yaml"
    private val baseUrl = "https://example.com/shared/base.yaml"
    private val patternUrl = "https://example.com/shared/p.txt"

    private val json = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement = KarandashJson.Default.parseToJsonElement(content)
    }

    private fun root(
        authFileResolution: String,
        entryExtra: String = "",
    ) = """
        {"auth": {"corp": {"type": "bearer", "token": "t", "file-resolution": "$authFileResolution"}},
         "extends": [{"url": "$baseUrl", "auth": "corp"$entryExtra}]}
        """.trimIndent()

    private suspend fun load(rootContent: String): JsonObject {
        val responses = mapOf(
            rootUrl to rootContent,
            baseUrl to """{"rules": {"x": {"pattern": {"file": "./p.txt"}}}}""",
            patternUrl to "remote-pattern",
        )
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    responses[request.url.toString()]?.let { respond(it) }
                        ?: respond("Not Found", HttpStatusCode.NotFound)
                }
            }
        }
        val registry = ConfigFormatRegistry(listOf(json))
        val fetcher = RemoteConfigFetcher(client)
        val chain = ConfigChainResolver(registry, fetcher).buildChain(ConfigSource(url = rootUrl))
        return ConfigChainParser(registry, fetcher = fetcher).parseAndMerge(chain).jsonObject
    }

    @Test
    fun `auth file-resolution strict is the default of an entry using that auth`() =
        runTest {
            val rules = checkNotNull(load(root("strict"))["rules"]).jsonObject
            assertEquals("remote-pattern", checkNotNull(checkNotNull(rules["x"]).jsonObject["pattern"]).jsonPrimitive.content)
        }

    @Test
    fun `the entry own file-resolution wins over the auth default`() =
        runTest {
            val error = assertFailsWith<ConfigException> { load(root("strict", """, "file-resolution": "local"""")) }
            assertTrue("p.txt" in error.message.orEmpty(), error.message)
        }

    @Test
    fun `extends entry fileResolution strict reads the remote file and local fails on the same chain`() =
        runTest {
            fun root(mode: String) = """{"extends": [{"url": "$baseUrl", "fileResolution": "$mode"}]}"""

            val rules = checkNotNull(load(root("strict"))["rules"]).jsonObject
            assertEquals("remote-pattern", checkNotNull(checkNotNull(rules["x"]).jsonObject["pattern"]).jsonPrimitive.content)

            val error = assertFailsWith<ConfigException> { load(root("local")) }
            assertTrue("p.txt" in error.message.orEmpty(), error.message)
        }

    @Test
    fun `an invalid auth file-resolution fails the load`() =
        runTest {
            val error = assertFailsWith<ConfigException> { load(root("bogus")) }
            assertTrue("bogus" in error.message.orEmpty(), error.message)
        }

    @Test
    fun `the auth block parser rejects an invalid file-resolution`() {
        val element = KarandashJson.Default.parseToJsonElement(root("bogus"))

        val error = assertFailsWith<ConfigParseException> { AuthBlockParser.parse(element) }

        assertTrue("auth.corp.file-resolution" in error.message.orEmpty() && "secret" !in error.message.orEmpty(), error.message)
    }

    @Test
    fun `an entry naming an unknown auth fails the load`() =
        runTest {
            val error = assertFailsWith<ConfigException> { load(root("strict").replace("\"auth\": \"corp\"", "\"auth\": \"nope\"")) }
            assertTrue("nope" in error.message.orEmpty(), error.message)
        }
}
