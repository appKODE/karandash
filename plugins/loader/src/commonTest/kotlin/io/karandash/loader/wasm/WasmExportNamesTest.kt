package io.karandash.loader.wasm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WasmExportNamesTest {
    private val header = listOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    // type section (id 1) with zero entries, skipped on the way to the exports
    private val typeSection = listOf(0x01, 0x01, 0x00)

    // export section (id 7): "a" -> func 0, "bc" -> func 1
    private val exportSection = listOf(0x07, 0x0A, 0x02, 0x01, 0x61, 0x00, 0x00, 0x02, 0x62, 0x63, 0x00, 0x01)

    private fun bytes(vararg parts: List<Int>) = parts.flatMap { it }.map(Int::toByte).toByteArray()

    @Test
    fun export_names_are_read_after_skipping_other_sections() {
        assertEquals(setOf("a", "bc"), WasmExportNames.of(bytes(header, typeSection, exportSection)))
    }

    @Test
    fun a_module_without_exports_has_none() {
        assertEquals(emptySet(), WasmExportNames.of(bytes(header, typeSection)))
    }

    @Test
    fun param_counts_follow_imports_and_function_types() {
        // types: 0 = (i32 i32) -> i32, 1 = (i32 x4) -> i32
        val types = listOf(0x01, 0x0F, 0x02, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F, 0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x7F)
        // one imported function "m"."f" of type 0: it takes function index 0
        val imports = listOf(0x02, 0x07, 0x01, 0x01, 0x6D, 0x01, 0x66, 0x00, 0x00)
        // two own functions: index 1 of type 1, index 2 of type 0
        val functions = listOf(0x03, 0x03, 0x02, 0x01, 0x00)
        // "a" -> func 1, "bc" -> func 2
        val exports = listOf(0x07, 0x0A, 0x02, 0x01, 0x61, 0x00, 0x01, 0x02, 0x62, 0x63, 0x00, 0x02)
        assertEquals(mapOf("a" to 4, "bc" to 2), WasmExportNames.paramCounts(bytes(header, types, imports, functions, exports)))
        assertEquals(emptyMap(), WasmExportNames.paramCounts(bytes(header, listOf(0x01, 0x7F))))
    }

    @Test
    fun malformed_bytes_are_unknown() {
        assertNull(WasmExportNames.of(bytes(listOf(0x01, 0x02, 0x03, 0x04, 0x01, 0x00, 0x00, 0x00))))
        assertNull(WasmExportNames.of(bytes(header, exportSection.dropLast(3))))
        assertNull(WasmExportNames.of(bytes(header, listOf(0x01, 0x7F))))
    }
}
