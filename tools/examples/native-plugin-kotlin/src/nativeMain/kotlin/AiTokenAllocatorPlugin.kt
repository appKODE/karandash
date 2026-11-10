@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKStringFromUtf8
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import platform.posix.strdup

private fun String.toCAllocatorString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Allocate token budgets across a set of rules given a total token budget.
 *
 * Returns a JSON object mapping rule IDs to their allocated token counts.
 * This example splits `totalBudget` evenly; a real allocator would weight it by `prioritiesJson`.
 *
 * @param rulesJson Null-terminated UTF-8 JSON array of rule ID strings requiring token budgets.
 * @param totalBudget Total number of tokens available to distribute across all rules.
 * @param prioritiesJson Null-terminated UTF-8 JSON object mapping rule IDs to priority weights.
 * @return Null-terminated UTF-8 JSON object mapping rule IDs to allocated token counts.
 */
@CName("karandash_ai_allocate_tokens")
fun aiAllocateTokens(
    rulesJson: CPointer<ByteVar>?,
    totalBudget: Int,
    prioritiesJson: CPointer<ByteVar>?
): CPointer<ByteVar> {
    prioritiesJson?.toKStringFromUtf8()
    val rules = try {
        rulesJson?.toKStringFromUtf8()?.let { pluginJson.decodeFromString(ListSerializer(String.serializer()), it) }
    } catch (e: IllegalArgumentException) {
        // Malformed rules JSON (SerializationException is an IllegalArgumentException): allocate nothing.
        logToStderr("KotlinNativeTokenAllocator: cannot decode rules: ${e.message}")
        null
    }.orEmpty()
    val share = if (rules.isEmpty()) 0 else totalBudget / rules.size
    val allocation = rules.associateWith { share }
    return pluginJson.encodeToString(MapSerializer(String.serializer(), Int.serializer()), allocation)
        .toCAllocatorString()
}
