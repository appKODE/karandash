package io.karandash.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests for [ChicoryJvmEngine], the pure-Java WASM runtime.
 *
 * Uses a minimal hand-crafted WASM binary that exports an `add` function
 * returning the sum of two i32 arguments.
 */
class ChicoryJvmEngineTest {
    /**
     * Minimal valid WASM module (magic + version header only, no exports).
     * Just verifies that Chicory can parse a module without crashing.
     */
    @Test
    fun createEngineWithMinimalModule() {
        // Minimal WASM: magic number + version 1 + empty sections
        // This module has no exports but should parse successfully.
        val minimalWasm = byteArrayOf(
            0x00,
            0x61,
            0x73,
            0x6D, // magic: \0asm
            0x01,
            0x00,
            0x00,
            0x00, // version: 1
        )

        val engine = ChicoryJvmEngine(minimalWasm)
        assertNotNull(engine)
        engine.close()
    }

    /** A void export (Rust `karandash_dealloc`) must return 0, not NPE on Chicory's null result. */
    @Test
    fun voidExportReturnsZero() {
        val voidExportWasm = byteArrayOf(
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
            0x00, // type: () -> ()
            0x03,
            0x02,
            0x01,
            0x00, // function 0 uses type 0
            0x07,
            0x08,
            0x01,
            0x04,
            0x6E,
            0x6F,
            0x6F,
            0x70,
            0x00,
            0x00, // export "noop"
            0x0A,
            0x04,
            0x01,
            0x02,
            0x00,
            0x0B, // body: end
        )
        val engine = ChicoryJvmEngine(voidExportWasm)
        assertEquals(0, engine.callI32("noop"))
        engine.close()
    }

    @Test
    fun invalidWasmBytesThrows() {
        assertFailsWith<Exception> {
            ChicoryJvmEngine(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
    }

    @Test
    fun emptyBytesThrows() {
        assertFailsWith<Exception> {
            ChicoryJvmEngine(byteArrayOf())
        }
    }

    /**
     * WASM module with a single exported function: add(i32, i32) -> i32.
     *
     * Hand-crafted binary:
     * - Type section: one function type (i32, i32) -> i32
     * - Function section: one function using type 0
     * - Export section: export "add" as function 0
     * - Code section: local.get 0, local.get 1, i32.add, end
     */
    @Test
    fun callExportedAddFunction() {
        val addWasm = WasmTestModules.add()
        val engine = ChicoryJvmEngine(addWasm)

        val result = engine.callI32("add", 3, 7)
        assertEquals(10, result)

        engine.close()
    }

    @Test
    fun callMissingFunctionThrows() {
        val addWasm = WasmTestModules.add()
        val engine = ChicoryJvmEngine(addWasm)

        assertFailsWith<Exception> {
            engine.callI32("nonexistent")
        }

        engine.close()
    }
}
