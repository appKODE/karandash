package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.cache.cas.CasCache
import io.karandash.cli.plugin.PluginInfoCommand
import io.karandash.cli.plugin.PluginLoadCommand
import io.karandash.cli.plugin.PluginRebuildCommand
import io.karandash.cli.plugin.PluginRegistrySetCommand
import io.karandash.cli.plugin.PluginRegistryShowCommand
import io.karandash.cli.plugin.PluginUpdateCommand
import io.karandash.cli.plugin.PluginVerifyCommand
import io.karandash.cli.plugin.registryClient
import io.karandash.crypto.ChecksumVerifier
import io.karandash.inspector.InstalledPluginListerImpl
import io.karandash.inspector.createPluginInspector
import io.karandash.loader.DownloadCache
import io.karandash.manifest.ManifestData
import io.karandash.registry.InstalledPluginLister
import io.karandash.registry.PluginInstallUpdateEngine
import io.karandash.testfixtures.TestFiles
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import kotlinx.io.files.Path
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import java.util.jar.Attributes
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.jar.Manifest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `plugin install / list / update / remove / uninstall / info / rebuild / load / verify / registry` against a temp plugin directory and fixture JARs. */
class PluginLifecycleCommandsTest {
    private val root: File = java.nio.file.Paths
        .get(TestFiles.createTestTempDirectory("plugin-lifecycle").toString())
        .toFile()
    private val pluginDir = File(root, "plugins").path
    private val v1 = fixtureJar("1.0.0")
    private val v2 = fixtureJar("2.0.0")

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    @Test
    fun installListUpdateRemove() {
        val install = PluginInstallCommand(lister()).test(listOf(v1.path, "--plugin-dir", pluginDir, "--allow-unsigned"))
        assertEquals(0, install.statusCode, install.output)
        assertTrue(File(pluginDir, "$PLUGIN_ID.jar").isFile, install.output)
        assertTrue("$PLUGIN_ID v1.0.0" in list(), list())

        val update = PluginUpdateCommand(lister(), registryClient()).test(listOf("--yes", "--plugin-dir", pluginDir, "--allow-unsigned"))
        assertEquals(0, update.statusCode, update.output)
        assertTrue("$PLUGIN_ID v2.0.0" in list(), update.output + list())

        val remove = PluginRemoveCommand(lister()).test(listOf(PLUGIN_ID, "--plugin-dir", pluginDir))
        assertEquals(0, remove.statusCode, remove.output)
        assertFalse(PLUGIN_ID in list(), list())
    }

    @Test
    fun infoSourceDependenciesAndUninstall() {
        PluginInstallCommand(lister()).test(listOf(v1.path, "--plugin-dir", pluginDir, "--allow-unsigned"))
        val info = PluginInfoCommand(lister()).test(listOf(PLUGIN_ID, "--plugin-dir", pluginDir))
        assertEquals(0, info.statusCode, info.output)
        assertTrue("Version: 1.0.0" in info.output, info.output)
        val source = PluginInfoCommand(lister()).test(listOf(PLUGIN_ID, "--plugin-dir", pluginDir, "--source"))
        assertEquals(File(pluginDir, "$PLUGIN_ID.jar").path, source.stdout.trim())
        val deps = PluginInfoCommand(lister(), registryClient()).test(listOf(PLUGIN_ID, "--plugin-dir", pluginDir, "--dependencies"))
        assertTrue("Requires: karandash >= 0.1.0 (registry v2.0.0)" in deps.output, deps.output)
        assertTrue("Host: compatible" in deps.output, deps.output)
        assertEquals(2, PluginInfoCommand(lister()).test(listOf("nope", "--plugin-dir", pluginDir)).statusCode)

        val uninstall = PluginRemoveCommand(lister(), name = "uninstall").test(listOf(PLUGIN_ID, "--plugin-dir", pluginDir))
        assertEquals(0, uninstall.statusCode, uninstall.output)
        assertFalse(PLUGIN_ID in list(), list())
    }

