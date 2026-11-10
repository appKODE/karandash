@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import platform.posix.free
import platform.posix.strdup

private const val TITLE_LENGTH_RULE = "kotlin-title-length"
private const val MIN_TITLE_LENGTH = 10

// Helper: allocate C string from Kotlin String
private fun String.toCString(): CPointer<ByteVar> {
    return checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()
}

@CName("karandash_abi_version")
fun abiVersion(): Int = 1

@CName("karandash_module_id")
fun moduleId(): CPointer<ByteVar> = "kotlin-example-plugin".toCString()

@CName("karandash_module_name")
fun moduleName(): CPointer<ByteVar> = "Kotlin/Native Example Plugin".toCString()

@CName("karandash_module_version")
fun moduleVersion(): CPointer<ByteVar> = "1.0.0".toCString()

@CName("karandash_module_type")
fun moduleType(): CPointer<ByteVar> = "RULE".toCString()

/** [contextJson] is part of the fixed C ABI signature; this plugin needs no host context. */
@Suppress("UnusedParameter")
@CName("karandash_initialize")
fun initialize(contextJson: CPointer<ByteVar>?): Int = 1

@CName("karandash_shutdown")
fun shutdown() {
    // Nothing to release: this plugin holds no resources between calls.
}

@CName("karandash_contributed_rules")
fun contributedRules(): CPointer<ByteVar> {
    return pluginJson.encodeToString(
        ListSerializer(String.serializer()),
        listOf(TITLE_LENGTH_RULE)
    ).toCString()
}

/** [configJson] is part of the fixed C ABI signature; this rule has no settings. */
@Suppress("UnusedParameter")
@CName("karandash_validate")
fun validate(
    ruleId: CPointer<ByteVar>?,
    mrJson: CPointer<ByteVar>?,
    configJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    val rule = ruleId?.toKStringFromUtf8() ?: return "[]".toCString()
    val mrStr = mrJson?.toKStringFromUtf8() ?: return "[]".toCString()

    if (rule != TITLE_LENGTH_RULE) return "[]".toCString()

    val mr = try {
        pluginJson.decodeFromString<MergeRequest>(mrStr)
    } catch (e: IllegalArgumentException) {
        // Malformed MR JSON (SerializationException is an IllegalArgumentException): no findings.
        logToStderr("kotlin-example-plugin: cannot decode MR: ${e.message}")
        return "[]".toCString()
    }

    val findings = mutableListOf<Finding>()
    if (mr.title.length < MIN_TITLE_LENGTH) {
        findings.add(
            Finding(
                severity = "WARNING",
                message = "Title is too short (${mr.title.length} chars, minimum $MIN_TITLE_LENGTH)",
                rule = TITLE_LENGTH_RULE,
                location = null,
                explanation = null
            )
        )
    }

    return pluginJson.encodeToString(
        ListSerializer(Finding.serializer()),
        findings
    ).toCString()
}

@CName("karandash_format_id")
fun formatId(): CPointer<ByteVar> = "kotlin-native-text".toCString()

/**
 * Format findings as a human-readable text report.
 *
 * @param findingsJson Null-terminated UTF-8 JSON array of Finding objects (may be null).
 * @param passed 1 if validation passed (no blocking findings), 0 otherwise.
 * @param configJson Null-terminated UTF-8 JSON reporter config object (may be null); part of the fixed
 *   C ABI signature, this reporter has no settings.
 * @return Heap-allocated null-terminated UTF-8 report string; caller must free via karandash_free_string.
 */
@Suppress("UnusedParameter")
@CName("karandash_report")
fun report(
    findingsJson: CPointer<ByteVar>?,
    passed: Int,
    configJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    val findings = findingsJson?.toKStringFromUtf8() ?: "[]"
    val status = if (passed != 0) "PASSED" else "FAILED"
    return "[kotlin-native-text] $status\nFindings: $findings\n".toCString()
}

@CName("karandash_free_string")
fun freeString(ptr: CPointer<ByteVar>?) {
    if (ptr != null) {
        free(ptr)
    }
}
