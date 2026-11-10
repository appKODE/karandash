package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.cache.cas.CasCache
import io.karandash.duration.KarandashDuration
import io.karandash.logger.Logger
import io.karandash.manifest.ConfigSource
import io.karandash.models.support.KarandashJson
import io.karandash.sdk.config.anyToJsonElement
import io.karandash.testfixtures.TestFiles
import io.karandash.utils.GlobExpander
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `extends:` entry keys (`auth`, `cache`) and the top-level `auth` and `$schema` keys, observed on a mocked remote chain. */
class ExtendsKeyBehaviourTest {
    private val jsonModule = object : ConfigFormatModule {
        override val formatId = "yaml"

        override suspend fun parseToJsonElement(content: String): JsonElement = KarandashJson.Default.parseToJsonElement(content)
    }

    private class DebugLogger : Logger {
        val lines = mutableListOf<String>()

        override val isDebugEnabled: Boolean get() = true

        override fun debug(message: String) {
            lines += message
        }

        override fun info(message: String) = Unit

        override fun warn(message: String) = Unit

        override fun error(message: String) = Unit
    }

    private val root = "https://example.com/root.yaml"
    private val parent = "https://example.com/parent.yaml"

    private fun body(vararg keys: Pair<String, Any?>): String = anyToJsonElement(keys.toMap()).toString()

    private fun deleteTree(path: Path) {
        if (SystemFileSystem.metadataOrNull(path)?.isDirectory == true) SystemFileSystem.list(path).forEach(::deleteTree)
        SystemFileSystem.delete(path, mustExist = false)
    }

    /** Builds the chain from [root]; returns the Authorization header each URL was fetched with. */
    private suspend fun buildChain(
        responses: Map<String, String>,
        logger: Logger? = null,
    ): Map<String, String?> {
        val seen = mutableMapOf<String, String?>()
        val client = HttpClient(MockEngine) {
            engine {
                addHandler { request ->
                    val url = request.url.toString()
                    seen[url] = request.headers[HttpHeaders.Authorization]
                    responses[url]?.let { respond(it) } ?: respond("Not Found", HttpStatusCode.NotFound)
                }
            }
        }
        val fetcher = RemoteConfigFetcher(client, ssrfMode = SsrfMode.OPERATOR)
        val dir = TestFiles.createTestTempDirectory("extends-key-behaviour")
        try {
            val cacheLayer = logger?.let { CacheLayer(fetcher, CasCache(dir, "downloads"), KarandashDuration.ofSeconds(3600L), it) }
            ConfigChainResolver(
                ConfigFormatRegistry(listOf(jsonModule)),
                fetcher = fetcher,
                entryResolver = ExtendsEntryResolver(globExpander = GlobExpander()),
                cacheLayer = cacheLayer,
            ).buildChain(ConfigSource(root))
        } finally {
            fetcher.close()
            deleteTree(dir)
        }
        return seen
    }

    @Test
    fun `extends auth names a top level auth entry whose token is sent to that parent only`() =
        runTest {
            val rootBody = body(
                "auth" to mapOf("corp" to mapOf("type" to "bearer", "token" to "s3cret")),
                "extends" to listOf(mapOf("url" to parent, "auth" to "corp")),
            )
            val seen = buildChain(mapOf(root to rootBody, parent to body()))
            assertEquals("Bearer s3cret", seen[parent])
            assertEquals(null, seen[root])
        }

    @Test
    fun `an extends entry without auth is fetched without credentials even when an auth block exists`() =
        runTest {
            val rootBody = body(
                "auth" to mapOf("corp" to mapOf("type" to "bearer", "token" to "s3cret")),
                "extends" to listOf(mapOf("url" to parent)),
            )
            assertEquals(null, buildChain(mapOf(root to rootBody, parent to body()))[parent])
        }

    @Test
    fun `extends cache ttl sets how long that parent is cached and ttl 0 bypasses the cache`() =
        runTest {
            fun rootWith(ttl: String) = body("extends" to listOf(mapOf("url" to parent, "cache" to mapOf("ttl" to ttl))))
            val short = DebugLogger()
            buildChain(mapOf(root to rootWith("120s"), parent to body()), short)
            assertTrue(
                short.lines.any {
                    it.startsWith("CacheLayer: cached fetch of $parent") &&
                        "ttl=${KarandashDuration.ofSeconds(120L)}" in it
                },
                "${short.lines}"
            )

            val off = DebugLogger()
            buildChain(mapOf(root to rootWith("0"), parent to body()), off)
            assertTrue(off.lines.any { it.startsWith("CacheLayer: bypass for $parent") }, "${off.lines}")
        }

    @Test
    fun `schema key is read by the loader so it is not reported as an unknown key`() {
        val config = anyToJsonElement(mapOf("\$schema" to "https://example.com/schema.json", "bogus" to 1))
        val warnings = UnknownTopLevelKeys.warnings(config)
        assertEquals(1, warnings.size, "$warnings")
        assertTrue("bogus" in warnings.single(), warnings.single())
    }
}
