package io.karandash.loader.wasm

import io.karandash.wasm.WasmEngine
import io.karandash.wasm.WasmMemoryProtocol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WasmMemoryProtocolTest {
    private class FakeWasmEngine : WasmEngine {
        val memory = ByteArray(4096)
        var nextAllocPtr = 100
        val calls = mutableListOf<Pair<String, List<Int>>>()

        override fun callI32Array(
            funcName: String,
            args: IntArray
        ): Int {
            calls.add(funcName to args.toList())
            return when (funcName) {
                WasmAbi.ALLOC -> {
                    val ptr = nextAllocPtr
                    nextAllocPtr += args[0] + 16
                    ptr
                }

                WasmAbi.DEALLOC -> {
                    0
                }

                else -> {
                    0
                }
            }
        }

        override fun readMemory(
            offset: Int,
            length: Int
        ): ByteArray = memory.copyOfRange(offset, offset + length)

        override fun writeMemory(
            offset: Int,
            data: ByteArray
        ) {
            data.copyInto(memory, offset)
        }

        override fun close() {}
    }

    @Test
    fun writeString_allocates_and_writes_utf8_bytes() {
        val engine = FakeWasmEngine()
        val (ptr, len) = WasmMemoryProtocol.writeString(engine, "hello")
        assertEquals(5, len)
        val written = engine.readMemory(ptr, len).decodeToString()
        assertEquals("hello", written)
    }

    @Test
    fun readLengthPrefixedString_decodes_4_byte_le_length_prefix() {
        val engine = FakeWasmEngine()
        val data = "world".encodeToByteArray()
        val ptr = 200
        // Write 4-byte LE length then data
        val lenBytes = byteArrayOf(
            (data.size and 0xFF).toByte(),
            ((data.size shr 8) and 0xFF).toByte(),
            ((data.size shr 16) and 0xFF).toByte(),
            ((data.size shr 24) and 0xFF).toByte(),
        )
        lenBytes.copyInto(engine.memory, ptr)
        data.copyInto(engine.memory, ptr + 4)

        val result = WasmMemoryProtocol.readLengthPrefixedString(engine, ptr)
        assertEquals("world", result)
    }

    @Test
    fun readLengthPrefixedString_returns_empty_for_null_ptr() {
        val engine = FakeWasmEngine()
        val result = WasmMemoryProtocol.readLengthPrefixedString(engine, 0)
        assertEquals("", result)
    }

    @Test
    fun writeString_handles_empty_string() {
        val engine = FakeWasmEngine()
        val (_, len) = WasmMemoryProtocol.writeString(engine, "")
        assertEquals(0, len)
        // Alloc is still called with size 0
        assertEquals(WasmAbi.ALLOC, engine.calls.first().first)
    }

    @Test
    fun readLengthPrefixedString_returns_empty_for_zero_length() {
        val engine = FakeWasmEngine()
        val ptr = 300
        // Write zero length
        val lenBytes = byteArrayOf(0, 0, 0, 0)
        lenBytes.copyInto(engine.memory, ptr)

        val result = WasmMemoryProtocol.readLengthPrefixedString(engine, ptr)
        assertEquals("", result)
    }

    @Test
    fun writeString_records_alloc_call_with_correct_size() {
        val engine = FakeWasmEngine()
        WasmMemoryProtocol.writeString(engine, "test")
        val allocCall = engine.calls.first { it.first == WasmAbi.ALLOC }
        assertEquals(listOf(4), allocCall.second)
    }

    @Test
    fun readLengthPrefixedString_calls_dealloc_after_reading() {
        val engine = FakeWasmEngine()
        val data = "abc".encodeToByteArray()
        val ptr = 200
        val lenBytes = byteArrayOf(3, 0, 0, 0)
        lenBytes.copyInto(engine.memory, ptr)
        data.copyInto(engine.memory, ptr + 4)

        WasmMemoryProtocol.readLengthPrefixedString(engine, ptr)
        val deallocCall = engine.calls.first { it.first == WasmAbi.DEALLOC }
        assertEquals(listOf(ptr, 4 + 3), deallocCall.second)
    }

    // ── Bug 9: oversized length prefix ───────────────────────────────────

    @Test
    fun readLengthPrefixedString_rejects_oversized_length() {
        val engine = FakeWasmEngine()
        val ptr = 200
        // Write a length of 20MB (exceeds 10MB max)
        val len = 20 * 1024 * 1024
        val lenBytes = byteArrayOf(
            (len and 0xFF).toByte(),
            ((len shr 8) and 0xFF).toByte(),
            ((len shr 16) and 0xFF).toByte(),
            ((len shr 24) and 0xFF).toByte(),
        )
        lenBytes.copyInto(engine.memory, ptr)

        val ex = assertFailsWith<IllegalStateException> {
            WasmMemoryProtocol.readLengthPrefixedString(engine, ptr)
        }
        assertTrue(ex.message!!.contains("exceeds maximum"))
    }
}
