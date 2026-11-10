package io.karandash.cli.plugin

import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.TestFileHelpers
import io.karandash.loader.PluginDiscovery
import io.karandash.manifest.ManifestData
import io.karandash.manifest.PluginDeclaration
import io.karandash.platform.readText
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PluginManifestSettingsTest {
    private val tempDir = TestFileHelpers.createTempDir("plugin-manifest-settings")
    private val manifestPath = Path(tempDir, "manifest.yaml")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    @Test
    fun manifestRegistrySettingsAreRead() =
        runTest {
            TestFileHelpers.writeFile(
                manifestPath,
                """
                plugins:
                  registry-url: https://registry.example.com/registry.json
                  token: secret
                  auth-header: PRIVATE-TOKEN
                  directory: /opt/plugins
                  allow-localhost: true
                """.trimIndent(),
            )

            val manifest = readPluginManifest(manifestPath) { error(it) }

            assertEquals("https://registry.example.com/registry.json", manifest.registryUrl)
            assertEquals("secret", manifest.registryToken)
            assertEquals("PRIVATE-TOKEN", manifest.registryAuthHeader)
            assertEquals("/opt/plugins", manifest.pluginDir)
            assertTrue(manifest.allowLocalhost)
            assertEquals(Path("/opt/plugins"), manifestPluginDir(warn = { error(it) }, path = manifestPath))
        }

    @Test
    fun `a manifest that is not valid YAML warns and yields defaults`() =
        runTest {
            TestFileHelpers.writeFile(manifestPath, "plugins:\n  install: [unclosed\n  directory: : :\n")
            val warnings = mutableListOf<String>()

            assertEquals(ManifestData(), readPluginManifest(manifestPath) { warnings += it })
            assertEquals(1, warnings.size, "$warnings")
        }

    @Test
    fun manifestPluginDirIsNullWithoutDirectory() =
        runTest {
            assertNull(manifestPluginDir(warn = { error(it) }, path = Path(tempDir, "missing.yaml")))
        }

    @Test
    fun manifestPluginDirIsScannedInAdditionToTheDefaultDir() =
        runTest {
            val defaultDir = Path(tempDir, "default").also { SystemFileSystem.createDirectories(it) }
            val manifestDir = Path(tempDir, "manifest-plugins").also { SystemFileSystem.createDirectories(it) }
            TestFileHelpers.writeFile(Path(defaultDir, "default-broken.mjs"), "this is not ( javascript")
            TestFileHelpers.writeFile(Path(manifestDir, "manifest-broken.mjs"), "this is not ( javascript")
            TestFileHelpers.writeFile(manifestPath, "plugins:\n  directory: $manifestDir\n")
            val env = mapOf(PluginDiscovery.PLUGIN_DIR_ENV to defaultDir.toString())
            val bootstrap = PluginBootstrap(envReader = env::get)
            try {
                val failed = bootstrap.loadPluginsWithManifest(manifestPath = manifestPath).failed.map { it.path }
                assertTrue(failed.any { "default-broken.mjs" in it }, "failed: $failed")
                assertTrue(failed.any { "manifest-broken.mjs" in it }, "failed: $failed")
            } finally {
                bootstrap.shutdown()
            }
        }

    @Test
    fun registryClientUsesManifestUrlAndToken() =
        runTest {
            val seen = mutableListOf<Pair<String, String?>>()
            val engine = MockEngine { request ->
                seen += request.url.toString() to request.headers["PRIVATE-TOKEN"]
                respond(
                    """{"version":"1","lastUpdated":"now","plugins":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val manifest = ManifestData(
                registryUrl = "https://registry.example.com/registry.json",
                registryToken = "secret",
                registryAuthHeader = "PRIVATE-TOKEN",
            )

            val result = manifest.registryClient(cacheDir = tempDir, engine = engine).fetchRegistry(forceRefresh = true)

            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
            assertEquals(listOf<Pair<String, String?>>("https://registry.example.com/registry.json" to "secret"), seen)
        }

    @Test
    fun registryClientReachesLocalhostOnlyWithAllowLocalhost() =
        runTest {
            val engine = MockEngine {
                respond(
                    """{"version":"1","lastUpdated":"now","plugins":[]}""",
                    headers = headersOf(HttpHeaders.ContentType, "application/json"),
                )
            }
            val url = "https://localhost:8443/registry.json"

            val blocked = ManifestData(registryUrl = url).registryClient(cacheDir = tempDir, engine = engine)
            assertTrue(blocked.fetchRegistry(forceRefresh = true).isFailure)

            val allowed = ManifestData(registryUrl = url, allowLocalhost = true).registryClient(cacheDir = tempDir, engine = engine)
            val result = allowed.fetchRegistry(forceRefresh = true)
            assertTrue(result.isSuccess, result.exceptionOrNull()?.message)
        }

    @Test
    fun pinUpsertsTheInstalledVersion() =
        runTest {
            TestFileHelpers.writeFile(
                manifestPath,
                """
                plugins:
                  on-missing: error
                  install:
                    - com.example.other
                    - id: com.example.acme
                      version: "1.0.0"
                      source: https://example.com/acme.jar
                """.trimIndent(),
            )

            assertNull(pinPluginInManifest("com.example.acme", "2.0.0", manifestPath))
            assertNull(pinPluginInManifest("com.example.new", "0.1.0", manifestPath))

            val declarations = readPluginManifest(manifestPath) { error(it) }.pluginDeclarations
            assertEquals(
                listOf(
                    PluginDeclaration("com.example.other"),
                    PluginDeclaration("com.example.acme", "2.0.0", "https://example.com/acme.jar"),
                    PluginDeclaration("com.example.new", "0.1.0"),
                ),
                declarations,
            )
        }

    @Test
    fun pinCreatesAMissingManifest() =
        runTest {
            val path = Path(tempDir, ".karandash", "manifest.yaml")

            assertNull(pinPluginInManifest("com.example.acme", "1.2.3", path))

            assertEquals(
                listOf(PluginDeclaration("com.example.acme", "1.2.3")),
                readPluginManifest(path) { error(it) }.pluginDeclarations,
            )
        }

    @Test
    fun pinKeepsCommentsAndLayout() =
        runTest {
            val original =
                """
                # team manifest
                plugins:
                  # pinned by CI
                  install:
                    - com.example.other # keep me
                    - id: com.example.acme
                      version: "1.0.0" # old
                      source: https://example.com/acme.jar
                  on-missing: error
                # trailing note
                """.trimIndent()
            TestFileHelpers.writeFile(manifestPath, original)

            assertNull(pinPluginInManifest("com.example.acme", "2.0.0", manifestPath))
            assertNull(pinPluginInManifest("com.example.other", "3.0.0", manifestPath))
            assertNull(pinPluginInManifest("com.example.new", "0.1.0", manifestPath))

            val text = manifestPath.readText()
            listOf("# team manifest", "# pinned by CI", "# trailing note", "on-missing: error").forEach {
                assertTrue(it in text, "lost '$it' in:\n$text")
            }
            assertEquals(
                listOf(
                    PluginDeclaration("com.example.other", "3.0.0"),
                    PluginDeclaration("com.example.acme", "2.0.0", "https://example.com/acme.jar"),
                    PluginDeclaration("com.example.new", "0.1.0"),
                ),
                readPluginManifest(manifestPath) { error(it) }.pluginDeclarations,
            )
        }

    @Test
    fun pinFallsBackToARewriteForFlowStyle() =
        runTest {
            TestFileHelpers.writeFile(manifestPath, "plugins: { install: [com.example.other] }")

            assertNull(pinPluginInManifest("com.example.acme", "1.0.0", manifestPath))

            assertEquals(
                listOf(PluginDeclaration("com.example.other"), PluginDeclaration("com.example.acme", "1.0.0")),
                readPluginManifest(manifestPath) { error(it) }.pluginDeclarations,
            )
        }
}
