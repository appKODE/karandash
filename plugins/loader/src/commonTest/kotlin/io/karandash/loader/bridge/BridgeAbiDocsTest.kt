package io.karandash.loader.bridge

import io.karandash.testfixtures.GoldenFiles
import io.karandash.testfixtures.TestFiles
import kotlin.test.Test

/** WasmAbi names that are not guest ops: memory management, host imports and multi-step continuations. */
internal val WASM_HOST_SIDE: Map<String, String> =
    mapOf(
        "karandash_abi_version" to "version handshake read by the loader before any op",
        "karandash_alloc" to "guest allocator used by the string protocol",
        "karandash_dealloc" to "guest allocator used by the string protocol",
        "karandash_host_call" to "host import `env` (svc_ptr, svc_len, req_ptr, req_len) -> response length",
        "karandash_host_response" to "host import `env` (dst_ptr) -> length: copies the pending response, -1 if none",
        "karandash_ai_analyze_continue" to "second leg of the WASM AI analyze `aiRequest` handshake",
    )

/**
 * The native header and the WASM export table in `docs/native-sdk/` are generated from [BridgeAbi.OPS].
 * The example plugins ship a copy of the header. Regenerate after changing the op table with
 * `./gradlew :plugins:loader:jvmTest --tests io.karandash.loader.bridge.BridgeAbiDocsTest -Dkarandash.updateGolden=true`.
 */
class BridgeAbiDocsTest {
    private val repoRoot = checkNotNull(TestFiles.findRepoRoot()) { "repo root not found" }

    private fun golden(
        path: String,
        expected: String,
    ) = GoldenFiles.assertMatches(repoRoot, path, expected)

    @Test
    fun `the native header matches the op table`() = golden(HEADER, header())

    @Test
    fun `the WASM export table matches the op table`() = golden("docs/native-sdk/wasm-exports.md", wasmTable())

    private fun header(): String =
        buildString {
            appendLine("/* GENERATED from BridgeAbi.OPS by BridgeAbiDocsTest. Do not edit; see that test to regenerate. */")
            appendLine("#ifndef KARANDASH_PLUGIN_H")
            appendLine("#define KARANDASH_PLUGIN_H")
            appendLine()
            appendLine("#include <stdint.h>")
            appendLine()
            appendLine("#ifdef __cplusplus")
            appendLine("extern \"C\" {")
            appendLine("#endif")
            appendLine()
            appendLine("/*")
            appendLine(" * Karandash Native Plugin ABI v2 (v1 plugins, without karandash_set_host, still load).")
            appendLine(" * Strings are UTF-8, null-terminated. Complex values are JSON strings.")
            appendLine(" * Returned strings are heap-allocated by the plugin and released by the host through")
            appendLine(" * karandash_free_string; strings passed to the plugin stay owned by the host.")
            appendLine(" * Every op is optional except identity and lifecycle: an absent export means the SDK default.")
            appendLine(" *")
            appendLine(" * Host services (v2): before karandash_initialize the host calls karandash_set_host(2, host_ctx, call, free);")
            appendLine(" * return 1 to accept. call(host_ctx, service, request_json) answers a JSON string the plugin releases")
            appendLine(" * with free. Services: log, cache.get, cache.put, resolvePattern, resolveContent, ai.complete, ai.proceed, skip.")
            appendLine(" */")
            appendLine()
            appendLine("typedef char* (*karandash_host_call_fn)(void* host_ctx, const char* service, const char* request_json);")
            appendLine("typedef void (*karandash_host_free_fn)(char* ptr);")
            appendLine()
            appendLine("int32_t karandash_abi_version(void);")
            appendLine(
                "int32_t karandash_set_host(int32_t abi, void* host_ctx, karandash_host_call_fn call, karandash_host_free_fn free_fn);"
            )
            appendLine("void karandash_free_string(const char* ptr);")
            BridgeAbi.OPS.groupBy { it.section }.forEach { (section, ops) ->
                appendLine()
                appendLine("/* $section */")
                ops.forEach { appendLine(prototype(it)) }
            }
            appendLine()
            appendLine("#ifdef __cplusplus")
            appendLine("}")
            appendLine("#endif")
            appendLine()
            appendLine("#endif /* KARANDASH_PLUGIN_H */")
        }

    private fun prototype(op: BridgeOp): String {
        val params =
            op.params
                .map { if (it.startsWith("#")) "int32_t ${it.drop(1)}" else "const char* $it" }
                .ifEmpty { listOf("void") }
        return "${op.returns.c} ${op.symbol}(${params.joinToString(", ")});"
    }

    private fun wasmTable(): String =
        buildString {
            appendLine("# WASM export table")
            appendLine()
            appendLine("Generated from `BridgeAbi.OPS` by `BridgeAbiDocsTest`; do not edit. A string parameter is passed as two")
            appendLine("`i32`s (pointer, length) into guest memory written through `karandash_alloc`; a parameter marked `(i32)` is a")
            appendLine("plain `i32`. A `STR` result is an `i32` pointer to a 4-byte little-endian length followed by UTF-8 bytes")
            appendLine("(0 means none); `INT` and `BOOL` results are `i32`.")
            appendLine()
            appendLine("| Op | Export | Parameters | Returns | JS member |")
            appendLine("|----|--------|------------|---------|-----------|")
            BridgeAbi.OPS.forEach { op ->
                val params = op.params.joinToString(", ") { if (it.startsWith("#")) "${it.drop(1)} (i32)" else it }.ifEmpty { "-" }
                appendLine("| `${op.op}` | `${op.wasm}` | $params | ${op.returns.name} | `${op.js}` |")
            }
            appendLine()
            appendLine("## Host-side names (not guest ops)")
            appendLine()
            appendLine("| Export | Purpose |")
            appendLine("|--------|---------|")
            WASM_HOST_SIDE.forEach { (name, why) -> appendLine("| `$name` | $why |") }
        }

    private companion object {
        // The C/C++ examples build against this one header (`KARANDASH_SDK` in their Makefile/CMakeLists).
        const val HEADER = "docs/native-sdk/karandash_plugin.h"
    }
}
