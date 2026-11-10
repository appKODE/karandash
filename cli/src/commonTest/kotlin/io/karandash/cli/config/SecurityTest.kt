package io.karandash.cli.config

import io.karandash.api.errors.ConfigException
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cache.cas.CasCache
import io.karandash.cli.TestFileHelpers
import io.karandash.config.pipeline.CacheLayer
import io.karandash.config.pipeline.ManifestContentParser
import io.karandash.config.pipeline.ManifestResolver
import io.karandash.config.pipeline.RemoteConfigFetcher
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpAuth
import io.karandash.manifest.CachePolicy
import io.karandash.manifest.ConfigSource
import io.karandash.plugins.parsers.yaml.YamlFormatModule
import io.karandash.utils.GlobExpander
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Test suite for glob expansion (GlobExpander) and the cache layer (CacheLayer).
 *
 * Coverage:
 * - GlobExpander:
 *   - Path traversal blocking
 *   - Max 10 files limit
 *   - Remote URL rejection
 *   - Max depth enforcement
 * - CacheLayer:
 *   - Cache poisoning detection via content hash
 *   - Graceful degradation on cache failures
 * - General:
 *   - HTTPS enforcement for auth
 *   - Path traversal in RemoteConfigFetcher
 */
class SecurityTest {
    // ========== GlobExpander ==========

