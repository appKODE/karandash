package io.karandash.validators

import kotlin.test.Test
import kotlin.test.assertEquals

/** byte-based maxDiffSize without the encode allocation. */
class Utf8LengthTest {
    @Test
    fun `utf8 byte length matches the encoder for every width`() {
        val samples = listOf("", "abc", "я", "€uro", "😀 smile")
        samples.forEach { assertEquals(it.encodeToByteArray().size, it.utf8ByteLength(), it) }
    }
}
