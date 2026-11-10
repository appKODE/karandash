package io.karandash.cli.plugin

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.errors.ExitCodes
import io.karandash.manifest.ManifestData
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** `plugin update` and `plugin rebuild` require trusted signatures exactly like `plugin install`. */
class PluginUpdateRebuildSignatureTest {
    private val tempDir = TestFileHelpers.createTempDir("plugin-update-signature")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun client() =
        { manifest: ManifestData ->
            manifest.registryClient(cacheDir = Path(tempDir, "cache"), engine = registryEngine("https://x/acme.jar"))
        }

    private fun keysOf(lister: FakePluginLister) = lister.signatureKeys.map { it?.single()?.toList() }

    @Test
    fun `plugin update requires trusted signatures unless unsigned is allowed`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        val base = listOf("--plugin-dir", tempDir.toString(), "--yes")
        val signed = PluginUpdateCommand(lister, client(), trustedKeys = { listOf(TRUSTED_KEY) }).test(base)
        assertEquals(ExitCodes.SUCCESS, signed.statusCode, signed.output)
        PluginUpdateCommand(lister, client(), trustedKeys = { listOf(TRUSTED_KEY) }).test(base + "--allow-unsigned")

        assertEquals(listOf(TRUSTED_KEY.toList(), null), keysOf(lister))
    }

    @Test
    fun `plugin rebuild requires trusted signatures unless unsigned is allowed`() {
        val lister = FakePluginLister(download = Result.success(null to "acme.jar"))
        val base = listOf("acme", "--plugin-dir", tempDir.toString())
        val signed = PluginRebuildCommand(lister, client(), trustedKeys = { listOf(TRUSTED_KEY) }).test(base)
        assertEquals(ExitCodes.SUCCESS, signed.statusCode, signed.output)
        PluginRebuildCommand(lister, client(), trustedKeys = { listOf(TRUSTED_KEY) }).test(base + "--allow-unsigned")

        assertEquals(listOf(TRUSTED_KEY.toList(), null), keysOf(lister))
    }

    private companion object {
        val TRUSTED_KEY = byteArrayOf(1, 2, 3)
    }
}
