package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.config.merge.MergeMode
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.http.HttpAuth
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.RemoteFileResolution
import io.karandash.sdk.config.anyToJsonElement
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Unit tests for [ConfigChainParser].
 *
 * Uses an in-memory stub [ConfigFormatModule] so no filesystem or network I/O occurs.
 */
class ConfigChainParserTest {
    private fun registryWith(
        formatId: String,
        parseMap: (String) -> Map<String, Any?>
    ): ConfigFormatRegistry {
        val module = object : ConfigFormatModule {
            override val formatId = formatId

            override suspend fun parseToJsonElement(content: String): JsonElement = anyToJsonElement(parseMap(content))
        }
        return ConfigFormatRegistry(listOf(module))
    }

    private fun stubSource(url: String = "file://config.yaml") = ConfigSource(url)

    private fun chainEntry(
        content: String,
        format: String = "yaml",
        url: String = "file://config.yaml",
        depth: Int = 0,
        mergeStrategy: MergeStrategyMap? = null,
    ) = ChainEntry(
        source = stubSource(url),
        depth = depth,
        mergeStrategy = mergeStrategy,
        content = content,
        format = format,
    )

    // --- Test 1: Single entry returns its parsed content ---

    @Test
    fun `single entry returns parsed content as-is`() =
        runTest {
            val registry = registryWith("yaml") { mapOf("key" to "value") }
            val parser = ConfigChainParser(registry)
            val chain = ConfigChain(
                listOf(chainEntry("key: value"))
            )
            val result = parser.parseAndMerge(chain) as JsonObject
            assertEquals("value", result["key"]?.jsonPrimitive?.content)
        }

