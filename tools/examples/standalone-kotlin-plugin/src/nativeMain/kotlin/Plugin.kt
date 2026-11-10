@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.*
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import platform.posix.fprintf
import platform.posix.free
import platform.posix.stderr
import platform.posix.strdup

/** The plugin's one Json instance: a C-ABI plugin links no karandash library, so `KarandashJson` is unavailable. */
// standalone-plugin: C-ABI plugin with no karandash library on the classpath
private val json = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

private const val TITLE_CHECK_RULE = "kotlin-title-check"

private val conventionalPrefixes = listOf(
    "feat:", "fix:", "chore:", "refactor:", "docs:", "test:", "ci:"
)

private fun String.toCString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

@CName("karandash_abi_version")
fun abiVersion(): Int = 1

@CName("karandash_module_id")
fun moduleId(): CPointer<ByteVar> = "kotlin-standalone-plugin".toCString()

@CName("karandash_module_name")
fun moduleName(): CPointer<ByteVar> = "Kotlin Standalone Rule Plugin".toCString()

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
    return json.encodeToString(
        ListSerializer(String.serializer()),
        listOf(TITLE_CHECK_RULE)
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

    if (rule != TITLE_CHECK_RULE) return "[]".toCString()

    val mr = try {
        json.decodeFromString<MergeRequest>(mrStr)
    } catch (e: IllegalArgumentException) {
        // Malformed MR JSON (SerializationException is an IllegalArgumentException): no findings. A C-ABI plugin
        // has no Logger channel and stdout may carry the host's report, so the diagnostic goes to stderr.
        fprintf(stderr, "%s\n", "kotlin-standalone-plugin: cannot decode MR: ${e.message}")
        return "[]".toCString()
    }

    val hasPrefix = conventionalPrefixes.any { mr.title.startsWith(it) }
    if (!hasPrefix) {
        val findings = listOf(
            Finding(
                severity = "ERROR",
                message = "Title must start with feat:, fix:, chore:, refactor:, docs:, test:, or ci:",
                rule = TITLE_CHECK_RULE,
                location = null,
                explanation = null
            )
        )
        return json.encodeToString(ListSerializer(Finding.serializer()), findings).toCString()
    }

    return "[]".toCString()
}

@CName("karandash_format_id")
fun formatId(): CPointer<ByteVar> = "".toCString()

/**
 * This plugin is a rule, not a reporter (empty [formatId]), so the host never calls this export. The
 * parameters are the fixed C ABI signature.
 */
@Suppress("UnusedParameter")
@CName("karandash_report")
fun report(
    findingsJson: CPointer<ByteVar>?,
    passed: Int,
    configJson: CPointer<ByteVar>?
): CPointer<ByteVar> = "".toCString()

@CName("karandash_free_string")
fun freeString(ptr: CPointer<ByteVar>?) {
    if (ptr != null) free(ptr)
}
