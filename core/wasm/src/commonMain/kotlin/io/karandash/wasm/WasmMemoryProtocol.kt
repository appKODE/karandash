package io.karandash.wasm

/**
 * Memory protocol for WASM<->Karandash string exchange.
 *
 * All strings crossing the ABI boundary use 4-byte little-endian length prefix
 * followed by UTF-8 bytes:
 * ```
 * [len: 4 bytes LE] [data: len bytes UTF-8]
 * ```
 */
object WasmMemoryProtocol {
    /** Maximum allowed WASM string size (10 MB). Prevents OOM from oversized length prefixes. */
    private const val MAX_WASM_STRING_SIZE = 10 * 1024 * 1024

    /** WASM export name for the memory allocator. */
    const val ALLOC_FUNC = "karandash_alloc"

    /** WASM export name for the memory deallocator. */
    const val DEALLOC_FUNC = "karandash_dealloc"

    /**
     * Write [str] into WASM memory via [engine]:
     * 1. Calls `karandash_alloc(len)` to get a pointer.
     * 2. Writes UTF-8 bytes at that pointer.
     * Returns (ptr, len) pair.
     */
    fun writeString(
        engine: WasmEngine,
        str: String,
    ): Pair<Int, Int> {
        val bytes = str.encodeToByteArray()
        val ptr = engine.callI32(ALLOC_FUNC, bytes.size)
        if (ptr == 0) error("karandash_alloc returned null for size ${bytes.size}")
        engine.writeMemory(ptr, bytes)
        return ptr to bytes.size
    }

    /**
     * Read a length-prefixed string returned by a WASM function.
     * The function returns a ptr where the first 4 bytes are a LE int length,
     * followed by UTF-8 bytes.
     * After reading, frees the allocation via `karandash_dealloc`.
     */
    fun readLengthPrefixedString(
        engine: WasmEngine,
        ptr: Int,
    ): String {
        if (ptr == 0) return ""
        val lenBytes = engine.readMemory(ptr, 4)
        val len = (lenBytes[0].toInt() and 0xFF) or
            ((lenBytes[1].toInt() and 0xFF) shl 8) or
            ((lenBytes[2].toInt() and 0xFF) shl 16) or
            ((lenBytes[3].toInt() and 0xFF) shl 24)
        if (len <= 0) {
            engine.callI32(DEALLOC_FUNC, ptr, 4)
            return ""
        }
        if (len > MAX_WASM_STRING_SIZE) {
            engine.callI32(DEALLOC_FUNC, ptr, 4)
            error("WASM string length exceeds maximum ($len > $MAX_WASM_STRING_SIZE bytes)")
        }
        val data = engine.readMemory(ptr + 4, len)
        engine.callI32(DEALLOC_FUNC, ptr, 4 + len)
        return data.decodeToString()
    }
}
