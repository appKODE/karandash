package io.karandash.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/** a guest cannot grow its linear memory without bound. */
class ChicoryMemoryLimitTest {
    /** Exports `grow() -> i32`, which asks for 2000 more pages and returns the old size or -1. */
    private val growModule = byteArrayOf(
        0x00,
        0x61,
        0x73,
        0x6D,
        0x01,
        0x00,
        0x00,
        0x00, // header
        0x01,
        0x05,
        0x01,
        0x60,
        0x00,
        0x01,
        0x7F, // type () -> i32
        0x03,
        0x02,
        0x01,
        0x00, // function 0
        0x05,
        0x03,
        0x01,
        0x00,
        0x01, // memory, min 1 page, no maximum
        0x07,
        0x08,
        0x01,
        0x04,
        0x67,
        0x72,
        0x6F,
        0x77,
        0x00,
        0x00, // export "grow"
        0x0A,
        0x09,
        0x01,
        0x07,
        0x00,
        0x41,
        0xD0.toByte(),
        0x0F,
        0x40,
        0x00,
        0x0B, // i32.const 2000; memory.grow
    )

    @Test
    fun `memory grow past the engine limit fails`() {
        val engine = ChicoryJvmEngine(growModule)

        assertEquals(-1, engine.callI32("grow"))
    }

    private fun wasm(hex: String): ByteArray = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    /** Memory min 2 pages, data byte 0x2A at offset 65536 (page 1), exported `read() -> i32` loads it. */
    private val minTwoModule = wasm(
        "0061736d01000000" + "010501600001" + "7f" + "03020100" + "05030100" + "02" +
            "0708010472656164" + "0000" + "0a0b010900" + "41808004" + "2d0000" + "0b" +
            "0b09010041808004" + "0b012a",
    )

    /** Memory min 1, max 3; exported `grow(n: i32) -> i32` is `memory.grow`. */
    private val maxThreeModule = wasm(
        "0061736d01000000" + "01060160017f017f" + "03020100" + "0504010101" + "03" +
            "07080104" + "67726f77" + "0000" + "0a0801060020004000" + "0b",
    )

    private val minAboveCapModule = wasm("0061736d01000000" + "0504" + "01" + "00" + "d00f")

    @Test
    fun `module declaring min 2 pages loads and its data segment at page 1 works`() {
        assertEquals(0x2A, ChicoryJvmEngine(minTwoModule).callI32("read"))
    }

    @Test
    fun `module max below the cap keeps its own max`() {
        val engine = ChicoryJvmEngine(maxThreeModule)

        assertEquals(1, engine.callI32Array("grow", intArrayOf(2)))
        assertEquals(-1, engine.callI32Array("grow", intArrayOf(1)))
    }

    @Test
    fun `module min above the cap is rejected with a clear error`() {
        val error = assertFailsWith<IllegalArgumentException> { ChicoryJvmEngine(minAboveCapModule) }
        assertEquals(true, error.message?.contains("2000"))
    }
}
