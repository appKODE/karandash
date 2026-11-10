@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.strdup

private fun String.toCInterceptorString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Intercept an AI request before it is sent to the provider.
 *
 * Returning `{"passthrough":true}` signals the host to continue with the original request
 * unchanged. A real interceptor may modify the request JSON and return it instead.
 *
 * @param requestJson Null-terminated UTF-8 JSON object with the AI completion request.
 * @param contextJson Null-terminated UTF-8 JSON object with additional plugin context.
 * @return Null-terminated UTF-8 JSON object; either `{"passthrough":true}` or a modified request.
 */
@CName("karandash_ai_intercept")
fun aiIntercept(
    requestJson: CPointer<ByteVar>?,
    contextJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    // Unused in this passthrough example; a real interceptor would inspect/modify requestJson.
    requestJson?.toKStringFromUtf8()
    contextJson?.toKStringFromUtf8()
    return """{"passthrough":true}""".toCInterceptorString()
}
