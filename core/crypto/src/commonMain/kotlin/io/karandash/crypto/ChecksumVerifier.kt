package io.karandash.crypto

import io.karandash.platform.readBytes
import kotlinx.io.files.Path

/**
 * Verifies file integrity using SHA-256 checksums.
 *
 * Checksum format: `sha256:<hex-digest>`
 *
 * Uses constant-time comparison to prevent timing attacks.
 */
object ChecksumVerifier {
    private const val PREFIX = "sha256:"
    private const val CHECKSUM_ERROR_PREVIEW_LENGTH = 20

    /**
     * Verify that a file matches the expected checksum.
     *
     * @param file File path to verify.
     * @param expectedChecksum Checksum in format "sha256:hexdigest".
     * @return true if the file's SHA-256 digest matches.
     * @throws IllegalArgumentException if the checksum format is invalid.
     */
    fun verify(
        file: Path,
        expectedChecksum: String,
    ): Boolean {
        require(expectedChecksum.startsWith(PREFIX)) {
            "Checksum must start with '$PREFIX', got: ${expectedChecksum.take(CHECKSUM_ERROR_PREVIEW_LENGTH)}"
        }

        val expectedHex = expectedChecksum.removePrefix(PREFIX).lowercase()
        require(expectedHex.isNotEmpty()) {
            "Checksum hex digest must not be empty"
        }
        require(expectedHex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "Checksum hex digest contains invalid characters"
        }

        val actualHex = computeSha256(file)
        return constantTimeEquals(expectedHex, actualHex)
    }

    /**
     * Compute the SHA-256 hex digest of a file.
     *
     * @param file File path.
     * @return Lowercase hex string of the SHA-256 digest.
     */
    fun computeSha256(file: Path): String {
        val data = file.readBytes()
        val digest = CryptoProvider.sha256(data)
        return digest.toHexString()
    }

    /**
     * Constant-time string comparison to prevent timing attacks.
     *
     * Always compares all characters regardless of mismatch position.
     */
    private fun constantTimeEquals(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }
}
