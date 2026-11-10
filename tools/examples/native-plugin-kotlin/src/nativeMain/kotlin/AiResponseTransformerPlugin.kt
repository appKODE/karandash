@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.strdup

private fun String.toCTransformerString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Transform an AI response after it is received from the provider.
 *
 * Returns the response JSON unchanged (passthrough). A real transformer may rewrite or
 * normalise the response before the host consumes it.
 *
 * @param responseJson Null-terminated UTF-8 JSON object with the raw AI response.
 * @param requestJson Null-terminated UTF-8 JSON object with the original AI request.
 * @param contextJson Null-terminated UTF-8 JSON object with additional plugin context.
 * @return Null-terminated UTF-8 JSON object with the (possibly modified) AI response.
 */
@CName("karandash_ai_transform_response")
fun aiTransformResponse(
    responseJson: CPointer<ByteVar>?,
    requestJson: CPointer<ByteVar>?,
    contextJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    val response = responseJson?.toKStringFromUtf8() ?: "{}"
    requestJson?.toKStringFromUtf8()
    contextJson?.toKStringFromUtf8()
    return response.toCTransformerString()
}
