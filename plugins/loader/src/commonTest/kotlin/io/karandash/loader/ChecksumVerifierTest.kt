package io.karandash.loader

import io.karandash.crypto.ChecksumVerifier
import io.karandash.crypto.CryptoProvider
import io.karandash.crypto.toHexString
import io.karandash.testfixtures.TestFiles
import kotlinx.io.files.Path
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChecksumVerifierTest {
    private lateinit var tempDir: Path
    private val createdFiles = mutableListOf<Path>()
    private var fileCounter = 0

    @BeforeTest
    fun setup() {
        tempDir = TestFiles.createTestTempDirectory("checksum-verifier")
        fileCounter = 0
    }

    @AfterTest
    fun cleanup() {
        // Clean up all created files
        for (file in createdFiles) {
            deleteIfExists(file)
        }
        createdFiles.clear()
    }

    private fun createFileWithContent(content: String): Path {
        val fileName = "checksum-test-${fileCounter++}.jar"
        val file = Path("$tempDir/$fileName")
        file.writeBytes(content.encodeToByteArray())
        createdFiles.add(file)
        return file
    }

    @Test
    fun `verify accepts correct checksum`() {
        val file = createFileWithContent("hello world")
        val checksum = "sha256:" + ChecksumVerifier.computeSha256(file)

        assertTrue(ChecksumVerifier.verify(file, checksum))
    }

    @Test
    fun `verify rejects incorrect checksum`() {
        val file = createFileWithContent("hello world")
        val badChecksum = "sha256:0000000000000000000000000000000000000000000000000000000000000000"

        assertFalse(ChecksumVerifier.verify(file, badChecksum))
    }

    @Test
    fun `verify rejects missing prefix`() {
        val file = createFileWithContent("hello")

        assertFailsWith<IllegalArgumentException> {
            ChecksumVerifier.verify(file, "abc123")
        }
    }

    @Test
    fun `verify rejects empty hex digest`() {
        val file = createFileWithContent("hello")

        assertFailsWith<IllegalArgumentException> {
            ChecksumVerifier.verify(file, "sha256:")
        }
    }

    @Test
    fun `verify rejects invalid hex characters`() {
        val file = createFileWithContent("hello")

        assertFailsWith<IllegalArgumentException> {
            ChecksumVerifier.verify(file, "sha256:xyz123")
        }
    }

    @Test
    fun `verify accepts uppercase hex digits`() {
        val file = createFileWithContent("test content")
        val lower = "sha256:" + ChecksumVerifier.computeSha256(file)
        val upper = "sha256:" + ChecksumVerifier.computeSha256(file).uppercase()

        // Both lowercase and uppercase should work (uppercase is normalized to lowercase)
        assertTrue(ChecksumVerifier.verify(file, lower))
        assertTrue(ChecksumVerifier.verify(file, upper))
    }

    @Test
    fun `computeSha256 produces known hash for empty file`() {
        val file = createFileWithContent("")
        val hash = ChecksumVerifier.computeSha256(file)

        // SHA-256 of empty string is well-known
        assertEquals(
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            hash
        )
    }

    @Test
    fun `computeSha256 produces known hash for hello world`() {
        val file = createFileWithContent("hello world")
        val hash = ChecksumVerifier.computeSha256(file)

        // Known SHA-256 of "hello world"
        assertEquals(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe37a5380ee9088f7ace2efcde9",
            hash
        )
    }

    @Test
    fun `verify with different file contents produces different hashes`() {
        val file1 = createFileWithContent("content A")
        val file2 = Path("$tempDir/test-file2.jar")
        file2.writeBytes("content B".encodeToByteArray())

        val hash1 = ChecksumVerifier.computeSha256(file1)
        val hash2 = ChecksumVerifier.computeSha256(file2)

        assertTrue(hash1 != hash2, "Different contents should produce different hashes")
    }

    @Test
    fun `verify works with large file`() {
        val largeContent = "x".repeat(100_000)
        val file = createFileWithContent(largeContent)
        val checksum = "sha256:" + ChecksumVerifier.computeSha256(file)

        assertTrue(ChecksumVerifier.verify(file, checksum))
    }

    // Edge Case Tests: Security and Boundary Conditions

    @Test
    fun `verify with different-length hex digest returns false`() {
        val file = createFileWithContent("test content")

        // SHA-256 produces 64 hex chars, try with 2 chars
        val result = ChecksumVerifier.verify(file, "sha256:ab")
        assertFalse(result, "Should reject wrong-length hex digest")
    }

    @Test
    fun `verify detects single character difference at end`() {
        val file = createFileWithContent("test content")
        val correctChecksum = ChecksumVerifier.computeSha256(file)

        // Change last character
        val nearMissChecksum = correctChecksum.dropLast(1) +
            if (correctChecksum.last() == 'a') 'b' else 'a'

        val result = ChecksumVerifier.verify(file, "sha256:$nearMissChecksum")
        assertFalse(result, "Should detect single char difference")
    }

    @Test
    fun `verify handles mixed case hex correctly`() {
        val file = createFileWithContent("test content")
        val checksum = ChecksumVerifier.computeSha256(file)

        // Convert to mixed case
        val mixedCase = checksum
            .mapIndexed { i, c ->
                if (i % 2 == 0) c.uppercaseChar() else c
            }.joinToString("")

        val result = ChecksumVerifier.verify(file, "sha256:$mixedCase")
        assertTrue(result, "Should handle mixed case hex")
    }

    @Test
    fun `verify works with binary file content`() {
        val fileName = "binary-test-${fileCounter++}.dat"
        val file = Path("$tempDir/$fileName")

        // Write binary data including null bytes and high-value bytes
        val binaryContent = byteArrayOf(0x00, 0xFF.toByte(), 0x7F, 0x80.toByte(), 0xAA.toByte())
        file.writeBytes(binaryContent)
        createdFiles.add(Path(file.toString()))

        val checksum = CryptoProvider.sha256(binaryContent).toHexString()
        val result = ChecksumVerifier.verify(file, "sha256:$checksum")
        assertTrue(result, "Should verify binary files correctly")
    }

    @Test
    fun `verify accepts all valid hex characters`() {
        val file = createFileWithContent("content")
        // Manually create a valid hex string with all hex chars
        val validHex = "0123456789abcdef" + "0123456789ABCDEF" + "0".repeat(32)

        // This won't match the actual checksum, but should pass hex validation
        // and fail on checksum mismatch (not hex validation)
        val result = ChecksumVerifier.verify(file, "sha256:$validHex")
        assertFalse(result, "Should fail on checksum mismatch, not hex validation")
    }

    @Test
    fun `constantTimeEquals length mismatch short-circuits - timing leak documented`() {
        // This test documents the timing leak in ChecksumVerifier.constantTimeEquals
        // The function returns early on length mismatch, allowing timing attacks
        // to determine the expected checksum length

        val file = createFileWithContent("test")

        // Different lengths should fail
        assertFalse(ChecksumVerifier.verify(file, "sha256:abc"))
        assertFalse(ChecksumVerifier.verify(file, "sha256:ab"))
        assertFalse(ChecksumVerifier.verify(file, "sha256:a"))

        // Note: In a constant-time implementation, length check should also be constant-time
        // or should pad/reject without revealing length information
    }
}
