package io.karandash.loader

import io.karandash.crypto.SignatureVerifier
import io.karandash.testfixtures.TestFiles
import kotlinx.coroutines.test.runTest
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SignatureVerifierTest {
    private lateinit var tempDir: Path
    private val createdFiles = mutableListOf<Path>()
    private var fileCounter = 0

    // RSA-2048 public key in X.509 DER (SubjectPublicKeyInfo) format, hex-encoded
    private val testPublicKeyDerHex =
        "30820122300d06092a864886f70d01010105000382010f003082010a0282010100" +
            "af44f30b34e1b0a334a8f9ef736a4921e0d5ea7ddaced2d53d8e3a2ad76db7db" +
            "a1f33665d1811eac5d5108204b9e8931bf0f0b2b5fe6f3edf6a094c4ea1fe1d8" +
            "8e1651c971660d19c60fba0c5b91c8570f9f75d64c047cc15e5d835640569cfc" +
            "8f1bb3cd5f5044ecafdb58333ce214f989a59830aa0ade3beadd4629b85b74ae" +
            "2ca8e8198f63ddc0794c085a59a7140d5e0b53253c1f3761b1bfd867bf0e7182" +
            "34e553d977dae0be6309154f26215a6e5c94e26dfa449a39e2e68825897969d2" +
            "d70f84021bd9318f34e9d7f7ffb2bb022cc38701969363638a890c54ccddd4a5" +
            "c2ae4ec7fb66f91872ad3401a428fc25efe0e510e7fa8d5aaa4f4462582c8785" +
            "0203010001"

    // Signature of "test data for signing\n" using the above key (SHA256withRSA)
    private val testSignatureHex =
        "9697136808267d83f212a41980d34c1cdaa196cef1a7922c18cdb7071fbd0c40" +
            "a9e47cdf85238b708996c07fed2c3861a0b27a3adfeae295a4c82ae6897c8208" +
            "688e56d25ff51685b1de68e4de8087db64c4eb8d4abe33c68a56afb78c7a99d5" +
            "fdc54af37814a87afdac90146c47bb45ef8c5d45f209263ddfa126818813b209" +
            "d7482c71a0db5e7f802541d123fd2b9446bc59c5da3c38a69ce53be097aa9ac8" +
            "c99e7ab3e12abd6be5c5e44839bc3da09de84c7c78755d95be788ce5c243b504" +
            "9067bad98d0efbb803d819bcc0496e1b7e0d5ef19a37308ddda734b30e4efe1c" +
            "64affa5e21062914a4132a3e87d5d351c36912dd6aa8efb5091d20cc66e0fdf3"

    // The signed data: "test data for signing\n" (with trailing newline)
    private val testDataHex = "74657374206461746120666f72207369676e696e670a"

    // A different public key (first byte of modulus changed) to test wrong-key scenario
    private val wrongPublicKeyDerHex =
        "30820122300d06092a864886f70d01010105000382010f003082010a0282010100" +
            "bf44f30b34e1b0a334a8f9ef736a4921e0d5ea7ddaced2d53d8e3a2ad76db7db" +
            "a1f33665d1811eac5d5108204b9e8931bf0f0b2b5fe6f3edf6a094c4ea1fe1d8" +
            "8e1651c971660d19c60fba0c5b91c8570f9f75d64c047cc15e5d835640569cfc" +
            "8f1bb3cd5f5044ecafdb58333ce214f989a59830aa0ade3beadd4629b85b74ae" +
            "2ca8e8198f63ddc0794c085a59a7140d5e0b53253c1f3761b1bfd867bf0e7182" +
            "34e553d977dae0be6309154f26215a6e5c94e26dfa449a39e2e68825897969d2" +
            "d70f84021bd9318f34e9d7f7ffb2bb022cc38701969363638a890c54ccddd4a5" +
            "c2ae4ec7fb66f91872ad3401a428fc25efe0e510e7fa8d5aaa4f4462582c8785" +
            "0203010001"

    private fun hexToByteArray(hex: String): ByteArray {
        val len = hex.length
        require(len % 2 == 0) { "Hex string must have even length" }
        return ByteArray(len / 2) { i ->
            val hi = hexCharToInt(hex[i * 2])
            val lo = hexCharToInt(hex[i * 2 + 1])
            ((hi shl 4) or lo).toByte()
        }
    }

    private fun hexCharToInt(c: Char): Int =
        when (c) {
            in '0'..'9' -> c - '0'
            in 'a'..'f' -> c - 'a' + 10
            in 'A'..'F' -> c - 'A' + 10
            else -> error("Invalid hex char: $c")
        }

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("signature-verifier")
        fileCounter = 0
    }

    @AfterTest
    fun cleanup() {
        for (file in createdFiles) {
            deleteIfExists(file)
        }
        createdFiles.clear()
    }

    private fun createFile(
        name: String,
        content: ByteArray
    ): Path {
        val file = Path("$tempDir/sig-test-${fileCounter++}-$name")
        file.writeBytes(content)
        createdFiles.add(Path(file.toString()))
        return file
    }

    // --- verifySignature tests ---

    @Test
    fun verifySignature_succeeds_with_valid_signature() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val signature = hexToByteArray(testSignatureHex)
            val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

            val jarFile = createFile("test.jar", data)
            val sigFile = createFile("test.jar.sig", signature)

            val result = SignatureVerifier.verifySignature(jarFile, sigFile, publicKeyDer)
            assertTrue(result, "Signature should be valid")
        }

    @Test
    fun verifySignature_fails_with_wrong_public_key() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val signature = hexToByteArray(testSignatureHex)
            val wrongKeyDer = hexToByteArray(wrongPublicKeyDerHex)

            val jarFile = createFile("test.jar", data)
            val sigFile = createFile("test.jar.sig", signature)

            val result = SignatureVerifier.verifySignature(jarFile, sigFile, wrongKeyDer)
            assertFalse(result, "Signature should be invalid with wrong key")
        }

    @Test
    fun verifySignature_fails_with_tampered_data() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val tamperedData = data.copyOf()
            tamperedData[0] = (tamperedData[0].toInt() xor 0xFF).toByte()
            val signature = hexToByteArray(testSignatureHex)
            val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

            val jarFile = createFile("test.jar", tamperedData)
            val sigFile = createFile("test.jar.sig", signature)

            val result = SignatureVerifier.verifySignature(jarFile, sigFile, publicKeyDer)
            assertFalse(result, "Signature should be invalid after tampering")
        }

    @Test
    fun verifySignature_fails_with_missing_jar() =
        runTest {
            val signature = hexToByteArray(testSignatureHex)
            val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

            val missingJar = Path("$tempDir/sig-test-missing.jar")
            val sigFile = createFile("missing.jar.sig", signature)

            val result = SignatureVerifier.verifySignature(missingJar, sigFile, publicKeyDer)
            assertFalse(result, "Should fail with missing JAR")
        }

    @Test
    fun verifySignature_fails_with_missing_signature_file() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

            val jarFile = createFile("test.jar", data)
            val missingSig = Path("$tempDir/sig-test-missing.sig")

            val result = SignatureVerifier.verifySignature(jarFile, missingSig, publicKeyDer)
            assertFalse(result, "Should fail with missing signature")
        }

    // --- findSignatureFile tests ---

    @Test
    fun findSignatureFile_locates_signature() {
        val jarFile = createFile("test-plugin.jar", "jar content".encodeToByteArray())
        // Create signature file as sibling
        val sigFile = Path("$tempDir/${jarFile.name}.sig")
        sigFile.writeBytes("signature content".encodeToByteArray())
        createdFiles.add(Path(sigFile.toString()))

        val found = SignatureVerifier.findSignatureFile(jarFile)

        assertNotNull(found, "Should find signature file")
        assertEquals(sigFile.toString(), found.toString())
    }

    @Test
    fun findSignatureFile_returns_null_when_signature_missing() {
        val jarFile = createFile("test-plugin.jar", "jar content".encodeToByteArray())

        val found = SignatureVerifier.findSignatureFile(jarFile)

        assertNull(found, "Should return null when signature not found")
    }

    // --- verifyJar tests ---

    @Test
    fun verifyJar_succeeds_with_matching_key() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val signature = hexToByteArray(testSignatureHex)
            val publicKeyDer = hexToByteArray(testPublicKeyDerHex)

            val jarFile = createFile("verified.jar", data)
            // Create signature file as sibling: <jar-name>.sig
            val sigFile = Path("$tempDir/${jarFile.name}.sig")
            sigFile.writeBytes(signature)
            createdFiles.add(Path(sigFile.toString()))

            val result = SignatureVerifier.verifyJar(jarFile, listOf(publicKeyDer))
            assertTrue(result.isSuccess, "Verification should succeed")
            assertTrue(result.getOrThrow(), "Should return true for valid signature")
        }

    @Test
    fun verifyJar_fails_when_unsigned() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val jarFile = createFile("unsigned.jar", data)

            val publicKeyDer = hexToByteArray(testPublicKeyDerHex)
            val result = SignatureVerifier.verifyJar(jarFile, listOf(publicKeyDer))

            assertTrue(result.isFailure, "Should fail for unsigned JAR")
            assertTrue(
                result.exceptionOrNull()?.message?.contains("No signature file", ignoreCase = true) == true,
                "Error should mention missing signature"
            )
        }

    @Test
    fun verifyJar_fails_when_no_trusted_keys() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val signature = hexToByteArray(testSignatureHex)

            val jarFile = createFile("nokeys.jar", data)
            val sigFile = Path("$tempDir/${jarFile.name}.sig")
            sigFile.writeBytes(signature)
            createdFiles.add(Path(sigFile.toString()))

            val result = SignatureVerifier.verifyJar(jarFile, emptyList())

            assertTrue(result.isFailure, "Should fail with no trusted keys")
            assertTrue(
                result.exceptionOrNull()?.message?.contains("No trusted keys", ignoreCase = true) == true,
                "Error should mention no trusted keys"
            )
        }

    @Test
    fun verifyJar_fails_when_signed_with_untrusted_key() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val signature = hexToByteArray(testSignatureHex)
            val wrongKeyDer = hexToByteArray(wrongPublicKeyDerHex)

            val jarFile = createFile("untrusted.jar", data)
            val sigFile = Path("$tempDir/${jarFile.name}.sig")
            sigFile.writeBytes(signature)
            createdFiles.add(Path(sigFile.toString()))

            val result = SignatureVerifier.verifyJar(jarFile, listOf(wrongKeyDer))

            assertTrue(result.isFailure, "Should fail with untrusted signing key")
            assertTrue(
                result.exceptionOrNull()?.message?.contains("no matching trusted key", ignoreCase = true) == true,
                "Error should mention no matching trusted key"
            )
        }

    @Test
    fun verifyJar_succeeds_when_multiple_keys_and_one_matches() =
        runTest {
            val data = hexToByteArray(testDataHex)
            val signature = hexToByteArray(testSignatureHex)
            val publicKeyDer = hexToByteArray(testPublicKeyDerHex)
            val wrongKeyDer = hexToByteArray(wrongPublicKeyDerHex)

            val jarFile = createFile("multikey.jar", data)
            val sigFile = Path("$tempDir/${jarFile.name}.sig")
            sigFile.writeBytes(signature)
            createdFiles.add(Path(sigFile.toString()))

            val result = SignatureVerifier.verifyJar(jarFile, listOf(wrongKeyDer, publicKeyDer))

            assertTrue(result.isSuccess, "Should succeed when one key matches")
            assertTrue(result.getOrThrow())
        }
}
