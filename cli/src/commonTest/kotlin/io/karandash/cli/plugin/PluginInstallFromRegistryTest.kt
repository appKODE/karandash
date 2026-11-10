package io.karandash.cli.plugin

import com.github.ajalt.clikt.testing.CliktCommandTestResult
import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.commands.PluginInstallCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.registry.InstalledPluginInfo
import io.karandash.testfixtures.TestFiles
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respondError
import io.ktor.http.HttpStatusCode
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `plugin install --registry <id>`: artifact selection, host compatibility and the exit code of every failure. */
class PluginInstallFromRegistryTest {
    private val tempDir = TestFileHelpers.createTempDir("plugin-install-registry")
    private val manifestPath = Path(tempDir, ".karandash", "manifest.yaml")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun install(
        engine: MockEngine,
        lister: FakePluginLister = FakePluginLister(),
        id: String = "acme",
        extraArgs: List<String> = emptyList(),
    ): CliktCommandTestResult {
        val cache = Path(tempDir, "cache-${Random.nextInt(0, Int.MAX_VALUE)}")
        return PluginInstallCommand(
            lister,
            registryClient = { it.registryClient(cacheDir = cache, engine = engine) },
            manifestPath = manifestPath,
            trustedKeys = { listOf(TRUSTED_KEY) },
        ).test(listOf("--registry", id, "--plugin-dir", tempDir.toString()) + extraArgs)
    }

