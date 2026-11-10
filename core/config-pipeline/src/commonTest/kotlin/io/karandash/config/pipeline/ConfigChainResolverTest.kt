package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigChainException
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.module.ConfigFormatModule
import io.karandash.cache.cas.CasCache
import io.karandash.config.ExtendsLimitsConfig
import io.karandash.config.KarandashLimits
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpAuth
import io.karandash.http.NetworkPolicy
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.ConfigSource
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.anyToJsonElement
import io.karandash.testfixtures.TestFiles
import io.karandash.utils.GlobExpander
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeoutCapability
import io.ktor.client.plugins.api.Send
import io.ktor.client.plugins.api.createClientPlugin
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Unit tests for [ConfigChainResolver].
 *
 * Uses Ktor [MockEngine] to serve in-memory YAML content, avoiding real I/O.
 * Format parsing is stubbed: the registered YAML module simply parses the raw
 * `extends:` line directly without a full YAML library.
 */
class ConfigChainResolverTest {
    /**
     * A minimal [ConfigFormatModule] that supports `extends: <url>` single-line
     * and `extends: [url1, url2, ...]` list-line detection for tests.
     */
    private val stubYamlModule = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement {
            val line = content.lines().firstOrNull { it.trimStart().startsWith("extends:") }
                ?: return anyToJsonElement(emptyMap<String, Any?>())
            val value = line.substringAfter("extends:").trim()
            val map: Map<String, Any?> = when {
                value.startsWith("[") -> {
                    val urls = value
                        .removeSurrounding("[", "]")
                        .split(",")
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                    mapOf("extends" to urls)
                }

                value.isNotEmpty() -> {
                    mapOf("extends" to value)
                }

                else -> {
                    emptyMap()
                }
            }
            return anyToJsonElement(map)
        }
    }

    private fun registryWithStubYaml() = ConfigFormatRegistry(listOf(stubYamlModule))

    /** Creates a [RemoteConfigFetcher] backed by [MockEngine] serving [responses]. */
    private fun mockFetcher(responses: Map<String, String>): RemoteConfigFetcher {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    val body = responses[url]
                    if (body != null) {
                        respond(
                            content = ByteReadChannel(body),
                            status = HttpStatusCode.OK,
                            headers = headersOf(HttpHeaders.ContentType, "text/yaml"),
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

    // --- Test 1: Single config, no extends ---

    @Test
    fun `single config with no extends produces chain of size 1`() =
        runTest {
            val responses = mapOf("https://example.com/root.yaml" to "# no extends")
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource("https://example.com/root.yaml"))
            assertEquals(1, chain.size)
            assertEquals("https://example.com/root.yaml", chain.root.source.url)
        }

    // --- Test 2: Single inheritance (parent first, child last) ---

    @Test
    fun `single extends produces chain of size 2 with parent first child last`() =
        runTest {
            val responses = mapOf(
                "https://example.com/child.yaml" to "extends: https://example.com/parent.yaml",
                "https://example.com/parent.yaml" to "# base",
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource("https://example.com/child.yaml"))
            assertEquals(2, chain.size)
            assertEquals("https://example.com/parent.yaml", chain.entries[0].source.url)
            assertEquals("https://example.com/child.yaml", chain.entries[1].source.url)
        }

    // --- Test 3: Diamond inheritance - shared ancestor appears exactly once, no E0010 ---

    @Test
    fun `diamond inheritance where shared ancestor is visited twice resolves without error`() =
        runTest {
            // A extends [B, C]; B extends D; C extends D -> D should appear exactly once in the chain
            val responses = mapOf(
                "https://example.com/a.yaml" to
                    "extends: [https://example.com/b.yaml, https://example.com/c.yaml]",
                "https://example.com/b.yaml" to "extends: https://example.com/d.yaml",
                "https://example.com/c.yaml" to "extends: https://example.com/d.yaml",
                "https://example.com/d.yaml" to "# base",
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource("https://example.com/a.yaml"))
            val urls = chain.entries.map { it.source.url }
            // d.yaml must appear exactly once despite being referenced by both b and c
            assertEquals(1, urls.count { it == "https://example.com/d.yaml" }, "d.yaml should appear exactly once")
            // a.yaml is the root (last entry), d.yaml is deepest ancestor (first entry)
            assertEquals("https://example.com/a.yaml", urls.last())
            assertEquals("https://example.com/d.yaml", urls.first())
        }

    /** A config format nobody registered is E0025, not the PR URL code E0012. */
    @Test
    fun `unsupported config format reports E0025`() =
        runTest {
            val resolver = ConfigChainResolver(
                formatRegistry = ConfigFormatRegistry(),
                fetcher = mockFetcher(mapOf("https://example.com/a.yaml" to "x: 1")),
            )
            val ex = assertFailsWith<ConfigException> {
                resolver.buildChain(ConfigSource("https://example.com/a.yaml"))
            }
            assertTrue(ex.message!!.contains("[E0025]"), ex.message)
            assertFalse(ex.message!!.contains("E0012"), ex.message)
        }

    // --- Test 4: Cycle detection ---

    @Test
    fun `circular inheritance throws ConfigException containing E0010`() =
        runTest {
            val responses = mapOf(
                "https://example.com/a.yaml" to "extends: https://example.com/b.yaml",
                "https://example.com/b.yaml" to "extends: https://example.com/a.yaml",
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val ex = assertFailsWith<ConfigException> {
                resolver.buildChain(ConfigSource("https://example.com/a.yaml"))
            }
            assertTrue(ex.message!!.contains("E0010"), "Expected E0010 in: ${ex.message}")
            assertTrue(
                ex.message!!.contains("Circular") || ex.message!!.contains("circular"),
                "Expected 'Circular' in: ${ex.message}",
            )
        }

    @Test
    fun `diamond whose deeper path reaches the shared ancestor after it is resolved is no cycle`() =
        runTest {
            // root extends [a, b]; a -> c -> d -> x; b -> x: x is resolved at depth 2 before d (depth 3) declares it
            val responses = mapOf(
                "https://example.com/root.yaml" to "extends: [https://example.com/a.yaml, https://example.com/b.yaml]",
                "https://example.com/a.yaml" to "extends: https://example.com/c.yaml",
                "https://example.com/c.yaml" to "extends: https://example.com/d.yaml",
                "https://example.com/d.yaml" to "extends: https://example.com/x.yaml",
                "https://example.com/b.yaml" to "extends: https://example.com/x.yaml",
                "https://example.com/x.yaml" to "# base",
            )
            val resolver = ConfigChainResolver(formatRegistry = registryWithStubYaml(), fetcher = mockFetcher(responses))

            val chain = resolver.buildChain(ConfigSource("https://example.com/root.yaml"))

            assertEquals(
                listOf("d", "c", "x", "a", "b", "root").map { "https://example.com/$it.yaml" },
                chain.entries.map { it.source.url },
            )
            assertEquals(2, chain.entries.single { it.source.url == "https://example.com/x.yaml" }.depth)
        }

    @Test
    fun `cycle between two siblings of a diamond still throws E0010`() =
        runTest {
            val responses = mapOf(
                "https://example.com/root.yaml" to "extends: [https://example.com/a.yaml, https://example.com/b.yaml]",
                "https://example.com/a.yaml" to "extends: https://example.com/b.yaml",
                "https://example.com/b.yaml" to "extends: https://example.com/a.yaml",
            )
            val resolver = ConfigChainResolver(formatRegistry = registryWithStubYaml(), fetcher = mockFetcher(responses))

            val ex = assertFailsWith<ConfigException> { resolver.buildChain(ConfigSource("https://example.com/root.yaml")) }
            assertTrue(ex.message.orEmpty().contains("E0010"), "Expected E0010 in: ${ex.message}")
        }

    @Test
    fun `cycle error names the full path`() =
        runTest {
            val responses = mapOf(
                "https://example.com/a.yaml" to "extends: https://example.com/b.yaml",
                "https://example.com/b.yaml" to "extends: https://example.com/a.yaml",
            )
            val resolver = ConfigChainResolver(formatRegistry = registryWithStubYaml(), fetcher = mockFetcher(responses))

            val ex = assertFailsWith<ConfigException> { resolver.buildChain(ConfigSource("https://example.com/a.yaml")) }
            assertTrue(
                ex.message.orEmpty().contains("https://example.com/b.yaml -> https://example.com/a.yaml -> https://example.com/b.yaml"),
                ex.message,
            )
        }

    @Test
    fun `self-reference throws E0010`() =
        runTest {
            val responses = mapOf("https://example.com/a.yaml" to "extends: https://example.com/a.yaml")
            val resolver = ConfigChainResolver(formatRegistry = registryWithStubYaml(), fetcher = mockFetcher(responses))

            val ex = assertFailsWith<ConfigException> { resolver.buildChain(ConfigSource("https://example.com/a.yaml")) }
            assertTrue(ex.message.orEmpty().contains("E0010"), "Expected E0010 in: ${ex.message}")
        }

    // --- Test 5: Depth limit ---

    @Test
    fun `cycle and depth violations carry their error as a type`() =
        runTest {
            val cycle = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(mapOf("https://example.com/a.yaml" to "extends: https://example.com/a.yaml")),
            )
            val cycleError = assertFailsWith<ConfigChainException> { cycle.buildChain(ConfigSource("https://example.com/a.yaml")) }
            assertEquals(KarandashError.CIRCULAR_CONFIG_INHERITANCE, cycleError.error)

            val depth = ConfigChainResolver.MAX_DEPTH + 2
            val deep = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(
                    (0 until depth).associate { i ->
                        "https://example.com/$i.yaml" to if (i < depth - 1) "extends: https://example.com/${i + 1}.yaml" else "# leaf"
                    },
                ),
            )
            val depthError = assertFailsWith<ConfigChainException> { deep.buildChain(ConfigSource("https://example.com/0.yaml")) }
            assertEquals(KarandashError.CONFIG_INHERITANCE_DEPTH_EXCEEDED, depthError.error)
        }

    @Test
    fun `chain exceeding MAX_DEPTH throws ConfigException containing E0011`() =
        runTest {
            // Chain of MAX_DEPTH+2 nodes: 0 -> 1 -> 2 -> ... -> MAX_DEPTH+1 (leaf)
            val depth = ConfigChainResolver.MAX_DEPTH + 2
            val responses = (0 until depth).associate { i ->
                val url = "https://example.com/$i.yaml"
                val content = if (i < depth - 1) {
                    "extends: https://example.com/${i + 1}.yaml"
                } else {
                    "# leaf"
                }
                url to content
            }
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val ex = assertFailsWith<ConfigException> {
                resolver.buildChain(ConfigSource("https://example.com/0.yaml"))
            }
            assertTrue(ex.message!!.contains("E0011"), "Expected E0011 in: ${ex.message}")
            assertTrue(
                ex.message!!.contains("depth") || ex.message!!.contains("Depth"),
                "Expected 'depth' in: ${ex.message}",
            )
        }

    @Test
    fun `root limits extends maxDepth and maxChainSize bound the chain`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to "extends: https://example.com/1.yaml",
                "https://example.com/1.yaml" to "extends: https://example.com/2.yaml",
                "https://example.com/2.yaml" to "# leaf",
            )
            val root = ConfigSource("https://example.com/0.yaml")
            val default = ConfigChainResolver(registryWithStubYaml(), fetcher = mockFetcher(responses)).buildChain(root)
            assertEquals(3, default.entries.size)

            val shallow = ConfigChainResolver(
                registryWithStubYaml(),
                fetcher = mockFetcher(responses),
                limits = ExtendsLimitsConfig(maxDepth = 1),
            )
            val depthError = assertFailsWith<ConfigException> { shallow.buildChain(root) }
            assertTrue(depthError.message.orEmpty().contains("E0011"), "${depthError.message}")

            val small = ConfigChainResolver(
                registryWithStubYaml(),
                fetcher = mockFetcher(responses),
                limits = ExtendsLimitsConfig(maxChainSize = 2),
            )
            val sizeError = assertFailsWith<ConfigException> { small.buildChain(root) }
            assertTrue(sizeError.message.orEmpty().contains("E0024"), "${sizeError.message}")
        }

    // --- Test 6: Size limit ---

    @Test
    fun `chain exceeding MAX_CHAIN_SIZE throws ConfigException containing E0024`() =
        runTest {
            // Two-level tree: root extends [p1..p10]; p1 extends [c1..c10].
            // Total unique nodes = 1 + 10 + 10 = 21 > MAX_CHAIN_SIZE(20).
            // No single extends array exceeds MAX_EXTENDS_ENTRIES(10), so E0014 is not triggered.
            val parents = (1..10).map { "https://example.com/p$it.yaml" }
            val children = (1..10).map { "https://example.com/c$it.yaml" }
            val responses = buildMap {
                put("https://example.com/root.yaml", "extends: [${parents.joinToString(", ")}]")
                // p1 extends 10 unique children
                put(parents[0], "extends: [${children.joinToString(", ")}]")
                // p2..p10 are leaves
                parents.drop(1).forEach { put(it, "# leaf") }
                // children are all leaves
                children.forEach { put(it, "# leaf") }
            }
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val ex = assertFailsWith<ConfigException> {
                resolver.buildChain(ConfigSource("https://example.com/root.yaml"))
            }
            assertTrue(ex.message!!.contains("E0024"), "Expected E0024 in: ${ex.message}")
            assertTrue(
                ex.message!!.contains("size") || ex.message!!.contains("large"),
                "Expected size-related message in: ${ex.message}",
            )
        }

    // ── Bug 8: URL normalization for cycle detection ─────────────────────

    @Test
    fun `normalizeUrl resolves dot segments`() {
        val resolver = ConfigChainResolver(formatRegistry = registryWithStubYaml())
        assertEquals("configs/config.yaml", resolver.normalizeUrl("configs/./config.yaml"))
        assertEquals("config.yaml", resolver.normalizeUrl("./config.yaml"))
        assertEquals("config.yaml", resolver.normalizeUrl("dir/../config.yaml"))
    }

    @Test
    fun `normalizeUrl resolves HTTP URLs`() {
        val resolver = ConfigChainResolver(formatRegistry = registryWithStubYaml())
        assertEquals(
            "https://example.com/configs/config.yaml",
            resolver.normalizeUrl("https://example.com/configs/./config.yaml")
        )
        assertEquals(
            "https://example.com/config.yaml",
            resolver.normalizeUrl("https://example.com/dir/../config.yaml")
        )
    }

    @Test
    fun `cycle detection catches equivalent paths with dot segments`() =
        runTest {
            val responses = mapOf(
                "https://example.com/dir/config.yaml" to "extends: ../dir/config.yaml"
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val ex = assertFailsWith<ConfigException> {
                resolver.buildChain(ConfigSource("https://example.com/dir/config.yaml"))
            }
            assertTrue(ex.message!!.contains("E0010"), "Expected cycle detection E0010 in: ${ex.message}")
        }

    // ── GitLab API URL resolution ───────────────────────────────────────

    @Test
    fun `GitLab API URL extends chain resolves relative path correctly`() =
        runTest {
            val baseKmpUrl =
                "https://git.example.com/api/v4/projects/100/repository/files/base-kmp%2F.karandash%2Fconfig.yaml/raw?ref=main"
            // "../../base/.karandash/config.yaml" from base-kmp/.karandash/ goes up to repo root
            val baseUrl =
                "https://git.example.com/api/v4/projects/100/repository/files/base%2F.karandash%2Fconfig.yaml/raw?ref=main"

            val responses = mapOf(
                baseKmpUrl to "extends: ../../base/.karandash/config.yaml",
                baseUrl to "# base config",
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource(baseKmpUrl))
            assertEquals(2, chain.size)
            assertEquals(baseUrl, chain.entries[0].source.url)
            assertEquals(baseKmpUrl, chain.entries[1].source.url)
        }

    @Test
    fun `auth inherited from GitLab API parent to relative child`() =
        runTest {
            val parentUrl =
                "https://git.example.com/api/v4/projects/42/repository/files/configs%2Fchild.yaml/raw?ref=main"
            // "../base.yaml" from "configs/" goes to repo root
            val resolvedChildUrl =
                "https://git.example.com/api/v4/projects/42/repository/files/base.yaml/raw?ref=main"
            val auth = HttpAuth.CustomHeader("PRIVATE-TOKEN", "secret123")

            val responses = mapOf(
                parentUrl to "extends: ../base.yaml",
                resolvedChildUrl to "# base",
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource(parentUrl, auth = auth))
            assertEquals(2, chain.size)

            val baseEntry = chain.entries[0]
            assertEquals(resolvedChildUrl, baseEntry.source.url)
            assertNotNull(baseEntry.source.auth, "Auth should be inherited from parent")
        }

    @Test
    fun `non-API HTTP URL falls back to existing naive resolution`() =
        runTest {
            // Naive resolution produces un-normalized URL: baseDir + "/" + relative
            val resolvedParentUrl = "https://example.com/configs/../base/parent.yaml"
            val responses = mapOf(
                "https://example.com/configs/child.yaml" to "extends: ../base/parent.yaml",
                resolvedParentUrl to "# base",
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource("https://example.com/configs/child.yaml"))
            assertEquals(2, chain.size)
            assertEquals(resolvedParentUrl, chain.entries[0].source.url)
        }

    @Test
    fun `normalizeUrl handles GitLab API URLs by logical repo path`() {
        val resolver = ConfigChainResolver(formatRegistry = registryWithStubYaml())
        val url1 = "https://gitlab.com/api/v4/projects/1/repository/files/path%2Fconfig.yaml/raw?ref=main"
        val url2 = "https://gitlab.com/api/v4/projects/1/repository/files/path%2Fconfig.yaml/raw?ref=develop"
        // Same file, different ref - should normalize to same logical path
        assertEquals(resolver.normalizeUrl(url1), resolver.normalizeUrl(url2))
    }

    @Test
    fun `auth not inherited across different origins`() =
        runTest {
            // Parent on git.example.com, child resolves to absolute URL on other.com
            val parentUrl = "https://git.example.com/api/v4/projects/1/repository/files/config.yaml/raw?ref=main"
            val childUrl = "https://other.com/configs/base.yaml"
            val auth = HttpAuth.Bearer("secret")

            val responses = mapOf(
                parentUrl to "extends: $childUrl",
                childUrl to "# base",
            )
            val resolver = ConfigChainResolver(
                formatRegistry = registryWithStubYaml(),
                fetcher = mockFetcher(responses),
            )
            val chain = resolver.buildChain(ConfigSource(parentUrl, auth = auth))
            assertEquals(2, chain.size)

            val baseEntry = chain.entries[0]
            // Absolute URL - should NOT inherit auth (different origin)
            assertEquals(childUrl, baseEntry.source.url)
        }

    // --- Glob extends inside a remote parent ---

    private fun globResolver(responses: Map<String, String>) =
        ConfigChainResolver(
            formatRegistry = registryWithStubYaml(),
            fetcher = mockFetcher(responses),
            entryResolver = ExtendsEntryResolver(globExpander = GlobExpander()),
        )

    @Test
    fun `brace glob in a remote parent resolves against the parent URL`() =
        runTest {
            val responses = mapOf(
                "https://example.com/team/base.yaml" to "extends: shared/{a,b}.yaml",
                "https://example.com/team/shared/a.yaml" to "# a",
                "https://example.com/team/shared/b.yaml" to "# b",
            )
            val chain = globResolver(responses).buildChain(ConfigSource("https://example.com/team/base.yaml"))

            assertEquals(
                setOf("https://example.com/team/shared/a.yaml", "https://example.com/team/shared/b.yaml"),
                chain.entries
                    .dropLast(1)
                    .map { it.source.url }
                    .toSet(),
            )
        }

    @Test
    fun `wildcard glob in a remote parent fails instead of listing the local directory`() =
        runTest {
            val responses = mapOf("https://example.com/team/base.yaml" to "extends: shared/*.yaml")
            val error = assertFailsWith<ConfigException> {
                globResolver(responses).buildChain(ConfigSource("https://example.com/team/base.yaml"))
            }
            assertTrue(error.message.orEmpty().contains("remote"), error.message)
        }

    // --- Chain-fetch keys inherit through extends: a layer's settings govern fetching its own parents ---

    /** Parses each config body as JSON, so a layer can carry `limits`, `cache` and the SSRF flags. */
    private val jsonStubModule = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement = KarandashJson.Default.parseToJsonElement(content)
    }

    /** Serves [responses] and records each request's timeout (ms) by URL. */
    private fun recordingFetcher(
        responses: Map<String, String>,
        timeouts: MutableMap<String, Long?> = mutableMapOf(),
        ssrfMode: SsrfMode = SsrfMode.OPERATOR,
    ): RemoteConfigFetcher {
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    timeouts[url] = request.getCapabilityOrNull(HttpTimeoutCapability)?.requestTimeoutMillis
                    val body = responses[url]
                    if (body != null) respond(body) else respond("Not Found", HttpStatusCode.NotFound)
                }
            }
        }
        return RemoteConfigFetcher(client, ssrfMode = ssrfMode)
    }

    private fun jsonResolver(
        fetcher: RemoteConfigFetcher,
        limits: ExtendsLimitsConfig = ExtendsLimitsConfig(),
        cacheLayer: CacheLayer? = null,
    ) = ConfigChainResolver(
        ConfigFormatRegistry(listOf(jsonStubModule)),
        fetcher = fetcher,
        entryResolver = ExtendsEntryResolver(globExpander = GlobExpander()),
        cacheLayer = cacheLayer,
        limits = limits,
    )

    private fun layer(
        extends: String?,
        vararg keys: Pair<String, Any?>,
    ): String = anyToJsonElement(listOfNotNull(extends?.let { "extends" to it }, *keys).toMap()).toString()

    @Test
    fun `a Windows drive path in a local extends is used as is`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("karandash-chain-windows")
            val child = Path(dir, "child.yaml")
            SystemFileSystem.sink(child).buffered().use { it.writeString(layer("C:/x/base.yaml")) }
            try {
                val error = assertFailsWith<ConfigException> {
                    jsonResolver(recordingFetcher(emptyMap())).buildChain(ConfigSource(child.toString()))
                }
                val message = error.message.orEmpty()
                assertTrue("C:/x/base.yaml" in message, message)
                assertFalse("$dir/C:" in message, message)
            } finally {
                TestFiles.deleteRecursively(dir)
            }
        }

    @Test
    fun `a parent's limits extends bounds fetching its own parents and a child override wins`() =
        runTest {
            fun responses(rootKeys: Array<Pair<String, Any?>>) =
                mapOf(
                    "https://example.com/0.yaml" to layer("https://example.com/1.yaml", *rootKeys),
                    "https://example.com/1.yaml" to
                        layer("https://example.com/2.yaml", "limits" to mapOf("extends" to mapOf("max-depth" to 1))),
                    "https://example.com/2.yaml" to layer(null),
                )
            val root = ConfigSource("https://example.com/0.yaml")

            val lowered = assertFailsWith<ConfigException> { jsonResolver(recordingFetcher(responses(emptyArray()))).buildChain(root) }
            assertTrue(lowered.message.orEmpty().contains("E0011"), "${lowered.message}")

            val overridden = arrayOf<Pair<String, Any?>>("limits" to mapOf("extends" to mapOf("maxDepth" to 5)))
            assertEquals(3, jsonResolver(recordingFetcher(responses(overridden))).buildChain(root).size)

            // a parent can also raise the limit the resolver starts from
            val raised = mapOf(
                "https://example.com/0.yaml" to layer("https://example.com/1.yaml"),
                "https://example.com/1.yaml" to
                    layer("https://example.com/2.yaml", "limits" to mapOf("extends" to mapOf("max-depth" to 3))),
                "https://example.com/2.yaml" to layer(null),
            )
            val chain = jsonResolver(recordingFetcher(raised), limits = ExtendsLimitsConfig(maxDepth = 1)).buildChain(root)
            assertEquals(3, chain.size)
            assertEquals(3, ChainFetchKeys.limits(chain.fetchKeys, NoOpLogger).extends.effectiveMaxDepth)
        }

    @Test
    fun `a parent's fetchTimeout applies to fetching its own parents and a child override wins`() =
        runTest {
            fun responses(rootKeys: Array<Pair<String, Any?>>) =
                mapOf(
                    "https://example.com/0.yaml" to layer("https://example.com/1.yaml", *rootKeys),
                    "https://example.com/1.yaml" to layer("https://example.com/2.yaml", "limits" to mapOf("fetch-timeout" to "7s")),
                    "https://example.com/2.yaml" to layer(null),
                )
            val root = ConfigSource("https://example.com/0.yaml")

            val timeouts = mutableMapOf<String, Long?>()
            jsonResolver(recordingFetcher(responses(emptyArray()), timeouts)).buildChain(root)
            assertEquals(KarandashLimits.FETCH_TIMEOUT_MS, timeouts["https://example.com/1.yaml"], "$timeouts")
            assertEquals(7_000L, timeouts["https://example.com/2.yaml"], "$timeouts")

            val overridden = mutableMapOf<String, Long?>()
            val rootKeys = arrayOf<Pair<String, Any?>>("limits" to mapOf("fetchTimeout" to "9s"))
            jsonResolver(recordingFetcher(responses(rootKeys), overridden)).buildChain(root)
            assertEquals(9_000L, overridden["https://example.com/1.yaml"], "$overridden")
            assertEquals(9_000L, overridden["https://example.com/2.yaml"], "$overridden")
        }

    @Test
    fun `a parent's limits glob bounds the globs of its own extends`() =
        runTest {
            val responses = mapOf(
                "https://example.com/team/root.yaml" to layer("https://example.com/team/base.yaml"),
                "https://example.com/team/base.yaml" to
                    layer("shared/{a,b}.yaml", "limits" to mapOf("glob" to mapOf("max-expansion" to 1))),
                "https://example.com/team/shared/a.yaml" to layer(null),
                "https://example.com/team/shared/b.yaml" to layer(null),
            )
            val error = assertFailsWith<ConfigException> {
                jsonResolver(recordingFetcher(responses)).buildChain(ConfigSource("https://example.com/team/root.yaml"))
            }
            assertTrue(error.message.orEmpty().contains(KarandashError.GLOB_EXPANSION_FAILED.bracketCode), error.message)
        }

    @Test
    fun `a parent's allowPrivateNetwork lets its own parent come from a private host and a child override wins`() =
        runTest {
            fun responses(rootKeys: Array<Pair<String, Any?>>) =
                mapOf(
                    "https://example.com/0.yaml" to layer("https://example.com/1.yaml", *rootKeys),
                    "https://example.com/1.yaml" to layer("https://10.0.0.5/2.yaml", "allow-private-network" to true),
                    "https://10.0.0.5/2.yaml" to layer(null),
                )
            val root = ConfigSource("https://example.com/0.yaml")

            val chain = jsonResolver(recordingFetcher(responses(emptyArray()), ssrfMode = SsrfMode.STRICT)).buildChain(root)
            assertEquals(3, chain.size)
            assertFalse(NetworkPolicy.allowsPrivateNetwork(), "a parent's key never leaks into the caller's context")
            assertEquals(true, ChainFetchKeys.allowPrivateNetwork(chain.fetchKeys))

            val rootOff = arrayOf<Pair<String, Any?>>("allowPrivateNetwork" to false)
            val error = assertFailsWith<ConfigException> {
                jsonResolver(recordingFetcher(responses(rootOff), ssrfMode = SsrfMode.STRICT)).buildChain(root)
            }
            assertTrue(error.message.orEmpty().contains("private network"), error.message)
            assertFalse(NetworkPolicy.allowsPrivateNetwork())
        }

    @Test
    fun `a parent's allowLocalhost lets its own parent come from a loopback host`() =
        runTest {
            fun responses(parentKeys: Array<Pair<String, Any?>>) =
                mapOf(
                    "https://example.com/0.yaml" to layer("https://example.com/1.yaml"),
                    "https://example.com/1.yaml" to layer("https://localhost:8443/2.yaml", *parentKeys),
                    "https://localhost:8443/2.yaml" to layer(null),
                )
            val root = ConfigSource("https://example.com/0.yaml")

            val error = assertFailsWith<ConfigException> { jsonResolver(recordingFetcher(responses(emptyArray()))).buildChain(root) }
            assertTrue(error.message.orEmpty().contains("loopback"), error.message)

            val allowed = arrayOf<Pair<String, Any?>>("allow-localhost" to true)
            assertEquals(3, jsonResolver(recordingFetcher(responses(allowed))).buildChain(root).size)
        }

    @Test
    fun `a parent's cache downloads applies to fetching its own parents`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to layer("https://example.com/1.yaml"),
                "https://example.com/1.yaml" to
                    layer("https://example.com/2.yaml", "cache" to mapOf("downloads" to mapOf("enabled" to false))),
                "https://example.com/2.yaml" to layer(null),
            )
            val logger = DebugCapturingLogger()
            buildCached(responses, logger)

            val bypassed = logger.debugLines.filter { it.startsWith("CacheLayer: bypass for") }
            assertEquals(1, bypassed.size, "${logger.debugLines}")
            assertTrue("https://example.com/2.yaml" in bypassed.single(), bypassed.single())
        }

    @Test
    fun `a parent's cache downloads ttl applies to fetching its own parents`() =
        runTest {
            val logger = DebugCapturingLogger()
            buildCached(downloadsChain(mapOf("ttl" to "5m")), logger)

            assertEquals("${KarandashDuration.ofSeconds(300L)}", cachedTtl(logger, "https://example.com/2.yaml"), "${logger.debugLines}")
            assertEquals("${KarandashDuration.ofSeconds(3600L)}", cachedTtl(logger, "https://example.com/1.yaml"), "${logger.debugLines}")
        }

    @Test
    fun `a cache downloads field a layer does not set keeps the inherited value`() =
        runTest {
            val disabled = DebugCapturingLogger()
            buildCached(downloadsChain(mapOf("ttl" to "5m")), disabled, ownEnabled = false)
            assertTrue(
                disabled.debugLines.none { it.startsWith("CacheLayer: cached fetch") },
                "a ttl alone does not re-enable a disabled cache: ${disabled.debugLines}",
            )

            val ownTtl = KarandashDuration.ofSeconds(120L)
            val logger = DebugCapturingLogger()
            buildCached(downloadsChain(mapOf("enabled" to true)), logger, ownTtl = ownTtl)
            assertEquals("$ownTtl", cachedTtl(logger, "https://example.com/2.yaml"), "enabled alone keeps the TTL: ${logger.debugLines}")
        }

    @Test
    fun `chain-fetch keys are interpolated against the env defaults inherited so far`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to layer("https://example.com/1.yaml", "env" to mapOf("KD_CHAIN_TEST_APN" to "true")),
                "https://example.com/1.yaml" to layer(
                    "https://10.0.0.5/2.yaml",
                    "env" to mapOf("KD_CHAIN_TEST_TIMEOUT" to "7s"),
                    "allow-private-network" to "\${KD_CHAIN_TEST_APN}",
                    "limits" to mapOf("fetch-timeout" to "\${KD_CHAIN_TEST_TIMEOUT}"),
                ),
                "https://10.0.0.5/2.yaml" to layer(null),
            )
            val timeouts = mutableMapOf<String, Long?>()
            val chain = jsonResolver(recordingFetcher(responses, timeouts, SsrfMode.STRICT))
                .buildChain(ConfigSource("https://example.com/0.yaml"))

            assertEquals(3, chain.size)
            assertEquals(7_000L, timeouts["https://10.0.0.5/2.yaml"], "$timeouts")
            assertEquals(true, ChainFetchKeys.allowPrivateNetwork(chain.fetchKeys))
        }

    @Test
    fun `extends urls and auth of every layer interpolate against the env defaults inherited so far`() =
        runTest {
            val parentEntry = mapOf(
                "url" to "\${KD_EXT_HOST}/2.yaml",
                "auth" to mapOf("type" to "bearer-token", "token" to "\${KD_EXT_TOKEN}"),
            )
            val responses = mapOf(
                "https://example.com/0.yaml" to layer(
                    "\${KD_EXT_HOST}/1.yaml",
                    "env" to mapOf("KD_EXT_HOST" to "https://example.com", "KD_EXT_TOKEN" to "t0"),
                ),
                "https://example.com/1.yaml" to layer(null, "extends" to parentEntry),
                "https://example.com/2.yaml" to layer(null),
            )
            val chain = jsonResolver(recordingFetcher(responses)).buildChain(ConfigSource("https://example.com/0.yaml"))

            assertEquals(
                listOf(
                    "https://example.com/2.yaml",
                    "https://example.com/1.yaml",
                    "https://example.com/0.yaml"
                ),
                chain.entries.map {
                    it.source.url
                }
            )
            assertEquals(
                HttpAuth.Bearer("t0"),
                chain.entries
                    .first()
                    .source.auth
            )
        }

    @Test
    fun `a manifest set entry interpolates against the env defaults of the layer naming it`() =
        runTest {
            val dir = TestFiles.createTestTempDirectory("karandash-chain-manifest")
            SystemFileSystem.createDirectories(Path(dir, ".karandash"))
            val manifest = mapOf(
                "extends-sets" to mapOf(
                    "std" to listOf(
                        mapOf(
                            "url" to "\${KD_MF_HOST}/1.yaml",
                            "auth" to mapOf("type" to "bearer-token", "token" to "\${KD_MF_TOKEN}"),
                        ),
                    ),
                ),
            )
            SystemFileSystem.sink(Path(dir, ManifestResolver.MANIFEST_PATH)).buffered().use {
                it.writeString(anyToJsonElement(manifest).toString())
            }
            val responses = mapOf(
                "https://example.com/0.yaml" to layer(
                    "@std",
                    "env" to mapOf("KD_MF_HOST" to "https://example.com", "KD_MF_TOKEN" to "t1"),
                ),
                "https://example.com/1.yaml" to layer(null),
            )
            try {
                val manifests = ManifestResolver(dir) { KarandashJson.Default.parseToJsonElement(it) }
                val chain = ConfigChainResolver(
                    ConfigFormatRegistry(listOf(jsonStubModule)),
                    fetcher = recordingFetcher(responses),
                    entryResolver = ExtendsEntryResolver(manifestResolver = manifests),
                ).buildChain(ConfigSource("https://example.com/0.yaml"))

                assertEquals(
                    "https://example.com/1.yaml",
                    chain.entries
                        .first()
                        .source.url
                )
                assertEquals(
                    HttpAuth.Bearer("t1"),
                    chain.entries
                        .first()
                        .source.auth
                )
            } finally {
                TestFiles.deleteRecursively(dir)
            }
        }

    @Test
    fun `a diamond is fetched under the keys of the shallowest path`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to layer(
                    null,
                    "extends" to listOf("https://example.com/a.yaml", "https://example.com/b.yaml")
                ),
                "https://example.com/a.yaml" to layer("https://example.com/c.yaml", "limits" to mapOf("fetch-timeout" to "7s")),
                "https://example.com/c.yaml" to layer("https://example.com/x.yaml"),
                "https://example.com/b.yaml" to layer("https://example.com/x.yaml", "limits" to mapOf("fetch-timeout" to "9s")),
                "https://example.com/x.yaml" to layer(null),
            )
            val timeouts = mutableMapOf<String, Long?>()
            jsonResolver(recordingFetcher(responses, timeouts)).buildChain(ConfigSource("https://example.com/0.yaml"))

            assertEquals(9_000L, timeouts["https://example.com/x.yaml"], "x is reached at depth 2 through b: $timeouts")
        }

    @Test
    fun `a diamond of equal depth is fetched under the keys of the later-declared path`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to layer(
                    null,
                    "extends" to listOf("https://example.com/a.yaml", "https://example.com/b.yaml")
                ),
                "https://example.com/a.yaml" to layer("https://example.com/x.yaml", "limits" to mapOf("fetch-timeout" to "7s")),
                "https://example.com/b.yaml" to layer("https://example.com/x.yaml", "limits" to mapOf("fetch-timeout" to "9s")),
                "https://example.com/x.yaml" to layer(null),
            )
            val timeouts = mutableMapOf<String, Long?>()
            val chain = jsonResolver(recordingFetcher(responses, timeouts)).buildChain(ConfigSource("https://example.com/0.yaml"))

            assertEquals(4, chain.size)
            assertEquals(9_000L, timeouts["https://example.com/x.yaml"], "$timeouts")
        }

    @Test
    fun `a sibling's allowPrivateNetwork does not apply to another sibling's parents`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to layer(
                    null,
                    "extends" to listOf("https://example.com/a.yaml", "https://example.com/b.yaml")
                ),
                "https://example.com/a.yaml" to layer(null, "allow-private-network" to true),
                "https://example.com/b.yaml" to layer("https://10.0.0.5/p.yaml"),
                "https://10.0.0.5/p.yaml" to layer(null),
            )
            val error = assertFailsWith<ConfigException> {
                jsonResolver(recordingFetcher(responses, ssrfMode = SsrfMode.STRICT)).buildChain(ConfigSource("https://example.com/0.yaml"))
            }
            assertTrue(error.message.orEmpty().contains("private network"), error.message)
            assertFalse(NetworkPolicy.allowsPrivateNetwork())
        }

    @Test
    fun `the allowPrivateNetwork value of the calling run is unchanged when a build fails`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to layer("https://example.com/1.yaml", "allow-private-network" to false),
                "https://example.com/1.yaml" to layer("https://10.0.0.5/2.yaml"),
                "https://10.0.0.5/2.yaml" to layer(null),
            )
            withContext(NetworkPolicy(allowPrivateNetwork = true)) {
                assertFailsWith<ConfigException> {
                    jsonResolver(
                        recordingFetcher(responses, ssrfMode = SsrfMode.STRICT)
                    ).buildChain(ConfigSource("https://example.com/0.yaml"))
                }
                assertTrue(NetworkPolicy.allowsPrivateNetwork(), "the run's value is untouched")
            }
        }

    @Test
    fun `entries of one level are fetched each under its own allowPrivateNetwork`() =
        runTest {
            val responses = mapOf(
                "https://example.com/0.yaml" to layer(
                    null,
                    "extends" to listOf("https://example.com/a.yaml", "https://example.com/b.yaml")
                ),
                "https://example.com/a.yaml" to layer("https://10.0.0.5/a2.yaml", "allow-private-network" to true),
                "https://example.com/b.yaml" to layer("https://example.com/b2.yaml", "allow-private-network" to false),
                "https://10.0.0.5/a2.yaml" to layer(null),
                "https://example.com/b2.yaml" to layer(null),
            )
            val flags = mutableMapOf<String, Boolean>()
            val client = HttpClient(MockEngine) {
                engine {
                    addHandler { request ->
                        val body = responses[request.url.toString()]
                        if (body != null) respond(body) else respond("Not Found", HttpStatusCode.NotFound)
                    }
                }
                // The engine runs outside the caller's coroutine context; a Send hook runs inside it.
                install(
                    createClientPlugin("RecordPolicy") {
                        on(Send) { request ->
                            flags[request.url.buildString()] = NetworkPolicy.allowsPrivateNetwork()
                            proceed(request)
                        }
                    },
                )
            }
            val chain = jsonResolver(RemoteConfigFetcher(client, ssrfMode = SsrfMode.STRICT))
                .buildChain(ConfigSource("https://example.com/0.yaml"))

            assertEquals(5, chain.size)
            assertEquals(true, flags["https://10.0.0.5/a2.yaml"], "$flags")
            assertEquals(false, flags["https://example.com/b2.yaml"], "$flags")
            assertFalse(NetworkPolicy.allowsPrivateNetwork())
        }

    /** `0 → 1 → 2`, where `1` sets [downloads] as its `cache.downloads`. */
    private fun downloadsChain(downloads: Map<String, Any?>) =
        mapOf(
            "https://example.com/0.yaml" to layer("https://example.com/1.yaml"),
            "https://example.com/1.yaml" to layer("https://example.com/2.yaml", "cache" to mapOf("downloads" to downloads)),
            "https://example.com/2.yaml" to layer(null),
        )

    /** Builds the chain of [responses] from `0.yaml` through a [CacheLayer] on a fresh temp CAS store, deleted afterwards. */
    private suspend fun buildCached(
        responses: Map<String, String>,
        logger: Logger,
        ownTtl: KarandashDuration = KarandashDuration.ofSeconds(3600L),
        ownEnabled: Boolean = true,
    ) {
        val dir = TestFiles.createTestTempDirectory("karandash-chain-fetch")
        val fetcher = recordingFetcher(responses)
        try {
            val cacheLayer = CacheLayer(fetcher, CasCache(dir, "downloads"), ownTtl, logger, ownEnabled)
            jsonResolver(fetcher, cacheLayer = cacheLayer).buildChain(ConfigSource("https://example.com/0.yaml"))
        } finally {
            fetcher.close()
            TestFiles.deleteRecursively(dir)
        }
    }

    /** The TTL the cached fetch of [url] used, from the cache layer's debug line. */
    private fun cachedTtl(
        logger: DebugCapturingLogger,
        url: String,
    ): String? =
        logger.debugLines
            .firstOrNull { it.startsWith("CacheLayer: cached fetch of $url") }
            ?.substringAfter("(ttl=")
            ?.removeSuffix(")")

    private class DebugCapturingLogger : Logger {
        val debugLines = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            debugLines += message
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }
}
