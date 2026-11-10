package io.karandash.cli.commands

import com.github.ajalt.clikt.testing.test
import io.karandash.cli.TestFileHelpers
import io.karandash.cli.errors.ExitCodes
import io.karandash.crypto.SignatureVerifier
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.write
import java.security.KeyPairGenerator
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A signature written by `sign` verifies against the matching public key and fails for another key. */
class SignCommandRoundTripTest {
    private val tempDir = TestFileHelpers.createTempDir("karandash-sign-roundtrip")

    @AfterTest
    fun cleanup() = TestFileHelpers.deleteTempDir(tempDir)

    private fun rsaKeyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(KEY_BITS) }.generateKeyPair()

    @Test
    fun `signature verifies with the matching public key`() =
        runTest {
            val jar = Path(tempDir, "plugin.jar").also { TestFileHelpers.writeFile(it, "PK plugin bytes") }
            val key = Path(tempDir, "private.der")
            val pair = rsaKeyPair()
            SystemFileSystem.sink(key).buffered().use { it.write(pair.private.encoded) }
            val sig = Path(tempDir, "custom.sig")

            val result = SignCommand().test(listOf(jar.toString(), "--key", key.toString(), "--output", sig.toString()))

            assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
            assertTrue("Signature written to: $sig" in result.stdout, result.stdout)
            assertTrue(SignatureVerifier.verifySignature(jar, sig, pair.public.encoded))
            assertFalse(SignatureVerifier.verifySignature(jar, sig, rsaKeyPair().public.encoded))
        }

    @Test
    fun `signature defaults to jar path plus sig`() {
        val jar = Path(tempDir, "plugin.jar").also { TestFileHelpers.writeFile(it, "PK plugin bytes") }
        val key = Path(tempDir, "private.der")
        SystemFileSystem.sink(key).buffered().use { it.write(rsaKeyPair().private.encoded) }

        val result = SignCommand().test(listOf(jar.toString(), "--key", key.toString()))

        assertEquals(ExitCodes.SUCCESS, result.statusCode, result.output)
        assertTrue(SystemFileSystem.exists(Path("$jar.sig")))
    }

    private companion object {
        const val KEY_BITS = 2048
    }
}
