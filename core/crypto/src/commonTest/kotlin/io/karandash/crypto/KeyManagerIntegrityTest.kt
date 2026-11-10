package io.karandash.crypto

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.algorithms.RSA
import dev.whyoleg.cryptography.algorithms.SHA256
import io.karandash.logger.Logger
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.exists
import io.karandash.platform.isDirectory
import io.karandash.platform.listFiles
import io.karandash.platform.readBytes
import io.karandash.platform.writeBytes
import io.karandash.platform.writeText
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Key ids are validated and a corrupt index is never overwritten. */
class KeyManagerIntegrityTest {
    private class WarnLogger : Logger {
        val warnings = mutableListOf<String>()

        override fun debug(message: String) = Unit

        override fun info(message: String) = Unit

        override fun warn(message: String) {
            warnings += message
        }

        override fun error(message: String) = Unit
    }

    private val dir = TestFiles
        .createTestTempDirectory("key-integrity")
        .also { createDirectories(it) }

    @AfterTest
    fun cleanup() = deleteTree(dir)

    private fun deleteTree(path: Path) {
        if (path.isDirectory()) path.listFiles().orEmpty().forEach { deleteTree(it) }
        deleteIfExists(path)
    }

    private fun publicKeyFile(): Path {
        val der = CryptographyProvider.Default
            .get(RSA.PKCS1)
            .keyPairGenerator(digest = SHA256)
            .generateKeyBlocking()
            .publicKey
            .encodeToByteArrayBlocking(RSA.PublicKey.Format.DER)
        return Path(dir, "key.pub").also { it.writeBytes(der) }
    }

    @Test
    fun `path traversal key ids are rejected`() {
        val keyDir = Path(dir, "keys").also { createDirectories(it) }
        val outside = Path(dir, "victim.pub").also { it.writeText("secret") }
        val keys = KeyManager(keyDir = keyDir)

        listOf("../victim", "", "ABCDEF", "a/b", "abc\u0000").forEach { id ->
            assertIs<IllegalArgumentException>(keys.loadPublicKey(id).exceptionOrNull(), id)
            assertIs<IllegalArgumentException>(keys.exportKey(id, Path(dir, "out")).exceptionOrNull(), id)
            assertIs<IllegalArgumentException>(keys.revokeKey(id).exceptionOrNull(), id)
        }
        assertTrue(outside.exists(), "revoke must not delete a file outside the key dir")
    }

    @Test
    fun `a corrupt index is reported at warn and never overwritten`() {
        val keyDir = Path(dir, "keys").also { createDirectories(it) }
        val index = Path(keyDir, "trusted-keys.json").also { it.writeText("{not json") }
        val logger = WarnLogger()
        val keys = KeyManager(keyDir = keyDir, logger = logger)

        assertEquals(emptyList(), keys.listTrustedKeys())
        assertTrue(logger.warnings.any { "trusted keys index" in it }, logger.warnings.toString())

        assertTrue(keys.importKey(publicKeyFile()).isFailure)
        assertEquals("{not json", index.readBytes().decodeToString())
        assertFalse(keyDir.listFiles().orEmpty().any { it.name.endsWith(".pub") }, "no key file left behind")
    }
}
