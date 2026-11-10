package io.karandash.plugins.ai.providers

import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readLine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable

/**
 * One piece of a streamed completion ([AiProvider.streamCompletion]): a text delta, and the usage the provider
 * reports, usually on the last chunk. A field left null is not reported by this chunk.
 */
@Serializable
data class AiChunk(
    val text: String = "",
    val promptTokens: Int? = null,
    val completionTokens: Int? = null,
    val cacheReadTokens: Int? = null,
    val cacheWriteTokens: Int? = null,
    val model: String? = null,
    /** A piece of the model's refusal (OpenAI `delta.refusal`), sent instead of [text]. */
    val refusal: String? = null,
    /** The reply stopped at its output cap (OpenAI `finish_reason: length`); warned once per call, after the merge. */
    val truncated: Boolean = false,
)

/** The whole response as one chunk: the default stream of a provider that does not stream. */
internal fun AiResponse.toChunk(): AiChunk =
    AiChunk(
        text = content,
        promptTokens = promptTokens,
        completionTokens = completionTokens,
        cacheReadTokens = cacheReadTokens,
        cacheWriteTokens = cacheWriteTokens,
        model = model,
    )

/** Joins the text of [chunks]; each usage field is the last value reported, as providers send totals, not deltas. */
internal fun mergeChunks(
    chunks: List<AiChunk>,
    fallbackModel: String,
    durationMs: Long,
): AiResponse {
    val prompt = chunks.lastNotNullOf { it.promptTokens }
    val completion = chunks.lastNotNullOf { it.completionTokens }
    return AiResponse(
        content = chunks.joinToString("") { it.text },
        promptTokens = prompt,
        completionTokens = completion,
        totalTokens = prompt + completion,
        model = chunks.lastOrNull { it.model != null }?.model ?: fallbackModel,
        durationMs = durationMs,
        cacheReadTokens = chunks.lastNotNullOf { it.cacheReadTokens },
        cacheWriteTokens = chunks.lastNotNullOf { it.cacheWriteTokens },
        truncated = chunks.any { it.truncated },
    )
}

private fun List<AiChunk>.lastNotNullOf(field: (AiChunk) -> Int?): Int = asReversed().firstNotNullOfOrNull(field) ?: 0

/**
 * The `data:` payloads of a server-sent-events body, line by line as they arrive, up to OpenAI's `[DONE]` or the end
 * of the body. `event:`, `id:` and comment lines are skipped: every payload this reads carries its own `type`.
 */
internal fun sseData(channel: ByteReadChannel): Flow<String> =
    flow {
        while (true) {
            val line = channel.readLine() ?: break
            if (!line.startsWith(SSE_DATA)) continue
            val data = line.removePrefix(SSE_DATA).trim()
            if (data == SSE_DONE) break
            if (data.isNotEmpty()) emit(data)
        }
    }

private const val SSE_DATA = "data:"
private const val SSE_DONE = "[DONE]"