    @Test
    fun rebuildReinstallsTheRegistryBuild() {
        PluginInstallCommand(lister()).test(listOf(v1.path, "--plugin-dir", pluginDir, "--allow-unsigned"))
        val rebuild = PluginRebuildCommand(
            lister(),
            registryClient()
        ).test(listOf(PLUGIN_ID, "--plugin-dir", pluginDir, "--allow-unsigned"))
        assertEquals(0, rebuild.statusCode, rebuild.output)
        assertTrue("Rebuilt $PLUGIN_ID v2.0.0 from the registry" in rebuild.output, rebuild.output)
        assertTrue("$PLUGIN_ID v2.0.0" in list(), list())
        val missing = PluginRebuildCommand(lister(), registryClient()).test(listOf("nope", "--plugin-dir", pluginDir))
        assertEquals(2, missing.statusCode, missing.output)
    }

    @Test
    fun loadChecksAPluginFileWithoutInstalling() {
        val load = PluginLoadCommand(lister(), createPluginInspector()).test(listOf(v1.path))
        assertEquals(0, load.statusCode, load.output)
        assertTrue("loads: $PLUGIN_ID v1.0.0" in load.output, load.output)
        assertFalse(File(pluginDir).exists())
        val notAPlugin = File(root, "notes.jar").apply { writeText("not a jar") }
        assertEquals(2, PluginLoadCommand(lister(), createPluginInspector()).test(listOf(notAPlugin.path)).statusCode)
    }

    @Test
    fun verifyChecksTheSignatureAgainstKeyOrTrustedKeys() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val sig = File(root, "plugin.sig").apply {
            writeBytes(
                Signature.getInstance("SHA256withRSA").run {
                    initSign(pair.private)
                    update(v1.readBytes())
                    sign()
                }
            )
        }
        val key = File(root, "public.der").apply { writeBytes(pair.public.encoded) }
        val other = KeyPairGenerator
            .getInstance("RSA")
            .apply { initialize(KEY_BITS) }
            .generateKeyPair()
            .public.encoded

