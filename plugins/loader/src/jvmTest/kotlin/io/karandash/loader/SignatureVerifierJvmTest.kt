package io.karandash.loader

import io.karandash.crypto.KeyManager
import io.karandash.crypto.SignatureVerifier
import io.karandash.crypto.findSignatureFile
import io.karandash.crypto.signJar
import io.karandash.crypto.verifyJar
import io.karandash.crypto.verifySignature
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import org.junit.After
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.security.KeyPairGenerator
import java.security.spec.RSAKeyGenParameterSpec
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureVerifierJvmTest {
    private lateinit var tempDir: File

    @Before
    fun setup() {
        tempDir = java.io.File(TestFiles.createTestTempDirectory("signature-verifier-test").toString())
    }

    @After
    fun cleanup() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `signJar creates signature file`() {
        val keyPair = generateTestKeyPair()
        val jarPath = createTestJar("test-plugin.jar")

        val result = SignatureVerifier.signJar(jarPath, keyPair.private)

        assertTrue(result.isSuccess, "Signing should succeed")
        val sigPath = result.getOrThrow()
        assertTrue(Files.exists(sigPath), "Signature file should exist")
        assertEquals("${jarPath.fileName}.sig", sigPath.fileName.toString())
    }

    @Test
    fun `signJar with custom output path`() {
        val keyPair = generateTestKeyPair()
        val jarPath = createTestJar("test-plugin.jar")
        val customOutput = tempDir.resolve("custom-signature.sig").toPath()

        val result = SignatureVerifier.signJar(jarPath, keyPair.private, customOutput)

        assertTrue(result.isSuccess)
        val sigPath = result.getOrThrow()
        assertEquals(customOutput, sigPath)
        assertTrue(Files.exists(customOutput))
    }

    @Test
    fun `signJar fails with non-existent JAR`() {
        val keyPair = generateTestKeyPair()
        val missingJar = tempDir.resolve("missing.jar").toPath()

        val result = SignatureVerifier.signJar(missingJar, keyPair.private)

        assertTrue(result.isFailure, "Should fail with non-existent JAR")
    }

    @Test
    fun `sign and verify round-trip with Path API`() =
        runTest {
            val keyPair = generateTestKeyPair()
            val jarPath = createTestJar("test-plugin.jar")

            val sigResult = SignatureVerifier.signJar(jarPath, keyPair.private)
            assertTrue(sigResult.isSuccess)
            val sigPath = sigResult.getOrThrow()

            val isValid = SignatureVerifier.verifySignature(jarPath, sigPath, keyPair.public)
            assertTrue(isValid, "Signature should be valid")
        }

    @Test
    fun `sign and verify fails with wrong key via Path API`() =
        runTest {
            val keyPair1 = generateTestKeyPair()
            val keyPair2 = generateTestKeyPair()
            val jarPath = createTestJar("test-plugin.jar")

            val sigResult = SignatureVerifier.signJar(jarPath, keyPair1.private)
            assertTrue(sigResult.isSuccess)
            val sigPath = sigResult.getOrThrow()

            val isValid = SignatureVerifier.verifySignature(jarPath, sigPath, keyPair2.public)
            assertFalse(isValid, "Signature should be invalid with wrong key")
        }

    @Test
    fun `sign and verify fails with tampered JAR`() =
        runTest {
            val keyPair = generateTestKeyPair()
            val jarPath = createTestJar("test-plugin.jar")

            val sigResult = SignatureVerifier.signJar(jarPath, keyPair.private)
            assertTrue(sigResult.isSuccess)
            val sigPath = sigResult.getOrThrow()

            // Tamper with the JAR
            Files.write(jarPath, "tampered content".toByteArray())

            val isValid = SignatureVerifier.verifySignature(jarPath, sigPath, keyPair.public)
            assertFalse(isValid, "Signature should be invalid after tampering")
        }

    @Test
    fun `findSignatureFile locates signature via Path`() {
        val jarPath = createTestJar("test-plugin.jar")
        val sigPath = tempDir.resolve("test-plugin.jar.sig").toPath()
        Files.write(sigPath, "signature content".toByteArray())

        val found = SignatureVerifier.findSignatureFile(jarPath)

        assertNotNull(found, "Should find signature file")
        assertEquals(sigPath, found)
    }

    @Test
    fun `findSignatureFile returns null when signature missing via Path`() {
        val jarPath = createTestJar("test-plugin.jar")

        val found = SignatureVerifier.findSignatureFile(jarPath)

        assertNull(found, "Should return null when signature not found")
    }

    @Test
    fun `verifyJar succeeds when JAR signed with trusted key`() =
        runTest {
            val keyPair = generateTestKeyPair()
            val jarPath = createTestJar("test-plugin.jar")
            val keyManager = KeyManager(tempDir.toPath().toKotlinPath())

            // Import public key
            val pubKeyFile = tempDir.resolve("public.key")
            pubKeyFile.writeBytes(keyPair.public.encoded)
            keyManager.importKey(pubKeyFile.toPath().toKotlinPath(), "Test key")

            // Sign JAR
            SignatureVerifier.signJar(jarPath, keyPair.private)

            // Verify
            val result = SignatureVerifier.verifyJar(jarPath, keyManager)

            assertTrue(result.isSuccess, "Verification should succeed")
            assertTrue(result.getOrThrow(), "Should return true for valid signature")
        }

    @Test
    fun `verifyJar fails when JAR unsigned`() =
        runTest {
            val jarPath = createTestJar("test-plugin.jar")
            val keyManager = KeyManager(tempDir.toPath().toKotlinPath())

            val result = SignatureVerifier.verifyJar(jarPath, keyManager)

            assertTrue(result.isFailure, "Should fail for unsigned JAR")
            assertContains(
                result.exceptionOrNull()?.message ?: "",
                "No signature file",
                ignoreCase = true
            )
        }

    @Test
    fun `verifyJar fails when no trusted keys configured`() =
        runTest {
            val keyPair = generateTestKeyPair()
            val jarPath = createTestJar("test-plugin.jar")
            val keyManager = KeyManager(tempDir.toPath().toKotlinPath())

            // Sign JAR but don't import the public key
            SignatureVerifier.signJar(jarPath, keyPair.private)

            val result = SignatureVerifier.verifyJar(jarPath, keyManager)

            assertTrue(result.isFailure, "Should fail with no trusted keys")
            assertContains(
                result.exceptionOrNull()?.message ?: "",
                "No trusted keys",
                ignoreCase = true
            )
        }

    @Test
    fun `verifyJar fails when signed with untrusted key`() =
        runTest {
            val keyPair1 = generateTestKeyPair()
            val keyPair2 = generateTestKeyPair()
            val jarPath = createTestJar("test-plugin.jar")
            val keyManager = KeyManager(tempDir.toPath().toKotlinPath())

            // Import keyPair1's public key
            val pubKeyFile = tempDir.resolve("public.key")
            pubKeyFile.writeBytes(keyPair1.public.encoded)
            keyManager.importKey(pubKeyFile.toPath().toKotlinPath(), "Trusted key")

            // Sign JAR with keyPair2 (untrusted)
            SignatureVerifier.signJar(jarPath, keyPair2.private)

            val result = SignatureVerifier.verifyJar(jarPath, keyManager)

            assertTrue(result.isFailure, "Should fail with untrusted signing key")
            assertContains(
                result.exceptionOrNull()?.message ?: "",
                "no matching trusted key",
                ignoreCase = true
            )
        }

    @Test
    fun `verifyJar succeeds when multiple trusted keys and one matches`() =
        runTest {
            val keyPair1 = generateTestKeyPair()
            val keyPair2 = generateTestKeyPair()
            val jarPath = createTestJar("test-plugin.jar")
            val keyManager = KeyManager(tempDir.toPath().toKotlinPath())

            // Import multiple trusted keys
            val pubKeyFile1 = tempDir.resolve("public1.key")
            pubKeyFile1.writeBytes(keyPair1.public.encoded)
            keyManager.importKey(pubKeyFile1.toPath().toKotlinPath(), "Key 1")

            val pubKeyFile2 = tempDir.resolve("public2.key")
            pubKeyFile2.writeBytes(keyPair2.public.encoded)
            keyManager.importKey(pubKeyFile2.toPath().toKotlinPath(), "Key 2")

            // Sign JAR with keyPair2
            SignatureVerifier.signJar(jarPath, keyPair2.private)

            val result = SignatureVerifier.verifyJar(jarPath, keyManager)

            assertTrue(result.isSuccess, "Should succeed when one key matches")
            assertTrue(result.getOrThrow())
        }

    private fun createTestJar(name: String): java.nio.file.Path {
        val jarPath = tempDir.resolve(name).toPath()
        val content = """
            PK\u0003\u0004
            This is a fake JAR file for testing purposes.
            It contains some binary-like content to simulate a real JAR.
        """.trimIndent()
        Files.write(jarPath, content.toByteArray())
        return jarPath
    }

    private fun generateTestKeyPair(): java.security.KeyPair {
        val keyGen = KeyPairGenerator.getInstance("RSA")
        keyGen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
        return keyGen.generateKeyPair()
    }
}
