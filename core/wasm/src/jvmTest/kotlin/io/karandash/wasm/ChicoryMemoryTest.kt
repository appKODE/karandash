package io.karandash.wasm

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFails

class ChicoryMemoryTest {
    /** Exports a one-page memory `mem` next to the void function `nop`; only `nop` is callable. */
    private val memoryModule =
        listOf(
            0x00,
            0x61,
            0x73,
            0x6D,
            0x01,
            0x00,
            0x00,
            0x00, // header
            0x01,
            0x04,
            0x01,
            0x60,
            0x00,
            0x00, // type 0: () -> ()
            0x03,
            0x02,
            0x01,
            0x00, // function 0 of type 0
            0x05,
            0x03,
            0x01,
            0x00,
            0x01, // memory: min 1 page
            0x07,
            0x0D,
            0x02, // export section, 2 entries
            0x03,
            0x6E,
            0x6F,
            0x70,
            0x00,
            0x00, // "nop" function 0
            0x03,
            0x6D,
            0x65,
            0x6D,
            0x02,
            0x00, // "mem" memory 0
            0x0A,
            0x04,
            0x01,
            0x02,
            0x00,
            0x0B, // body: end
        ).let { bytes -> ByteArray(bytes.size) { bytes[it].toByte() } }

    @Test
    fun `memory writes are read back and memory exports are not callable`() {
        val engine = ChicoryJvmEngine(memoryModule)

        engine.writeMemory(100, "abc".encodeToByteArray())

        assertContentEquals("abc".encodeToByteArray(), engine.readMemory(100, 3))
        assertEquals(0, engine.callI32("nop"))
        assertEquals("Unknown export with name mem", assertFails { engine.callI32("mem") }.message)
        engine.close()
    }
}