    @Test
    fun `parent kebab key and child camelCase key merge as one key and the child wins`() =
        runTest {
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("dry-run" to true, "rules" to mapOf("title-format" to mapOf("min-length" to 5)))
                    "child" -> mapOf("dryRun" to false, "rules" to mapOf("titleFormat" to mapOf("minLength" to 9)))
                    else -> emptyMap()
                }
            }
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val result = ConfigChainParser(registry).parseAndMerge(chain) as JsonObject
            assertEquals(setOf("dryRun", "rules"), result.keys)
            assertEquals("false", result["dryRun"]?.jsonPrimitive?.content)
            val titleFormat = (result["rules"] as JsonObject)["titleFormat"] as JsonObject
            assertEquals(setOf("minLength"), titleFormat.keys)
            assertEquals("9", titleFormat["minLength"]?.jsonPrimitive?.content)
        }

    @Test
    fun `a child baseline or dangerfile set to null clears the parent value`() =
        runTest {
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("baseline" to "base.json", "dangerfile" to "Dangerfile.kts")
                    "child" -> mapOf("baseline" to null, "dangerfile" to null)
                    else -> emptyMap()
                }
            }
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val merged = ConfigChainParser(registry).parseAndMerge(chain)
            val config = ConfigMapParser.parseFullConfig(merged).config
            assertEquals(null, config.baseline)
            assertEquals(null, config.dangerfile)
        }

    @Test
    fun `ai review instructions of the parent are inherited by a child that sets other ai keys`() =
        runTest {
            val instruction = mapOf("name" to "kotlin", "paths" to listOf("src/**/*.kt"), "text" to "prefer val")
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("ai" to mapOf("review" to mapOf("instructions" to listOf(instruction))))
                    "child" -> mapOf("ai" to mapOf("model" to "m"))
                    else -> emptyMap()
                }
            }
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val ai = (ConfigChainParser(registry).parseAndMerge(chain) as JsonObject)["ai"] as JsonObject
            val instructions = (ai["review"] as JsonObject)["instructions"] as JsonArray
            assertEquals("m", ai["model"]?.jsonPrimitive?.content)
            assertEquals(listOf("kotlin"), instructions.map { (it as JsonObject)["name"]?.jsonPrimitive?.content })
        }

    @Test
    fun `ai review duo-instructions-file of the parent is inherited under its camelCase key`() =
        runTest {
            val instruction = mapOf("name" to "kotlin", "paths" to listOf("src/**/*.kt"), "text" to "prefer val")
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("ai" to mapOf("review" to mapOf("duo-instructions-file" to true)))
                    "child" -> mapOf("ai" to mapOf("review" to mapOf("instructions" to listOf(instruction))))
                    else -> emptyMap()
                }
            }
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val ai = (ConfigChainParser(registry).parseAndMerge(chain) as JsonObject)["ai"] as JsonObject
            assertEquals("true", (ai["review"] as JsonObject)["duoInstructionsFile"]?.jsonPrimitive?.content)
        }

    @Test
    fun `ai learnings keys of the parent are inherited by a child that overrides one of them`() =
        runTest {
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("ai" to mapOf("learnings" to mapOf("file" to "team/learnings.yaml", "min-rejections" to 3)))
                    "child" -> mapOf("ai" to mapOf("learnings" to mapOf("enabled" to false)))
                    else -> emptyMap()
                }
            }
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val ai = (ConfigChainParser(registry).parseAndMerge(chain) as JsonObject)["ai"] as JsonObject
            val learnings = ai["learnings"] as JsonObject
            assertEquals("team/learnings.yaml", learnings["file"]?.jsonPrimitive?.content)
            assertEquals("3", learnings["minRejections"]?.jsonPrimitive?.content)
            assertEquals("false", learnings["enabled"]?.jsonPrimitive?.content)
        }

    @Test
    fun `kebab merge-strategy path applies to the normalized key`() =
        runTest {
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("rules" to mapOf("title-format" to mapOf("prefixes" to listOf("a"))))
                    "child" -> mapOf("rules" to mapOf("title-format" to mapOf("prefixes" to listOf("b"))))
                    else -> emptyMap()
                }
            }
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry(
                        "child",
                        url = "file://child.yaml",
                        depth = 1,
                        mergeStrategy = mapOf("rules.title-format.prefixes" to MergeMode.APPEND),
                    ),
                )
            )
            val result = ConfigChainParser(registry).parseAndMerge(chain) as JsonObject
            val prefixes = ((result["rules"] as JsonObject)["titleFormat"] as JsonObject)["prefixes"] as JsonArray
            assertEquals(listOf("a", "b"), prefixes.map { it.jsonPrimitive.content })
        }

    // --- Test 2: Child overrides parent scalar ---

    @Test
    fun `child entry overrides parent scalar value`() =
        runTest {
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("key" to "parent-value", "other" to "parent-other")
                    "child" -> mapOf("key" to "child-value")
                    else -> emptyMap()
                }
            }
            val parser = ConfigChainParser(registry)
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val result = parser.parseAndMerge(chain) as JsonObject
            assertEquals("child-value", result["key"]?.jsonPrimitive?.content)
        }

    // --- Test 3: Child inherits parent keys not in child ---

    @Test
    fun `child inherits parent keys not present in child`() =
        runTest {
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("fromParent" to "parent-value", "shared" to "parent-shared")
                    "child" -> mapOf("fromChild" to "child-value")
                    else -> emptyMap()
                }
            }
            val parser = ConfigChainParser(registry)
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val result = parser.parseAndMerge(chain) as JsonObject
            assertEquals("parent-value", result["fromParent"]?.jsonPrimitive?.content)
            assertEquals("child-value", result["fromChild"]?.jsonPrimitive?.content)
            assertEquals("parent-shared", result["shared"]?.jsonPrimitive?.content)
        }

    // --- Test 4: List merging uses REPLACE by default ---

    @Test
    fun `list merging uses REPLACE strategy by default`() =
        runTest {
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "parent" -> mapOf("items" to listOf("a", "b"))
                    "child" -> mapOf("items" to listOf("c"))
                    else -> emptyMap()
                }
            }
            val parser = ConfigChainParser(registry)
            val chain = ConfigChain(
                listOf(
                    chainEntry("parent", url = "file://parent.yaml", depth = 0),
                    chainEntry("child", url = "file://child.yaml", depth = 1),
                )
            )
            val result = parser.parseAndMerge(chain) as JsonObject
            val items = (result["items"] as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(listOf("c"), items)
        }

    // --- Test 5: Empty ConfigChain entries prevented by require ---

    @Test
    fun `ConfigChain with empty entries list throws IllegalArgumentException`() {
        assertFailsWith<IllegalArgumentException> {
            ConfigChain(emptyList())
        }
    }

    // --- Test 6: Remote entry resolves file references via fetcher ---

    @Test
    fun `remote entry resolves file references via fetcher`() =
        runTest {
            val remoteUrl = "https://gitlab.example.com/devops/shared/-/raw/main/.karandash/config.yaml"
            val expectedFileUrl = "https://gitlab.example.com/devops/shared/-/raw/main/.karandash/patterns/title.txt"
            val registry = registryWith("yaml") {
                mapOf(
                    "rules" to mapOf(
                        "title-format" to mapOf(
                            "pattern" to mapOf("file" to "./patterns/title.txt")
                        )
                    )
                )
            }
            val fetcher = stubFetcher(mapOf(expectedFileUrl to "^(feat|fix):"))
            val parser = ConfigChainParser(registry, fetcher = fetcher)
            val chain = ConfigChain(
                listOf(
                    ChainEntry(
                        source = stubSource(remoteUrl),
                        depth = 0,
                        mergeStrategy = null,
                        content = "content",
                        format = "yaml",
                        fileResolution = RemoteFileResolution.FALLBACK_LOCAL,
                    )
                )
            )
            val result = parser.parseAndMerge(chain) as JsonObject
            val rules = result["rules"] as JsonObject
            val titleFormat = rules["titleFormat"] as JsonObject
            assertEquals("^(feat|fix):", titleFormat["pattern"]?.jsonPrimitive?.content)
        }

    // --- Test 7: Mixed chain with local root and remote parent ---

    @Test
    fun `mixed chain resolves local and remote file refs independently`() =
        runTest {
            val remoteUrl = "https://example.com/shared/config.yaml"
            val expectedFileUrl = "https://example.com/shared/patterns/remote.txt"
            val registry = registryWith("yaml") { content ->
                when (content) {
                    "remote" -> mapOf(
                        "rules" to mapOf(
                            "title-format" to mapOf(
                                "pattern" to mapOf("file" to "./patterns/remote.txt")
                            )
                        )
                    )

                    "local" -> mapOf(
                        "rules" to mapOf(
                            "branch-naming" to mapOf("enabled" to true)
                        )
                    )

                    else -> emptyMap()
                }
            }
            val fetcher = stubFetcher(mapOf(expectedFileUrl to "remote-pattern"))
            val parser = ConfigChainParser(registry, fetcher = fetcher)
            val chain = ConfigChain(
                listOf(
                    ChainEntry(
                        source = ConfigSource(url = remoteUrl, auth = HttpAuth.Bearer("tok")),
                        depth = 0,
                        mergeStrategy = null,
                        content = "remote",
                        format = "yaml",
                        fileResolution = RemoteFileResolution.FALLBACK_LOCAL,
                    ),
                    chainEntry("local", url = "file://local.yaml", depth = 1),
                )
            )
            val result = parser.parseAndMerge(chain) as JsonObject
            val rules = result["rules"] as JsonObject
            val titleFormat = rules["titleFormat"] as JsonObject
            assertEquals("remote-pattern", titleFormat["pattern"]?.jsonPrimitive?.content)
        }

    // --- Test 8: Remote entry without fetcher fails on a file ref it cannot resolve ---

    @Test
    fun `remote entry with a file reference and no fetcher fails`() =
        runTest {
            val remoteUrl = "https://example.com/config.yaml"
            val registry = registryWith("yaml") {
                mapOf(
                    "value" to mapOf("file" to "./data.txt")
                )
            }
            val parser = ConfigChainParser(registry, fetcher = null)
            val chain = ConfigChain(
                listOf(chainEntry("content", url = remoteUrl, depth = 0))
            )
            val error = assertFailsWith<ConfigException> { parser.parseAndMerge(chain) }
            assertTrue(remoteUrl in error.message.orEmpty(), error.message)
        }

    @Test
    fun `remote entry without file references and no fetcher still parses`() =
        runTest {
            val registry = registryWith("yaml") {
                mapOf("value" to "plain", "auth" to mapOf("gitlab" to mapOf("token" to mapOf("file" to "./token"))))
            }
            val chain = ConfigChain(listOf(chainEntry("content", url = "https://example.com/config.yaml", depth = 0)))

            val result = ConfigChainParser(registry, fetcher = null).parseAndMerge(chain) as JsonObject

            assertEquals("plain", result["value"]?.jsonPrimitive?.content)
        }

    private fun stubFetcher(responses: Map<String, String>): RemoteConfigFetcher {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val body = responses[url]
                    if (body != null) {
                        respond(
                            content = ByteReadChannel(body),
                            status = HttpStatusCode.OK,
                        )
                    } else {
                        respond(
                            content = ByteReadChannel("Not Found"),
                            status = HttpStatusCode.NotFound,
                        )
                    }
                }
            }
        }
        return RemoteConfigFetcher(client)
    }
}
