package io.karandash.cli.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PlatformHashTest {
    @Test
    fun `sha256Hex produces deterministic output`() {
        val input = "test"
        val hash1 = PlatformHash.sha256Hex(input)
        val hash2 = PlatformHash.sha256Hex(input)

        assertEquals(hash1, hash2, "Same input should produce same hash")
    }

    @Test
    fun `sha256Hex returns lowercase hex string`() {
        val hash = PlatformHash.sha256Hex("test")

        assertTrue(
            hash.all { it in '0'..'9' || it in 'a'..'f' },
            "Hash should only contain lowercase hex characters"
        )
    }

    @Test
    fun `sha256Hex handles empty string`() {
        val hash = PlatformHash.sha256Hex("")

        assertTrue(hash.isNotEmpty(), "Hash of empty string should not be empty")
        assertTrue(
            hash.all { it in '0'..'9' || it in 'a'..'f' },
            "Hash should be valid hex"
        )
    }

    @Test
    fun `sha256Hex produces different hashes for different inputs`() {
        val hash1 = PlatformHash.sha256Hex("test1")
        val hash2 = PlatformHash.sha256Hex("test2")

        assertTrue(hash1 != hash2, "Different inputs should produce different hashes")
    }

    @Test
    fun `sha256Hex handles unicode characters`() {
        val hash = PlatformHash.sha256Hex("Hello 世界 🌍")

        assertTrue(hash.isNotEmpty(), "Hash should handle unicode")
        assertTrue(
            hash.all { it in '0'..'9' || it in 'a'..'f' },
            "Hash should be valid hex"
        )
    }
}
