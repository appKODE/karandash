package io.karandash.crypto

/**
 * Platform-independent hex encoding for byte arrays.
 * Avoids "%02x".format() which behaves differently on Kotlin/Native.
 */
private val HEX_CHARS = charArrayOf(
    '0',
    '1',
    '2',
    '3',
    '4',
    '5',
    '6',
    '7',
    '8',
    '9',
    'a',
    'b',
    'c',
    'd',
    'e',
    'f'
)

/**
 * Convert a byte array to a lowercase hex string.
 */
fun ByteArray.toHexString(): String {
    val result = CharArray(size * 2)
    for (i in indices) {
        val v = this[i].toInt() and 0xFF
        result[i * 2] = HEX_CHARS[v ushr 4]
        result[i * 2 + 1] = HEX_CHARS[v and 0x0F]
    }
    return result.concatToString()
}
