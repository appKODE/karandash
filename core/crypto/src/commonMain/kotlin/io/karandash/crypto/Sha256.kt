package io.karandash.crypto

/**
 * Compute SHA-256 for a UTF-8 string and return lowercase hex.
 */
fun sha256Hex(input: String): String = CryptoProvider.sha256(input.encodeToByteArray()).toHexString()

/** SHA-1 of a UTF-8 string as lowercase hex (GitLab names diff files and lines by it). */
fun sha1Hex(input: String): String = CryptoProvider.sha1(input.encodeToByteArray()).toHexString()

/**
 * Compute SHA-256 of [content] and return the first 64 bits as a [Long].
 *
 * This provides collision resistance far superior to 32-bit `hashCode()`.
 * At production CI volumes (50 MRs/day x 15 facets x 365 days = 273K pairs),
 * 64-bit hash collision probability is ~2e-12 vs ~100% for 32-bit.
 *
 * Used by the incremental validation state layer for facet and file hashing.
 *
 * @param content The string to hash.
 * @return First 8 bytes of SHA-256 digest interpreted as a big-endian Long.
 */
fun sha256First64Bits(content: String): Long {
    val bytes = CryptoProvider.sha256(content.encodeToByteArray())
    var result = 0L
    for (i in 0 until LONG_BYTE_SIZE) {
        result = (result shl Byte.SIZE_BITS) or (bytes[i].toLong() and BYTE_MASK)
    }
    return result
}

private const val LONG_BYTE_SIZE = 8
private const val BYTE_MASK = 0xFFL
