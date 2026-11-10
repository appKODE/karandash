@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf8
import platform.posix.strdup

private fun String.toCEnricherString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Enrich the AI context with additional metadata derived from the merge request.
 *
 * Returns a JSON object with extra context fields that the host will merge into the
 * AI request context before calling the provider.
 *
 * @param mrJson Null-terminated UTF-8 JSON object representing the merge request.
 * @param contextJson Null-terminated UTF-8 JSON object with the current AI context.
 * @return Null-terminated UTF-8 JSON object with additional context fields.
 */
@CName("karandash_ai_enrich_context")
fun aiEnrichContext(
    mrJson: CPointer<ByteVar>?,
    contextJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    mrJson?.toKStringFromUtf8()
    contextJson?.toKStringFromUtf8()
    return """{"kotlin_enriched":"true"}""".toCEnricherString()
}
