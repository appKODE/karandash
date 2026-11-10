package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.plugin.FakePluginLister
import io.karandash.cli.plugin.PluginVerifyCommand
import io.karandash.inspector.PluginFileMetadata
import io.karandash.inspector.PluginInspector
import io.karandash.testfixtures.TestFiles
import java.io.File
import java.security.KeyPairGenerator
import java.security.Signature
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** `plugin verify --load` and `plugin list --probe` run the plugin metadata readers only on request. */
class PluginProbeCommandsTest {
    private val root: File = java.nio.file.Paths
        .get(TestFiles.createTestTempDirectory("plugin-probe").toString())
        .toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    /** Static [readMetadata] answers the file name; [probeMetadata] answers what the guest declares and is recorded. */
    private class FakeInspector : PluginInspector {
        val probed = mutableListOf<String>()

        override suspend fun readMetadata(path: String) = PluginFileMetadata("file-name", "file-name", "unknown")

        override suspend fun probeMetadata(path: String): PluginFileMetadata {
            probed += path
            return PluginFileMetadata("declared-id", "Declared", "3.1.0")
        }

        override suspend fun verifyFile(
            path: String,
            extension: String,
        ): String? = null

        override fun nativeLibraryExtensions() = emptySet<String>()

        override suspend fun shutdown() = Unit
    }

    @Test
    fun `plugin verify runs the plugin only with --load and after the signature checks out`() {
        val plugin = File(root, "acme.wasm").apply { writeBytes(byteArrayOf(0, 'a'.code.toByte(), 's'.code.toByte())) }
        val pair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
        val sig = File(root, "acme.wasm.sig").apply {
            writeBytes(
                Signature.getInstance("SHA256withRSA").run {
                    initSign(pair.private)
                    update(plugin.readBytes())
                    sign()
                },
            )
        }
        val inspector = FakeInspector()
        val trusted = { listOf(pair.public.encoded) }

        assertEquals(0, PluginVerifyCommand(inspector, trusted).test(listOf(plugin.path)).statusCode)
        assertEquals(emptyList(), inspector.probed, "plain verify never runs plugin code")

        val loaded = PluginVerifyCommand(inspector, trusted).test(listOf(plugin.path, "--load"))
        assertEquals(0, loaded.statusCode, loaded.output)
        assertTrue("Loaded: declared-id v3.1.0" in loaded.output, loaded.output)
        assertEquals(listOf(plugin.path), inspector.probed)

        sig.writeBytes(byteArrayOf(1, 2, 3))
        val forged = PluginVerifyCommand(inspector, trusted).test(listOf(plugin.path, "--load"))
        assertEquals(1, forged.statusCode, forged.output)
        assertEquals(listOf(plugin.path), inspector.probed, "a bad signature never reaches the loader")
        assertTrue(PluginVerifyCommand(inspector, trusted).test(listOf("--help")).output.contains("execute", ignoreCase = true))
    }

    @Test
    fun `plugin list shows the declared identity only with --probe`() {
        val inspector = FakeInspector()

        val plain = PluginListCommand(FakePluginLister(), inspector).test(emptyList())
        assertEquals(0, plain.statusCode, plain.output)
        assertEquals(emptyList(), inspector.probed, "plain list never runs plugin code")

        val probed = PluginListCommand(FakePluginLister(), inspector).test(listOf("--probe"))
        assertTrue("Declares: declared-id v3.1.0" in probed.output, probed.output)
        assertEquals(listOf("/p/acme.jar"), inspector.probed)
        assertTrue(PluginListCommand(FakePluginLister(), inspector).test(listOf("--help")).output.contains("execute", ignoreCase = true))
    }

    private companion object {
        const val KEY_BITS = 2048
    }
}
