@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.strdup

private fun String.toCAiProviderString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Complete an AI request and return the model response.
 *
 * @param requestJson Null-terminated UTF-8 JSON object describing the AI completion request
 *                    (model, messages, temperature, maxTokens, etc.).
 * @return Null-terminated UTF-8 JSON object with the AI response
 *         (text, tokensUsed, model, finishReason).
 */
@CName("karandash_ai_complete")
fun aiComplete(requestJson: CPointer<ByteVar>?): CPointer<ByteVar> {
    val request = requestJson?.toKStringFromUtf8() ?: ""
    // A real implementation would call the target AI API using the parsed request.
    // This example returns a stub response that mirrors the request model field when possible.
    val model = if (request.contains("\"model\"")) "kotlin-example-model" else "kotlin-example-model"
    return """{"text":"Example Kotlin/Native AI response","tokensUsed":10,"model":"$model","finishReason":"stop"}""".toCAiProviderString()
}
