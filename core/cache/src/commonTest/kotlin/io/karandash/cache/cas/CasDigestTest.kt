package io.karandash.cache.cas

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CasDigestTest {
    private val validHex = "a".repeat(64)

    @Test
    fun validHexStringIsAccepted() {
        val digest = CasDigest(validHex)
        assertEquals(validHex, digest.hex)
    }

    @Test
    fun shortHexStringThrows() {
        assertFailsWith<IllegalArgumentException> {
            CasDigest("ab".repeat(30)) // 60 chars
        }
    }

    @Test
    fun longHexStringThrows() {
        assertFailsWith<IllegalArgumentException> {
            CasDigest("a".repeat(65))
        }
    }

    @Test
    fun nonHexStringThrows() {
        assertFailsWith<IllegalArgumentException> {
            CasDigest("g".repeat(64)) // 'g' is not hex
        }
    }

    @Test
    fun uppercaseHexStringThrows() {
        assertFailsWith<IllegalArgumentException> {
            CasDigest("A".repeat(64)) // uppercase not allowed
        }
    }

    @Test
    fun shardReturnsFirstTwoChars() {
        val digest = CasDigest("ab" + "c".repeat(62))
        assertEquals("ab", digest.shard)
    }

    @Test
    fun remainderReturnsCharsAfterShard() {
        val remainder = "c".repeat(62)
        val digest = CasDigest("ab$remainder")
        assertEquals(remainder, digest.remainder)
    }

    @Test
    fun toStringReturnsHex() {
        val digest = CasDigest(validHex)
        assertEquals(validHex, digest.toString())
    }
}
