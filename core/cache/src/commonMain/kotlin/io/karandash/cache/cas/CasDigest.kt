package io.karandash.cache.cas

import io.karandash.crypto.CryptoProvider
import io.karandash.crypto.toHexString

/**
 * Content-addressable digest - SHA-256 hash of the blob content.
 *
 * Same content always produces the same digest, enabling lockless
 * concurrent writes (idempotent) and integrity-by-design reads.
 *
 * @property hex 64-character lowercase hex string.
 */
@kotlin.jvm.JvmInline
value class CasDigest(
    val hex: String
) {
    init {
        require(hex.length == HEX_LENGTH && hex.all { it in '0'..'9' || it in 'a'..'f' }) {
            "CasDigest must be $HEX_LENGTH lowercase hex characters"
        }
    }

    /** First two hex chars - used as shard directory name for filesystem distribution. */
    val shard: String get() = hex.substring(0, SHARD_LENGTH)

    /** Remaining hex chars - used as blob filename within the shard directory. */
    val remainder: String get() = hex.substring(SHARD_LENGTH)

    override fun toString(): String = hex

    companion object {
        private const val HEX_LENGTH = 64
        private const val SHARD_LENGTH = 2

        /** Compute the CAS digest for the given content bytes. */
        fun of(content: ByteArray): CasDigest = CasDigest(CryptoProvider.sha256(content).toHexString())
    }
}
