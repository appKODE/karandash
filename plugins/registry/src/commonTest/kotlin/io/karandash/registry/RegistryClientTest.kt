package io.karandash.registry

import io.karandash.api.ModuleType
import io.karandash.api.exception.RegistrySecurityException
import io.karandash.models.support.KarandashJson
import io.karandash.platform.lastModifiedMillis
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.test.runTest
import kotlinx.io.Buffer
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RegistryClientTest {
    private val requests = mutableListOf<Map<String, String?>>()

    private fun tempDir(): Path =
        TestFiles.createTestTempDirectory("registry-client").also {
            SystemFileSystem.createDirectories(it)
        }

    private fun client(
        cacheDir: Path = tempDir(),
        url: String = URL,
        auth: RegistryAuth? = null,
        status: HttpStatusCode = HttpStatusCode.OK,
        now: () -> Long = { 0L },
    ) = RegistryClient(
        cacheDir = cacheDir,
        registryUrl = url,
        auth = auth,
        httpClient = HttpClient(
            MockEngine { request ->
                requests += mapOf(
                    "url" to request.url.toString(),
                    "auth" to request.headers[HttpHeaders.Authorization],
                    "private" to request.headers[RegistryAuth.GITLAB_HEADER_NAME],
                )
                if (status == HttpStatusCode.OK) respond(registryJson()) else respondError(status)
            },
        ),
        clock = now,
    )

    @Test
    fun `fetches the remote registry and sends bearer auth`() =
        runTest {
            val registry = client(auth = RegistryAuth("secret")).fetchRegistry().getOrThrow()
            assertEquals(listOf("lint-rule", "html-out", "gitea-svc"), registry.plugins.map { it.id })
            assertEquals("Bearer secret", requests.single()["auth"])
        }

    @Test
    fun `custom header auth sends the bare token`() =
        runTest {
            client(auth = RegistryAuth("pat", RegistryAuth.GITLAB_HEADER_NAME)).fetchRegistry().getOrThrow()
            assertEquals("pat", requests.single()["private"])
            assertNull(requests.single()["auth"])
            assertEquals(RegistryAuth.GITLAB_HEADER_NAME to "pat", RegistryAuth("pat", RegistryAuth.GITLAB_HEADER_NAME).toHeader())
        }

    @Test
    fun `fresh cache is served without a request and force refresh bypasses it`() =
        runTest {
            val dir = tempDir()
            client(cacheDir = dir, now = { 0L }).fetchRegistry().getOrThrow()
            val cachedClient = client(cacheDir = dir, now = { currentFileTime(dir) + 1 })
            requests.clear()

            assertEquals(
                3,
                cachedClient
                    .fetchRegistry()
                    .getOrThrow()
                    .plugins.size
            )
            assertTrue(requests.isEmpty())

            cachedClient.fetchRegistry(forceRefresh = true).getOrThrow()
            assertEquals(1, requests.size)
        }

    @Test
    fun `expired cache triggers a new fetch`() =
        runTest {
            val dir = tempDir()
            client(cacheDir = dir).fetchRegistry().getOrThrow()
            requests.clear()
            client(cacheDir = dir, now = { currentFileTime(dir) + CACHE_DURATION_MS + 1 }).fetchRegistry().getOrThrow()
            assertEquals(1, requests.size)
        }

    @Test
    fun `http error is a failure naming the status`() =
        runTest {
            val result = client(status = HttpStatusCode.NotFound).fetchRegistry()
            assertTrue(
                result
                    .exceptionOrNull()
                    ?.message
                    .orEmpty()
                    .contains("HTTP 404")
            )
        }

    @Test
    fun `non https registry url is rejected before any request`() =
        runTest {
            val result = client(url = "http://insecure.example/registry.json").fetchRegistry()
            assertIs<RegistrySecurityException>(result.exceptionOrNull())
            assertTrue(requests.isEmpty())
        }

    @Test
    fun `stale cache is null without a cache file and returns the age with one`() =
        runTest {
            val dir = tempDir()
            assertNull(client(cacheDir = dir).staleCache())
            client(cacheDir = dir).fetchRegistry().getOrThrow()
            val stale = assertNotNull(client(cacheDir = dir, now = { currentFileTime(dir) + 5_000 }).staleCache())
            assertEquals(3, stale.first.plugins.size)
            assertEquals(5_000L, stale.second)
        }

    @Test
    fun `corrupt cache is not served as stale`() =
        runTest {
            val dir = tempDir()
            client(cacheDir = dir).fetchRegistry().getOrThrow()
            val cacheFile = SystemFileSystem.list(dir).single()
            SystemFileSystem.sink(cacheFile).use { sink ->
                val bytes = "{not json".encodeToByteArray()
                sink.write(Buffer().apply { write(bytes) }, bytes.size.toLong())
            }
            assertNull(client(cacheDir = dir).staleCache())
        }

    @Test
    fun `filterPlugins matches type labels tags and free text`() {
        val client = client()
        val registry = KarandashJson.Default.decodeFromString(PluginRegistry.serializer(), registryJson())

        assertEquals(listOf("lint-rule"), client.filterPlugins(registry, type = "RULE").map { it.id })
        assertEquals(listOf("gitea-svc"), client.filterPlugins(registry, type = "provider").map { it.id })
        assertEquals(listOf("gitea-svc"), client.filterPlugins(registry, type = "platform_service").map { it.id })
        assertEquals(listOf("html-out"), client.filterPlugins(registry, tag = "HTML").map { it.id })
        assertEquals(listOf("lint-rule"), client.filterPlugins(registry, query = "TITLES").map { it.id })
        assertEquals(listOf("html-out"), client.filterPlugins(registry, query = "pages").map { it.id })
        assertEquals(listOf("gitea-svc"), client.filterPlugins(registry, query = "forge").map { it.id })
        assertEquals(3, client.filterPlugins(registry, query = "  ").size)
        assertEquals(emptyList(), client.filterPlugins(registry, query = "titles", tag = "html"))
    }

    @Test
    fun `filterPlugins keeps unknown type labels verbatim and matches free text on tags alone`() {
        val client = client()
        val registry = KarandashJson.Default.decodeFromString(PluginRegistry.serializer(), registryJson())
        val custom = registry.copy(plugins = registry.plugins + registry.plugins.first().copy(id = "custom", type = "Custom_Kind"))

        assertEquals(listOf("custom"), client.filterPlugins(custom, type = "custom-kind").map { it.id })
        assertEquals(listOf("gitea-svc"), client.filterPlugins(registry, query = "SCM").map { it.id })
    }

    @Test
    fun `platform artifacts survive the registry cache`() =
        runTest {
            val dir = tempDir()
            client(cacheDir = dir).fetchRegistry().getOrThrow()
            requests.clear()
            val cached = client(cacheDir = dir, now = { currentFileTime(dir) + 1 }).fetchRegistry().getOrThrow()

            assertTrue(requests.isEmpty())
            assertEquals(ARTIFACTS, cached.plugins.single { it.id == "html-out" }.artifacts)
        }

    @Test
    fun `host incompatibility names both versions only when the host is too old`() {
        val entry = KarandashJson.Default
            .decodeFromString(PluginRegistry.serializer(), registryJson())
            .plugins
            .first()
            .copy(minHostVersion = "2.0.0")
        assertEquals("Plugin 'lint-rule' v1.0.0 requires karandash >= 2.0.0 (this is 1.9.0)", entry.hostIncompatibility("1.9.0"))
        assertNull(entry.hostIncompatibility("2.0.0"))
        assertNull(entry.copy(minHostVersion = "").hostIncompatibility("1.0.0"))
    }

    @Test
    fun `template type labels map aliases to platform service`() {
        assertEquals(
            setOf("rule", "reporter", "platform-service", "provider", "mutator", "comment-poster"),
            TEMPLATE_TYPE_LABELS.keys,
        )
        assertEquals(ModuleType.PLATFORM_SERVICE, TEMPLATE_TYPE_LABELS["mutator"])
    }

    private fun currentFileTime(dir: Path): Long =
        SystemFileSystem
            .list(dir)
            .single()
            .lastModifiedMillis()

    private fun registryJson(): String =
        KarandashJson.Default.encodeToString(
            PluginRegistry.serializer(),
            PluginRegistry(
                version = "1",
                lastUpdated = "2026-10-01",
                plugins = listOf(
                    entry("lint-rule", "Lint Rule", "Checks titles", "rule", listOf("lint")),
                    entry("html-out", "HTML", "Renders pages", "reporter", listOf("html"), ARTIFACTS),
                    entry("gitea-svc", "Gitea", "Talks to a forge", "platform_service", listOf("scm")),
                ),
            ),
        )

    private fun entry(
        id: String,
        name: String,
        description: String,
        type: String,
        tags: List<String>,
        artifacts: Map<String, ArtifactInfo>? = null,
    ) = PluginRegistryEntry(
        id = id,
        name = name,
        description = description,
        author = "a",
        version = "1.0.0",
        type = type,
        tags = tags,
        url = "https://example.com/$id.jar",
        checksum = "sha256:00",
        artifacts = artifacts,
        minHostVersion = "0.0.1",
        repository = "https://example.com/$id",
        documentation = "https://example.com/$id/docs",
    )

    private companion object {
        const val URL = "https://registry.test/registry.json"
        val ARTIFACTS = mapOf("macos-arm64" to ArtifactInfo("https://example.com/html-out.dylib", "sha256:11", "dylib"))
    }
}
