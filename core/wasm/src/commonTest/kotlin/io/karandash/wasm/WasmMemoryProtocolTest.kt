package io.karandash.wasm

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class WasmMemoryProtocolTest {
    /** Linear memory with a bump allocator; records every dealloc as `ptr:size`. */
    private class MemoryEngine(
        private val nullAlloc: Boolean = false,
    ) : WasmEngine {
        val memory = ByteArray(1 shl 16)
        val deallocs = mutableListOf<String>()
        private val allocations = mutableListOf<Int>()

        override fun callI32Array(
            funcName: String,
            args: IntArray,
        ): Int =
            when (funcName) {
                WasmMemoryProtocol.ALLOC_FUNC -> if (nullAlloc) 0 else 8 + allocations.sum().also { allocations += args[0] + 4 }
                WasmMemoryProtocol.DEALLOC_FUNC -> 0.also { deallocs += "${args[0]}:${args[1]}" }
                else -> error("unknown export $funcName")
            }

        override fun readMemory(
            offset: Int,
            length: Int,
        ): ByteArray = memory.copyOfRange(offset, offset + length)

        override fun writeMemory(
            offset: Int,
            data: ByteArray,
        ) {
            data.copyInto(memory, offset)
        }

        override fun close() = Unit

        /** Stores [text] the way a guest returns it: a 4-byte little-endian length, then the bytes. */
        fun storeReturned(
            ptr: Int,
            length: Int,
            text: ByteArray = ByteArray(0),
        ) {
            (0 until 4).forEach { memory[ptr + it] = (length ushr (8 * it)).toByte() }
            text.copyInto(memory, ptr + 4)
        }
    }

    @Test
    fun `strings written then returned by the guest round trip and are freed`() {
        val random = Random(3)
        val alphabet = listOf("a", "Z", "0", " ", "-", "\n", "\u00e9", "\u4e2d", "\ud83d\ude00")
        repeat(100) {
            val engine = MemoryEngine()
            val text = List(random.nextInt(1, 40)) { alphabet[random.nextInt(alphabet.size)] }.joinToString("")

            val (ptr, length) = WasmMemoryProtocol.writeString(engine, text)
            assertEquals(text, engine.readMemory(ptr, length).decodeToString())

            val returned = ptr + length + 100
            engine.storeReturned(returned, length, text.encodeToByteArray())
            assertEquals(text, WasmMemoryProtocol.readLengthPrefixedString(engine, returned))
            assertEquals(listOf("$returned:${4 + length}"), engine.deallocs)
        }
    }

    @Test
    fun `a null pointer reads as empty and a null allocation fails`() {
        val engine = MemoryEngine(nullAlloc = true)

        assertEquals("", WasmMemoryProtocol.readLengthPrefixedString(engine, 0))
        assertTrue(engine.deallocs.isEmpty())
        val error = assertFailsWith<IllegalStateException> { WasmMemoryProtocol.writeString(engine, "abc") }
        assertEquals("karandash_alloc returned null for size 3", error.message)
    }

    @Test
    fun `empty negative and oversized lengths free the prefix only`() {
        val engine = MemoryEngine()
        engine.storeReturned(16, 0)
        engine.storeReturned(32, -5)
        engine.storeReturned(48, 10 * 1024 * 1024 + 1)

        assertEquals("", WasmMemoryProtocol.readLengthPrefixedString(engine, 16))
        assertEquals("", WasmMemoryProtocol.readLengthPrefixedString(engine, 32))
        val error = assertFailsWith<IllegalStateException> { WasmMemoryProtocol.readLengthPrefixedString(engine, 48) }
        assertTrue(error.message.orEmpty().contains("exceeds maximum"), error.message)
        assertEquals(listOf("16:4", "32:4", "48:4"), engine.deallocs)
    }

    @Test
    fun `timed engine passes memory access through until abandoned`() {
        val delegate = MemoryEngine()
        val engine = TimedWasmEngine(delegate, GuestCallRunner(), 5.seconds) {}

        val (ptr, length) = WasmMemoryProtocol.writeString(engine, "hello")

        assertEquals("hello", engine.readMemory(ptr, length).decodeToString())
        assertFalse(engine.isAbandoned)
        engine.close()
    }

    @Test
    fun `an untimed engine is never abandoned and invalid bytes fail creation`() {
        val engine = createWasmEngine(WasmTestModules.spinAndAnswer())

        assertEquals(42, engine.callI32("answer"))
        assertFalse(engine.isAbandoned)
        engine.interrupt()
        engine.close()
        assertFails { createWasmEngine(byteArrayOf(1, 2, 3), 5.seconds) }
    }
}
