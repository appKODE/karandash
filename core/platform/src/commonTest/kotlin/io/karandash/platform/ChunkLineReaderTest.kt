package io.karandash.platform

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChunkLineReaderTest {
    private fun reader(vararg chunks: ByteArray): ChunkLineReader {
        val queue = ArrayDeque(chunks.toList())
        return ChunkLineReader { target ->
            val next = queue.removeFirstOrNull() ?: return@ChunkLineReader -1
            next.copyInto(target)
            next.size
        }
    }

    private fun textReader(vararg chunks: String) = reader(*chunks.map { it.encodeToByteArray() }.toTypedArray())

    @Test
    fun `lines split across chunks are joined and carriage returns dropped`() {
        val r = textReader("ab", "c\r\nde", "f\n\ng")
        assertEquals("abc", r.readLine())
        assertEquals("def", r.readLine())
        assertEquals("", r.readLine())
        assertEquals("g", r.readLine())
        assertNull(r.readLine())
    }

    @Test
    fun `an empty stream gives no line`() {
        assertNull(reader().readLine())
    }

    @Test
    fun `multi-byte characters survive a chunk boundary`() {
        val bytes = "é\n".encodeToByteArray()
        val r = reader(bytes.copyOfRange(0, 1), bytes.copyOfRange(1, bytes.size))
        assertEquals("é", r.readLine())
    }
}
