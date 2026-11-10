package io.karandash.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull

/**
 * Tests for [createWasmEngine] -- the WASM engine factory (Chicory on JVM, Chasm on native).
 *
 * Verifies the happy path and the failure path (the engine rejects invalid bytes).
 */
class WasmEngineFactoryTest {
    @Test
    fun createWasmEngine_withValidWasm_returnsWorkingEngine() {
        val addWasm = WasmTestModules.add()
        val engine = createWasmEngine(addWasm)
        assertNotNull(engine)
        val result = engine.callI32("add", 3, 7)
        assertEquals(10, result)
        engine.close()
    }

    @Test
    fun createWasmEngine_withMinimalValidModule_returnsEngine() {
        // Minimal WASM: magic + version, no sections -- parseable but has no exports
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
        val engine = createWasmEngine(minimalWasm)
        assertNotNull(engine)
        engine.close()
    }

    @Test
    fun createWasmEngine_withInvalidBytes_throwsException() {
        assertFailsWith<RuntimeException> {
            createWasmEngine(byteArrayOf(0x00, 0x01, 0x02, 0x03))
        }
    }

    @Test
    fun createWasmEngine_withEmptyBytes_throwsException() {
        assertFailsWith<RuntimeException> {
            createWasmEngine(byteArrayOf())
        }
    }

    @Test
    fun createWasmEngine_producesEngineCapableOfCallingExport() {
        val addWasm = WasmTestModules.add()
        val engine = createWasmEngine(addWasm)
        // Boundary values
        assertEquals(0, engine.callI32("add", 0, 0))
        assertEquals(Int.MAX_VALUE, engine.callI32("add", Int.MAX_VALUE, 0))
        engine.close()
    }
}
