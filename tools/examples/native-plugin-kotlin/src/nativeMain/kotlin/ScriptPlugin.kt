@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.*
import platform.posix.strdup

private fun String.toScriptCString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Execute a script rule against a merge request.
 *
 * The host passes any script definitions, the serialized MR, and a context JSON object.
 * Returns a JSON array of Finding objects (may be empty).
 *
 * @param scriptsJson Null-terminated UTF-8 JSON array of script definitions (may be null).
 * @param mrJson Null-terminated UTF-8 JSON object representing the MergeRequest (may be null).
 * @param contextJson Null-terminated UTF-8 JSON object with execution context (may be null); part of the
 *   fixed C ABI signature, this example script needs no context.
 * @return Heap-allocated null-terminated UTF-8 JSON array of findings; caller must free via karandash_free_string.
 */
@Suppress("UnusedParameter")
@CName("karandash_execute")
fun execute(
    scriptsJson: CPointer<ByteVar>?,
    mrJson: CPointer<ByteVar>?,
    contextJson: CPointer<ByteVar>?,
): CPointer<ByteVar> {
    val scripts = scriptsJson?.toKStringFromUtf8() ?: return "[]".toScriptCString()
    val mr = mrJson?.toKStringFromUtf8() ?: return "[]".toScriptCString()

    return if (scripts.isNotEmpty() && mr.isNotEmpty()) {
        """[{"rule":"kotlin-script","message":"Script executed successfully","severity":"INFO","location":null}]"""
            .toScriptCString()
    } else {
        "[]".toScriptCString()
    }
}
