package io.karandash.loader.wasm

import io.karandash.loader.bridge.BridgeAbi
import io.karandash.wasm.WasmHostImports
import io.karandash.wasm.WasmMemoryProtocol

/**
 * Export names of the karandash WASM ABI that the host calls by name.
 *
 * Guest op exports come from [BridgeAbi] ([io.karandash.loader.bridge.BridgeOp.wasm]); the rest are the host-side
 * names with no op: the version handshake, the string-protocol allocator, the host imports and the AI
 * continuation. The ABI mirrors the native C ABI but passes strings as length-prefixed UTF-8 in linear memory.
 */
internal object WasmAbi {
    const val ABI_VERSION = "karandash_abi_version"
    const val ALLOC = WasmMemoryProtocol.ALLOC_FUNC
    const val DEALLOC = WasmMemoryProtocol.DEALLOC_FUNC

    /** Second leg of the analyze `aiRequest` handshake (a guest may call `ai.complete` through `host_call` instead). */
    const val AI_ANALYZE_CONTINUE = "karandash_ai_analyze_continue"

    // Host-service imports (module `env`, provided by the host): the one channel to every host service
    const val HOST_CALL = WasmHostImports.HOST_CALL
    const val HOST_RESPONSE = WasmHostImports.HOST_RESPONSE

    val MODULE_ID = wasm("id")
    val MODULE_NAME = wasm("name")
    val MODULE_VERSION = wasm("version")
    val MODULE_TYPE = wasm("type")
    val DESCRIBE = wasm("describe")
    val MIN_API_LEVEL = wasm("minApiLevel")
    val DEFAULT_PRIORITY = wasm("defaultPriority")
    val IS_DEFAULT_ENABLED = wasm("isDefaultEnabled")
    val PRESERVE_KEYS = wasm("preserveKeys")
    val INITIALIZE = wasm("initialize")
    val SHUTDOWN = wasm("shutdown")
    val VALIDATE = wasm("validate")
    val REPORT = wasm("report")
    val FORMAT_ID = wasm("formatId")
    val CLI_COMMANDS = wasm("cliCommands")
    val FETCH_MERGE_REQUEST = wasm("fetchMergeRequest")
    val POST_COMMENT = wasm("postComment")
    val AI_ANALYZE = wasm("aiAnalyze")

    private fun wasm(op: String): String = checkNotNull(BridgeAbi.find(op)) { "no BridgeAbi op '$op'" }.wasm
}
