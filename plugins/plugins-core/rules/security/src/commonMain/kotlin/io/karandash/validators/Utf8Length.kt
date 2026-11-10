package io.karandash.validators

private const val ONE_BYTE_LIMIT = 0x80
private const val TWO_BYTE_LIMIT = 0x800
private const val SURROGATE_PAIR_BYTES = 4
private const val THREE_BYTES = 3

/**
 * Length of this string in UTF-8 bytes, equal to `encodeToByteArray().size` without allocating the array.
 * An unpaired surrogate counts as three bytes (U+FFFD); the JVM encoder writes `?`, so this can overcount by two there.
 */
internal fun String.utf8ByteLength(): Int {
    var bytes = 0
    var i = 0
    while (i < length) {
        val c = this[i]
        bytes += when {
            c.code < ONE_BYTE_LIMIT -> {
                1
            }

            c.code < TWO_BYTE_LIMIT -> {
                2
            }

            c.isHighSurrogate() && i + 1 < length && this[i + 1].isLowSurrogate() -> {
                i++
                SURROGATE_PAIR_BYTES
            }

            c.isSurrogate() -> {
                1
            }

            else -> {
                THREE_BYTES
            }
        }
        i++
    }
    return bytes
}
