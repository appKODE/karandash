package io.karandash.wasm

/** Hand-assembled WASM binaries for engine tests. */
object WasmTestModules {
    private val header = listOf(0x00, 0x61, 0x73, 0x6D, 0x01, 0x00, 0x00, 0x00)

    /**
     * Exports `spin` (`() -> i32`, loops forever) and `answer` (`() -> i32`, returns 42).
     * Equivalent WAT: `(func (export "spin") (result i32) (loop (br 0)) (i32.const 0))`
     * and `(func (export "answer") (result i32) (i32.const 42))`.
     */
    fun spinAndAnswer(): ByteArray =
        bytes(
            header +
                listOf(0x01, 0x05, 0x01, 0x60, 0x00, 0x01, 0x7F) + // type 0: () -> i32
                listOf(0x03, 0x03, 0x02, 0x00, 0x00) + // two functions of type 0
                listOf(0x07, 0x11, 0x02) + // export section, 2 entries
                listOf(0x04) + "spin".map { it.code } + listOf(0x00, 0x00) +
                listOf(0x06) + "answer".map { it.code } + listOf(0x00, 0x01) +
                listOf(0x0A, 0x10, 0x02) + // code section, 2 bodies
                listOf(0x09, 0x00, 0x03, 0x40, 0x0C, 0x00, 0x0B, 0x41, 0x00, 0x0B) + // spin
                listOf(0x04, 0x00, 0x41, 0x2A, 0x0B), // answer
        )

    /**
     * Exports `add` (`(i32, i32) -> i32`, returns the sum).
     * Equivalent WAT: `(func (export "add") (param i32 i32) (result i32) (i32.add (local.get 0) (local.get 1)))`.
     */
    fun add(): ByteArray =
        bytes(
            header +
                listOf(0x01, 0x07, 0x01, 0x60, 0x02, 0x7F, 0x7F, 0x01, 0x7F) + // type 0: (i32, i32) -> i32
                listOf(0x03, 0x02, 0x01, 0x00) + // one function of type 0
                listOf(0x07, 0x07, 0x01, 0x03) + "add".map { it.code } + listOf(0x00, 0x00) + // export "add"
                listOf(0x0A, 0x09, 0x01, 0x07, 0x00, 0x20, 0x00, 0x20, 0x01, 0x6A, 0x0B), // local.get 0 1, i32.add
        )

    /**
     * A module whose start function loops forever, so instantiating it never returns.
     * Equivalent WAT: `(func $s (loop (br 0))) (start $s)`.
     */
    fun spinningStart(): ByteArray =
        bytes(
            header +
                listOf(0x01, 0x04, 0x01, 0x60, 0x00, 0x00) + // type 0: () -> ()
                listOf(0x03, 0x02, 0x01, 0x00) + // one function of type 0
                listOf(0x08, 0x01, 0x00) + // start: function 0
                listOf(0x0A, 0x09, 0x01, 0x07, 0x00, 0x03, 0x40, 0x0C, 0x00, 0x0B, 0x0B), // code: loop forever
        )

    /**
     * Imports `env.karandash_host_call` and `env.karandash_host_response`, exports `memory` (one page) and `ping`
     * (`() -> i32`): calls the host with service `"echo"` (at 0) and request `"{}"` (at 16), copies the response to 64
     * and returns its length.
     * Equivalent WAT: `(call $host_call (i32.const 0) (i32.const 4) (i32.const 16) (i32.const 2)) drop
     * (call $host_response (i32.const 64))`.
     */
    fun hostCall(): ByteArray =
        bytes(
            header +
                listOf(0x01, 0x12, 0x03) + // type section, 3 types
                listOf(0x60, 0x04, 0x7F, 0x7F, 0x7F, 0x7F, 0x01, 0x7F) + // type 0: (i32 x4) -> i32
                listOf(0x60, 0x01, 0x7F, 0x01, 0x7F) + // type 1: (i32) -> i32
                listOf(0x60, 0x00, 0x01, 0x7F) + // type 2: () -> i32
                listOf(0x02, 0x39, 0x02) + // import section, 2 entries
                hostImport(WasmHostImports.HOST_CALL, 0x00) +
                hostImport(WasmHostImports.HOST_RESPONSE, 0x01) +
                listOf(0x03, 0x02, 0x01, 0x02) + // one function of type 2
                listOf(0x05, 0x03, 0x01, 0x00, 0x01) + // memory: min 1 page
                listOf(0x07, 0x11, 0x02) + // export section, 2 entries
                listOf(0x04) + "ping".map { it.code } + listOf(0x00, 0x02) +
                listOf(0x06) + "memory".map { it.code } + listOf(0x02, 0x00) +
                listOf(0x0A, 0x14, 0x01, 0x12, 0x00) + // code section, 1 body of 18 bytes, no locals
                listOf(0x41, 0x00, 0x41, 0x04, 0x41, 0x10, 0x41, 0x02, 0x10, 0x00, 0x1A) + // host_call, drop
                listOf(0x41, 0xC0, 0x00, 0x10, 0x01, 0x0B) + // host_response(64)
                listOf(0x0B, 0x11, 0x02) + // data section, 2 segments
                listOf(0x00, 0x41, 0x00, 0x0B, 0x04) + "echo".map { it.code } +
                listOf(0x00, 0x41, 0x10, 0x0B, 0x02) + "{}".map { it.code },
        )

    private fun hostImport(
        name: String,
        type: Int,
    ): List<Int> =
        listOf(0x03) + WasmHostImports.MODULE.map { it.code } + listOf(name.length) + name.map { it.code } +
            listOf(0x00, type)

    private fun bytes(values: List<Int>): ByteArray = ByteArray(values.size) { values[it].toByte() }
}
