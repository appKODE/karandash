package io.karandash.contracts.fw

import io.karandash.api.ModuleRegistryImpl
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.crypto.KeyManager
import io.karandash.crypto.SignatureVerifier
import io.karandash.crypto.signJar
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import java.io.File
import java.security.KeyPairGenerator
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Plugin signature verification is unreachable.
 * `JarPluginLoader.keyManager` defaults to null and `PluginBootstrap` builds the loader without ever
 * setting it, so the verification branch never runs and a tampered signed JAR is loaded like any other.
 *
 * Trusted keys live under `~/.karandash/keys` ([KeyManager.defaultKeyDir]) and `~` follows `$HOME`; the
 * `contractTest` task points `HOME` at a build dir, and the test refuses to run against any other home.
 */
class PluginTrustContractTest {
    @Test
    fun `bootstrap rejects a signed plugin jar whose content was tampered with`() =
        runTest {
            val home = File(checkNotNull(System.getenv("HOME")) { "HOME is not set" })
            check(home.invariantSeparatorsPath.contains("/build/contract-home")) {
                "writes trusted keys under HOME; run it via :plugins:integration-tests:contracts:contractTest"
            }
            home.mkdirs()
            try {
                val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()
                val publicKeyFile = File(home, "trusted.pub").apply { writeBytes(keyPair.public.encoded) }
                KeyManager(Path(home.absolutePath, ".karandash/keys")).importKey(Path(publicKeyFile.absolutePath)).getOrThrow()

                val pluginDir = File(home, "plugins").apply { mkdirs() }
                val jar = File(pluginDir, JAR_NAME)
                writeJar(jar, "original")
                SignatureVerifier.signJar(jar.toPath(), keyPair.private).getOrThrow()
                writeJar(jar, "tampered")

                val result = PluginBootstrap(ModuleRegistryImpl()).loadPluginsFromDirs(listOf(Path(pluginDir.absolutePath)))

                val jarFailures = result.failed.filter { it.path == JAR_NAME }
                assertTrue(
                    jarFailures.any { "Signature verification failed" in it.reason },
                    "tampered signed JAR was not rejected by signature verification; failures=$jarFailures",
                )
            } finally {
                File(home, ".karandash").deleteRecursively()
                File(home, "plugins").deleteRecursively()
                File(home, "trusted.pub").delete()
            }
        }

    private fun writeJar(
        jar: File,
        content: String,
    ) {
        ZipOutputStream(jar.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("plugin.txt"))
            zip.write(content.encodeToByteArray())
            zip.closeEntry()
        }
    }

    private companion object {
        const val JAR_NAME = "plugin.jar"
        const val KEY_BITS = 2048
    }
}
