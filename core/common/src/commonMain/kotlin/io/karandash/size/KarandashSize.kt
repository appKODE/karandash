package io.karandash.size

import kotlinx.serialization.Serializable

/**
 * A domain size type backed by bytes.
 *
 * Parses human-readable strings like `"10mb"`, `"1gb"`, `"512kb"`, `"1024b"`, `"1tb"`.
 * Use [parse] to construct from config strings. Use the factory functions ([ofBytes], [ofKb],
 * etc.) for programmatic construction.
 */
@Serializable(with = KarandashSizeSerializer::class)
@kotlin.jvm.JvmInline
value class KarandashSize private constructor(
    /** Raw bytes. */
    val bytes: Long,
) : Comparable<KarandashSize> {
    /** Size expressed in whole kilobytes (truncated). */
    val kb: Long get() = bytes / BYTES_PER_KB

    /** Size expressed in whole megabytes (truncated). */
    val mb: Long get() = bytes / BYTES_PER_MB

    /** Size expressed in whole gigabytes (truncated). */
    val gb: Long get() = bytes / BYTES_PER_GB

    override fun compareTo(other: KarandashSize): Int = bytes.compareTo(other.bytes)

    override fun toString(): String = formatHumanReadable(bytes)

    companion object {
        private const val BYTES_PER_KB = 1_024L
        private const val BYTES_PER_MB = 1_048_576L
        private const val BYTES_PER_GB = 1_073_741_824L

        /** Zero size. */
        val ZERO: KarandashSize = KarandashSize(0L)

        /** Constructs from raw bytes. */
        fun ofBytes(b: Long): KarandashSize = KarandashSize(b)

        /** Constructs from whole kilobytes. */
        fun ofKb(kb: Long): KarandashSize = KarandashSize(kb * BYTES_PER_KB)

        /** Constructs from whole megabytes. */
        fun ofMb(mb: Long): KarandashSize = KarandashSize(mb * BYTES_PER_MB)

        /** Constructs from whole gigabytes. */
        fun ofGb(gb: Long): KarandashSize = KarandashSize(gb * BYTES_PER_GB)

        /**
         * Parses a human-readable size string.
         *
         * Supported units: `b`, `kb`, `mb`, `gb`, `tb`.
         *
         * @throws IllegalStateException if the input is invalid or a bare number without a unit.
         */
        fun parse(input: String): KarandashSize = parseSize(input)
    }
}
