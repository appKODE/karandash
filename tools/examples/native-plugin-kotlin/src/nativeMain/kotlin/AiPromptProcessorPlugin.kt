@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.strdup

private fun String.toCPromptString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Process a user prompt before it is sent to the AI model.
 *
 * Returns the prompt unchanged (passthrough). A real processor may augment or rewrite it.
 *
 * @param prompt Null-terminated UTF-8 string with the user prompt to process.
 * @param contextJson Null-terminated UTF-8 JSON object with additional plugin context.
 * @return Null-terminated UTF-8 string with the (possibly modified) prompt.
 */
@CName("karandash_ai_process_prompt")
fun aiProcessPrompt(
    prompt: CPointer<ByteVar>?,
    contextJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    val promptStr = prompt?.toKStringFromUtf8() ?: ""
    contextJson?.toKStringFromUtf8()
    return promptStr.toCPromptString()
}

/**
 * Process a system prompt before it is sent to the AI model.
 *
 * Returns the system prompt unchanged (passthrough). A real processor may augment or rewrite it.
 *
 * @param systemPrompt Null-terminated UTF-8 string with the system prompt to process.
 * @param contextJson Null-terminated UTF-8 JSON object with additional plugin context.
 * @return Null-terminated UTF-8 string with the (possibly modified) system prompt.
 */
@CName("karandash_ai_process_system_prompt")
fun aiProcessSystemPrompt(
    systemPrompt: CPointer<ByteVar>?,
    contextJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    val systemPromptStr = systemPrompt?.toKStringFromUtf8() ?: ""
    contextJson?.toKStringFromUtf8()
    return systemPromptStr.toCPromptString()
}