        val ok = PluginVerifyCommand().test(listOf(v1.path, sig.path, "--key", key.path))
        assertEquals(0, ok.statusCode, ok.output)
        assertEquals(0, PluginVerifyCommand { listOf(other, pair.public.encoded) }.test(listOf(v1.path, sig.path)).statusCode)
        assertEquals(1, PluginVerifyCommand { listOf(other) }.test(listOf(v1.path, sig.path)).statusCode)
        assertEquals(2, PluginVerifyCommand { emptyList() }.test(listOf(v1.path, sig.path)).statusCode)
        assertEquals(2, PluginVerifyCommand().test(listOf(v1.path, "--key", key.path)).statusCode) // no <file>.sig
    }

    @Test
    fun installRequiresATrustedSignatureUnlessUnsignedIsAllowed() {
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val trusted = { listOf(pair.public.encoded) }

        val unsigned = PluginInstallCommand(lister(), trustedKeys = trusted).test(listOf(v1.path, "--plugin-dir", pluginDir))
        assertEquals(1, unsigned.statusCode, unsigned.output)
        assertTrue("--allow-unsigned" in unsigned.stderr, unsigned.output)
        assertFalse(File(pluginDir, "$PLUGIN_ID.jar").exists())

        File("${v1.path}.sig").writeBytes(
            Signature.getInstance("SHA256withRSA").run {
                initSign(pair.private)
                update(v1.readBytes())
                sign()
            }
        )
        val signed = PluginInstallCommand(lister(), trustedKeys = trusted).test(listOf(v1.path, "--plugin-dir", pluginDir))
        assertEquals(0, signed.statusCode, signed.output)
        assertTrue(File(pluginDir, "$PLUGIN_ID.jar.sig").isFile, "signature is installed next to the JAR")
    }

    @Test
    fun registrySetWritesTheManifestAndShowReadsIt() {
        val manifest = File(root, ".karandash/manifest.yaml").apply {
            parentFile.mkdirs()
            writeText("# team manifest\nplugins:\n  install:\n    - id: acme\n")
        }
        val path = Path(manifest.path)
        assertTrue("(default)" in PluginRegistryShowCommand(path).test(emptyList()).output)

        val set = PluginRegistrySetCommand(path).test(listOf("https://plugins.example.com/plugins.json"))
        assertEquals(0, set.statusCode, set.output)
        val text = manifest.readText()
        assertTrue("# team manifest" in text && "- id: acme" in text, text)
        val show = PluginRegistryShowCommand(path).test(emptyList())
        assertTrue("Registry: https://plugins.example.com/plugins.json (from $path)" in show.output, show.output)

        assertEquals(2, PluginRegistrySetCommand(path).test(listOf("http://plugins.example.com/plugins.json")).statusCode)
    }

    @Test
    fun `plugin list shows the plugins the manifest declares`() {
        val manifest = File(root, "declared/manifest.yaml").apply {
            parentFile.mkdirs()
            writeText("plugins:\n  install:\n    - id: acme\n      version: 1.2.0\n    - other\n")
        }
        val out = PluginListCommand(lister(), manifestPath = Path(manifest.path)).test(listOf("--plugin-dir", pluginDir)).output
        assertTrue("Declared in manifest:" in out && "acme v1.2.0" in out && "other (latest)" in out, out)
        assertFalse("Declared in manifest:" in list(), "no manifest, no declared section")
    }

    private fun list(): String = PluginListCommand(lister()).test(listOf("--plugin-dir", pluginDir)).output

    /** Each command shuts its lister down, so every command gets a fresh one; downloads come from a mock server. */
    private fun lister(): InstalledPluginLister {
        val real = InstalledPluginListerImpl(createPluginInspector())
        return object : InstalledPluginLister by real {
            override suspend fun downloadAndUpdate(
                downloadUrl: String,
                pluginDirPath: String,
                oldSourcePath: String?,
                pluginId: String,
                expectedChecksum: String?,
                allowLocalhost: Boolean,
                signatureKeys: List<ByteArray>?,
            ) = PluginInstallUpdateEngine.downloadAndUpdate(
                downloadUrl = downloadUrl,
                pluginDirPath = pluginDirPath,
                oldSourcePath = oldSourcePath,
                pluginId = pluginId,
                supportedFormats = real.supportedFormats(),
                verifyPlugin = real::verifyPlugin,
                httpClient = HttpClient(MockEngine { respond(v2.readBytes()) }),
                downloadCache = DownloadCache(CasCache(baseDir = Path(root.path, "cache"), namespace = "downloads")),
                expectedChecksum = expectedChecksum,
                signatureKeys = signatureKeys,
            )
        }
    }

    private fun registryClient() =
        { manifest: ManifestData ->
            val checksum = ChecksumVerifier.computeSha256(Path(v2.path))
            val registry =
                """
                {"version":"1","lastUpdated":"now","plugins":[{
                  "id":"$PLUGIN_ID","name":"Acme","description":"Fixture","author":"acme","version":"2.0.0","type":"rule",
                  "tags":[],"url":"https://registry.test/$PLUGIN_ID.jar","checksum":"sha256:$checksum","minHostVersion":"0.1.0",
                  "repository":"https://example.com","documentation":"https://example.com"
                }]}
                """.trimIndent()
            val engine = MockEngine { respond(registry, headers = headersOf(HttpHeaders.ContentType, "application/json")) }
            manifest.registryClient(cacheDir = Path(root.path, "registry-cache"), engine = engine)
        }

    private fun fixtureJar(version: String): File {
        val file = File(root, "fixtures-$version/$PLUGIN_ID.jar").apply { parentFile.mkdirs() }
        val manifest = Manifest().apply {
            mainAttributes[Attributes.Name.MANIFEST_VERSION] = "1.0"
            mainAttributes.putValue("Plugin-Id", PLUGIN_ID)
            mainAttributes.putValue("Plugin-Version", version)
        }
        // The inspector reads JARs statically: it needs the services entry and the classes it names, never runs them.
        val className = javaClass.name
        val classPath = className.replace('.', '/') + ".class"
        JarOutputStream(file.outputStream(), manifest).use { out ->
            out.putNextEntry(JarEntry("META-INF/services/io.karandash.api.Module"))
            out.write("$className\n".encodeToByteArray())
            out.closeEntry()
            out.putNextEntry(JarEntry(classPath))
            out.write(checkNotNull(javaClass.classLoader.getResourceAsStream(classPath)) { classPath }.readBytes())
            out.closeEntry()
        }
        return file
    }

    private companion object {
        const val PLUGIN_ID = "acme"
        const val KEY_BITS = 2048
    }
}