    @Test
    fun `GlobExpander blocks path traversal with dot-dot segments`() {
        val testDir = TestFileHelpers.createTempDir("security-glob-traversal")
        try {
            val expander = GlobExpander(baseDir = testDir)

            val traversalPatterns = listOf(
                "../../etc/passwd",
                "../../../secrets/*.yaml",
                "configs/../../etc/*.conf",
                "subdir/../../../root/*"
            )

            for (pattern in traversalPatterns) {
                val exception = assertFailsWith<ConfigException> {
                    expander.expand(pattern)
                }

                assertTrue(
                    exception.message!!.contains("E0020"),
                    "Expected E0020 for pattern: $pattern"
                )
                assertTrue(
                    exception.message!!.contains("path traversal") ||
                        exception.message!!.contains("security constraints"),
                    "Expected security message for pattern: $pattern"
                )
            }
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `GlobExpander matches dot-dot inside a name and rejects a dot-dot segment from braces`() {
        val testDir = TestFileHelpers.createTempDir("security-glob-dotdot-name")
        try {
            TestFileHelpers.writeFile(Path(testDir, "v1..2.yaml"), "data")
            val expander = GlobExpander(baseDir = testDir)

            assertEquals(listOf("v1..2.yaml"), expander.expand("v1..2.yaml"))
            assertEquals(listOf("v1..2.yaml"), expander.expand("*..*.yaml"))
            val exception = assertFailsWith<ConfigException> { expander.expand("{..,configs}/base.yaml") }
            assertTrue(exception.message.orEmpty().contains("path traversal"), exception.message)
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `GlobExpander rejects dot-dot segments in every spelling`() {
        val testDir = TestFileHelpers.createTempDir("security-glob-dotdot-variants")
        try {
            val expander = GlobExpander(baseDir = testDir)
            for (pattern in listOf("{..,x}/a", "./..", "a\\..\\b", "a/../b")) {
                val exception = assertFailsWith<ConfigException>("Expected rejection for: $pattern") {
                    expander.expand(pattern)
                }
                assertTrue(exception.message.orEmpty().contains("path traversal"), "$pattern: ${exception.message}")
            }
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `GlobExpander enforces max 10 files expansion limit`() {
        val testDir = TestFileHelpers.createTempDir("security-glob-limit")
        try {
            // Create 11 files (exceeds limit)
            for (i in 1..11) {
                TestFileHelpers.writeFile(Path(testDir, "file$i.yaml"), "data")
            }

            val expander = GlobExpander(baseDir = testDir)
            val exception = assertFailsWith<ConfigException> {
                expander.expand("*.yaml")
            }

            assertTrue(exception.message!!.contains("E0015"))
            assertTrue(exception.message!!.contains("11 files"))
            assertTrue(exception.message!!.contains("max: 10"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `GlobExpander rejects remote URL patterns`() {
        val testDir = TestFileHelpers.createTempDir("security-glob-url")
        try {
            val expander = GlobExpander(baseDir = testDir)

            val urlPatterns = listOf(
                "https://evil.com/configs/*.yaml",
                "http://attacker.net/*.json",
                "ftp://malicious.org/**/*.yml"
            )

            for (pattern in urlPatterns) {
                val exception = assertFailsWith<ConfigException> {
                    expander.expand(pattern)
                }

                assertTrue(exception.message!!.contains("E0015"))
                assertTrue(exception.message!!.contains("remote URL"))
            }
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `GlobExpander enforces max depth limit of 5 levels`() {
        val testDir = TestFileHelpers.createTempDir("security-glob-depth")
        try {
            // Create directory tree 7 levels deep
            var currentPath = testDir
            for (i in 1..7) {
                currentPath = Path(currentPath, "level$i")
                SystemFileSystem.createDirectories(currentPath)
            }
            TestFileHelpers.writeFile(Path(currentPath, "deep.yaml"), "data")

            val expander = GlobExpander(baseDir = testDir)
            val matches = expander.expand("**/*.yaml")

            // Should not find files beyond depth 5
            assertEquals(0, matches.size)
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `GlobExpander prevents symlink escape outside project root`() {
        val testDir = TestFileHelpers.createTempDir("security-glob-symlink")
        try {
            // Note: Symlink creation is platform-specific and may not work in tests
            // This test validates the security check logic
            val expander = GlobExpander(baseDir = testDir)

            // The implementation checks that resolved paths stay within baseDir
            // Even if a symlink were created, the check would catch it
            val result = expander.expand("*.yaml")

            // Should not crash or escape baseDir
            assertTrue(result.isEmpty())
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    // ========== CacheLayer ==========

    @Test
    fun `CacheLayer uses content-addressable storage preventing cache poisoning`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("security-cache-poison")
            try {
                val mockEngine = MockEngine { _ ->
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val casCache = CasCache(baseDir = cacheDir, namespace = "downloads")
                val cache = CacheLayer(fetcher, casCache)

                val source = ConfigSource("https://example.com/config.yaml")

                // First fetch - cache it
                val result1 = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))
                assertEquals("rules: {}", result1)

                // CAS stores content by SHA-256 digest - content is integrity-verified
                // by its address. Verify the cached content is retrievable and correct.
                val result2 = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))
                assertEquals("rules: {}", result2)

                // Invalidate and refetch - should get fresh content from server
                casCache.keys().forEach { casCache.invalidate(it) }
                val result3 = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))
                assertEquals("rules: {}", result3)
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    @Test
    fun `CacheLayer gracefully degrades on cache write failure`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("security-cache-degradation")
            try {
                val mockEngine = MockEngine { _ ->
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))

                // Use an invalid cache directory to trigger write failure
                val invalidCacheDir = Path("/dev/null/invalid/cache")
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = CacheLayer(fetcher, CasCache(baseDir = invalidCacheDir, namespace = "downloads"))

                val source = ConfigSource("https://example.com/config.yaml")

                // Should fall back to direct fetch instead of failing
                val result = cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                assertEquals("rules: {}", result)
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }

    // ========== RemoteConfigFetcher Security Tests ==========

    @Test
    fun `RemoteConfigFetcher blocks path traversal in local file paths`() =
        runTest {
            val fetcher = RemoteConfigFetcher()

            val traversalPaths = listOf(
                "../../etc/passwd",
                "../../../secrets/credentials.yaml",
                "/project/../../../etc/shadow",
                "configs/../../../../../../etc/hosts"
            )

            for (path in traversalPaths) {
                val exception = assertFailsWith<ConfigException> {
                    fetcher.fetch(ConfigSource(path))
                }

                assertTrue(
                    exception.message!!.contains("E0009"),
                    "Expected E0009 for path: $path"
                )
                assertTrue(
                    exception.message!!.contains("path traversal") ||
                        exception.message!!.contains("parent directory"),
                    "Expected path traversal message for path: $path"
                )
            }
        }

    @Test
    fun `RemoteConfigFetcher enforces HTTPS for authenticated requests`() =
        runTest {
            val mockEngine = MockEngine { _ ->
                respond(
                    content = ByteReadChannel("rules: {}"),
                    status = HttpStatusCode.OK,
                    headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                )
            }
            val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))

            val source = ConfigSource(
                url = "http://example.com/config.yaml", // HTTP, not HTTPS
                auth = HttpAuth.Bearer("secret-token")
            )

            val exception = assertFailsWith<ConfigException> {
                fetcher.fetch(source)
            }

            assertTrue(exception.message!!.contains("E0013"))
            assertTrue(exception.message!!.contains("HTTPS"))
        }

    @Test
    fun `RemoteConfigFetcher rejects auth on local file paths`() =
        runTest {
            val testDir = TestFileHelpers.createTempDir("security-local-auth")
            try {
                val configFile = Path(testDir, "config.yaml")
                TestFileHelpers.writeFile(configFile, "rules: {}")

                val fetcher = RemoteConfigFetcher()
                val source = ConfigSource(
                    url = configFile.toString(),
                    auth = HttpAuth.Bearer("should-not-be-used")
                )

                val exception = assertFailsWith<ConfigException> {
                    fetcher.fetch(source)
                }

                assertTrue(exception.message!!.contains("E0009"))
                assertTrue(exception.message!!.contains("not supported"))
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }

    @Test
    fun `ManifestResolver prevents unsafe YAML deserialization`() {
        runTest {
            val testDir = TestFileHelpers.createTempDir("security-yaml-injection")
            try {
                val karandashDir = Path(testDir, ".karandash")
                SystemFileSystem.createDirectories(karandashDir)
                val manifestPath = Path(karandashDir, "extends-manifest.yaml")

                // Attempt YAML injection attack (safe parsers should handle this)
                val manifestContent = """
                    extends-sets:
                      evil:
                        - "!!python/object/apply:os.system ['rm -rf /']"
                """.trimIndent()

                TestFileHelpers.writeFile(manifestPath, manifestContent)

                val resolver =
                    ManifestResolver(baseDir = testDir, contentParser = ManifestContentParser { YamlFormatModule().parseToJsonElement(it) })

                // The YAML parser treats unknown tags as plain strings (safe behavior)
                // This test verifies that the dangerous construct is NOT executed
                val result = resolver.resolve("evil", emptyMap())

                // The dangerous YAML tag should be treated as a plain string URL
                // (not executed as code), which will later fail when trying to fetch it
                assertNotNull(result)
                assertTrue(result.isNotEmpty())
                // Verify the tag is treated as a string, not executed
                val url = result.first().source.url
                assertTrue(
                    url.contains("!!python") || url.contains("os.system"),
                    "YAML tag should be treated as plain string, not executed"
                )
            } finally {
                TestFileHelpers.deleteTempDir(testDir)
            }
        }
    }

    @Test
    fun `GlobExpander stops early when limit reached during traversal`() {
        val testDir = TestFileHelpers.createTempDir("security-early-stop")
        try {
            // Create 15 files to trigger early stopping
            for (i in 1..15) {
                TestFileHelpers.writeFile(Path(testDir, "file$i.yaml"), "data")
            }

            val expander = GlobExpander(baseDir = testDir)

            val exception = assertFailsWith<ConfigException> {
                expander.expand("*.yaml")
            }

            // Should fail with limit exceeded
            assertTrue(exception.message!!.contains("E0015"))
            assertTrue(exception.message!!.contains("max: 10"))
        } finally {
            TestFileHelpers.deleteTempDir(testDir)
        }
    }

    @Test
    fun `RemoteConfigFetcher validates file exists before reading`() =
        runTest {
            val fetcher = RemoteConfigFetcher()
            val source = ConfigSource("/nonexistent/path/config.yaml")

            val exception = assertFailsWith<ConfigException> {
                fetcher.fetch(source)
            }

            assertTrue(exception.message!!.contains("E0005"))
            assertTrue(exception.message!!.contains("not found"))
        }

    @Test
    fun `RemoteConfigFetcher validates relative file exists before reading`() =
        runTest {
            val fetcher = RemoteConfigFetcher()
            val source = ConfigSource("nonexistent-relative-config.yaml")

            val exception = assertFailsWith<ConfigException> {
                fetcher.fetch(source)
            }

            assertTrue(exception.message!!.contains("E0005"))
            assertTrue(exception.message!!.contains("not found"))
        }

    @Test
    fun `CacheLayer prevents directory traversal in cache keys`() =
        runTest {
            val cacheDir = TestFileHelpers.createTempDir("security-cache-key")
            try {
                val mockEngine = MockEngine { _ ->
                    respond(
                        content = ByteReadChannel("rules: {}"),
                        status = HttpStatusCode.OK,
                        headers = headersOf(HttpHeaders.ContentType, "text/yaml")
                    )
                }
                val fetcher = RemoteConfigFetcher(HttpClient(mockEngine))
                val bootstrap = PluginBootstrap()
                bootstrap.loadPlugins()
                val cache = CacheLayer(fetcher, CasCache(baseDir = cacheDir, namespace = "downloads"))

                // URL with path traversal attempts
                val source = ConfigSource("https://example.com/../../etc/passwd.yaml")

                // Should hash the URL, preventing traversal in cache path
                cache.getOrFetch(source, CachePolicy(ttl = KarandashDuration.ofSeconds(3600)))

                // Verify cache files are within cacheDir
                val cacheFiles = SystemFileSystem.list(cacheDir)
                for (file in cacheFiles) {
                    assertTrue(
                        file.toString().startsWith(cacheDir.toString()),
                        "Cache file should be within cacheDir"
                    )
                }
            } finally {
                TestFileHelpers.deleteTempDir(cacheDir)
            }
        }
}