    @Test
    fun `installs the jvm artifact when no artifact matches the host platform`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        val artifacts =
            ""","artifacts":{"windows-x64":{"url":"https://x/acme.dll","checksum":"c","format":"dll"},""" +
                """"jvm":{"url":"https://x/acme-jvm.jar","checksum":"c","format":"jar"}}"""
        val result = install(registryEngine("https://x/acme.zip", extra = artifacts), lister)

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("Installed acme v2.0.0" in result.output, result.output)
        assertEquals(listOf("https://x/acme-jvm.jar"), lister.downloads)
    }

    @Test
    fun `falls back to the entry url when its extension is supported`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        val result = install(registryEngine("https://x/acme.JAR?sig=1"), lister)

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertEquals(listOf("https://x/acme.JAR?sig=1"), lister.downloads)
    }

    /** Selection goes through `PlatformArtifactResolver` (cross-platform before another host). */
    @Test
    fun `a cross-platform artifact wins over an artifact for another host`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.wasm"), formats = setOf("jar", "wasm"))
        val artifacts =
            ""","artifacts":{"linux-riscv64":{"url":"https://x/acme-riscv.jar","checksum":"c","format":"jar"},""" +
                """"wasm":{"url":"https://x/acme.wasm","checksum":"c","format":"wasm"}}"""
        val result = install(registryEngine("https://x/acme.zip", extra = artifacts), lister)

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertEquals(listOf("https://x/acme.wasm"), lister.downloads)
    }

    @Test
    fun `registry installs require trusted signatures unless unsigned is allowed`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        install(registryEngine("https://x/acme.jar"), lister)
        install(registryEngine("https://x/acme.jar"), lister, extraArgs = listOf("--allow-unsigned"))

        assertEquals(listOf(TRUSTED_KEY.toList(), null), lister.signatureKeys.map { it?.single()?.toList() })
    }

    @Test
    fun `install with allow-unsigned warns that the JAR loads unverified`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        val signed = install(registryEngine("https://x/acme.jar"), lister)
        val unsigned = install(registryEngine("https://x/acme.jar"), lister, extraArgs = listOf("--allow-unsigned"))

        assertFalse("unverified" in signed.stderr, signed.output)
        assertEquals(ExitCodes.SUCCESS, unsigned.statusCode, unsigned.output)
        assertTrue("https://x/acme.jar is installed without a signature check" in unsigned.stderr, unsigned.output)
        assertTrue("loads it unverified" in unsigned.stderr, unsigned.output)
    }

    @Test
    fun `an unsigned local jar is rejected unless unsigned is allowed`() {
        val jar = Path(tempDir, "acme.jar").also { TestFiles.writeText(it, "jar") }
        val installed = Result.success(InstalledPluginInfo("acme", "1.0.0", "Acme", "/p/acme.jar"))

        fun local(vararg extra: String) =
            PluginInstallCommand(FakePluginLister(localInstall = installed), trustedKeys = { listOf(TRUSTED_KEY) })
                .test(listOf(jar.toString(), "--plugin-dir", tempDir.toString()) + extra)

        val rejected = local()
        assertEquals(ExitCodes.VALIDATION_FAILED, rejected.statusCode, rejected.output)
        assertTrue("pass --allow-unsigned" in rejected.stderr, rejected.output)
        val allowed = local("--allow-unsigned")
        assertEquals(ExitCodes.SUCCESS, allowed.statusCode, allowed.output)
        assertTrue("loads it unverified" in allowed.stderr, allowed.output)
    }

    @Test
    fun `source must be exactly one of a path or a registry id`() {
        val neither = PluginInstallCommand(FakePluginLister()).test(emptyList())
        assertEquals(ExitCodes.CONFIG_ERROR, neither.statusCode, neither.output)
        val both = PluginInstallCommand(FakePluginLister()).test(listOf("a.jar", "--registry", "acme"))
        assertEquals(ExitCodes.CONFIG_ERROR, both.statusCode, both.output)
        assertTrue("Specify exactly one source" in both.stderr, both.output)
    }

    @Test
    fun `registry failures are config errors`() {
        val unreachable = install(MockEngine { respondError(HttpStatusCode.BadGateway) })
        assertEquals(ExitCodes.CONFIG_ERROR, unreachable.statusCode, unreachable.output)
        assertTrue("Failed to fetch registry" in unreachable.stderr, unreachable.output)

        val unknown = install(registryEngine("https://x/acme.jar"), id = "nope")
        assertEquals(ExitCodes.CONFIG_ERROR, unknown.statusCode, unknown.output)
        assertTrue("Plugin 'nope' not found in registry" in unknown.stderr, unknown.output)
    }

    @Test
    fun `incompatible host unsupported format and plain http are config errors`() {
        val newer = install(registryEngine("https://x/acme.jar", minHostVersion = "999.0.0"))
        assertEquals(ExitCodes.CONFIG_ERROR, newer.statusCode, newer.output)
        assertTrue("requires karandash >= 999.0.0" in newer.stderr, newer.output)

        val noArtifact = install(registryEngine("https://x/acme.dll"))
        assertEquals(ExitCodes.CONFIG_ERROR, noArtifact.statusCode, noArtifact.output)
        assertTrue("No compatible artifact found for 'acme'" in noArtifact.stderr, noArtifact.output)

        val plainHttp = install(registryEngine("http://x/acme.jar"))
        assertEquals(ExitCodes.CONFIG_ERROR, plainHttp.statusCode, plainHttp.output)
        assertTrue("Only HTTPS download URLs are allowed" in plainHttp.stderr, plainHttp.output)
    }

    @Test
    fun `failed download is a validation failure`() {
        val result = install(registryEngine("https://x/acme.jar"))
        assertEquals(ExitCodes.VALIDATION_FAILED, result.statusCode, result.output)
        assertTrue("download refused" in result.stderr, result.output)
    }

    @Test
    fun `pin records the installed version in the manifest`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        val result = install(registryEngine("https://x/acme.jar"), lister, extraArgs = listOf("--pin"))

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("Pinned: acme → 2.0.0 in $manifestPath" in result.output, result.output)
        assertTrue("version: 2.0.0" in TestFiles.readText(manifestPath).replace("\"", ""), TestFiles.readText(manifestPath))
    }

    @Test
    fun `pin into an unreadable manifest warns but keeps the install`() {
        SystemFileSystem.createDirectories(Path(manifestPath, "child"))
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        val result = install(registryEngine("https://x/acme.jar"), lister, extraArgs = listOf("--pin"))

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue("Warning:" in result.stderr, result.output)
    }

    private companion object {
        val TRUSTED_KEY = byteArrayOf(1, 2, 3)
    }
}
