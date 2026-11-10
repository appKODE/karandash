package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.manifest.ConfigSource
import io.karandash.models.support.KarandashJson
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

/** resolving and merging a chain parses each layer's content to a [JsonElement] once. */
class ConfigChainParseOnceTest {
    private val parsed = mutableListOf<String>()

    private val countingJson = object : ConfigFormatModule {
        override val formatId = "json"

        override suspend fun parseToJsonElement(content: String): JsonElement {
            parsed += content
            return KarandashJson.Default.parseToJsonElement(content)
        }

        override suspend fun parseExtendsOnly(content: String): JsonElement? =
            (KarandashJson.Default.parseToJsonElement(content) as JsonObject)["extends"]
    }

    private fun fetcher(responses: Map<String, String>) =
        RemoteConfigFetcher(
            HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        responses[request.url.toString()]?.let { respond(it) } ?: respondError(HttpStatusCode.NotFound)
                    }
                }
            },
        )

    @Test
    fun `each chain layer is parsed to a JsonElement once`() =
        runTest {
            val child = """{"extends": "https://example.com/parent.json", "title": "child"}"""
            val parent = """{"title": "parent", "draft": "parent"}"""
            val registry = ConfigFormatRegistry(listOf(countingJson))
            val responses = mapOf(
                "https://example.com/child.json" to child,
                "https://example.com/parent.json" to parent,
            )

            val chain = ConfigChainResolver(registry, fetcher(responses)).buildChain(
                ConfigSource("https://example.com/child.json"),
            )
            val merged = ConfigChainParser(registry).parseAndMerge(chain) as JsonObject

            assertEquals(JsonPrimitive("child"), merged["title"])
            assertEquals(JsonPrimitive("parent"), merged["draft"])
            assertEquals(listOf(parent, child), parsed.sortedBy { it != parent })
        }

    private suspend fun mergeChild(
        strategy: String,
        parent: String,
        childBody: String,
    ): JsonObject {
        val child = """{"extends": [{"url": "https://example.com/parent.json", "merge-strategy": $strategy}], $childBody}"""
        val registry = ConfigFormatRegistry(listOf(countingJson))
        val responses = mapOf("https://example.com/child.json" to child, "https://example.com/parent.json" to parent)
        val chain = ConfigChainResolver(registry, fetcher(responses)).buildChain(ConfigSource("https://example.com/child.json"))
        return ConfigChainParser(registry).parseAndMerge(chain) as JsonObject
    }

    @Test
    fun `an extends entry path merge-strategy applies to the declaring child`() =
        runTest {
            val merged = mergeChild("""{"labels": "append"}""", """{"labels": ["a"]}""", """"labels": ["b"]""")
            assertEquals("[\"a\",\"b\"]", merged["labels"].toString())
        }

    @Test
    fun `a string merge-strategy replace drops the base and shallow replaces whole sections`() =
        runTest {
            val parent = """{"draft": {"enabled": true}, "title": {"maxLength": 50, "minLength": 5}}"""
            val replaced = mergeChild("\"replace\"", parent, """"title": {"maxLength": 72}""")
            assertEquals(null, replaced["draft"])
            val shallow = mergeChild("\"shallow\"", parent, """"title": {"maxLength": 72}""")
            assertEquals("{\"enabled\":true}", shallow["draft"].toString())
            assertEquals("{\"maxLength\":72}", shallow["title"].toString())
            val deep = mergeChild("\"deep\"", parent, """"title": {"maxLength": 72}""")
            assertEquals("{\"maxLength\":72,\"minLength\":5}", deep["title"].toString())
        }

    @Test
    fun `an array extends merges left to right so the later entry wins a key both set`() =
        runTest {
            val registry = ConfigFormatRegistry(listOf(countingJson))

            fun merged(order: String) =
                mapOf(
                    "https://example.com/child.json" to """{"extends": [$order]}""",
                    "https://example.com/a.json" to """{"title": "a", "onlyA": true}""",
                    "https://example.com/b.json" to """{"title": "b", "onlyB": true}""",
                )
            val aThenB = "\"https://example.com/a.json\", \"https://example.com/b.json\""
            val bThenA = "\"https://example.com/b.json\", \"https://example.com/a.json\""
            for ((order, winner) in listOf(aThenB to "b", bThenA to "a")) {
                val chain = ConfigChainResolver(registry, fetcher(merged(order))).buildChain(
                    ConfigSource("https://example.com/child.json"),
                )
                val result = ConfigChainParser(registry).parseAndMerge(chain) as JsonObject
                assertEquals(JsonPrimitive(winner), result["title"], "extends [$order]")
                assertEquals(JsonPrimitive(true), result["onlyA"])
                assertEquals(JsonPrimitive(true), result["onlyB"])
            }
        }
}
