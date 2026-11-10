package io.karandash.loader.bridge

import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.models.support.KarandashJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/**
 * The `{ok|err}` envelope a guest answers a `suspend ... : Result<Unit>` op with (the mutator and review-thread ops):
 * `{"ok":true}`, `{"err":"message"}`, or `{"err":"message","unsupported":true}` for
 * [UnsupportedPlatformOperationException].
 */
internal object BridgeResultEnvelope {
    const val OK = "{\"ok\":true}"

    /** The text every transport logs (once per op per module) when a plugin does not export a `Result` op. */
    const val MISSING_EXPORT = "the plugin does not export this operation; answered as unsupported"

    private const val KEY_OK = "ok"
    private const val KEY_ERR = "err"
    private const val KEY_UNSUPPORTED = "unsupported"

    fun encode(result: Result<Unit>): String {
        val error = result.exceptionOrNull() ?: return OK
        return buildJsonObject {
            put(KEY_ERR, error.message ?: error.toString())
            if (error is UnsupportedPlatformOperationException) put(KEY_UNSUPPORTED, true)
        }.toString()
    }

    /**
     * Whether [raw] is the `{"err":…,"unsupported":true}` marker a guest answers a query whose SDK default throws
     * [UnsupportedPlatformOperationException] with. A text answer that is exactly this object reads as the marker.
     */
    fun isUnsupported(raw: String): Boolean {
        if (!raw.startsWith("{")) return false
        val envelope = try {
            KarandashJson.Default.parseToJsonElement(raw) as? JsonObject
        } catch (_: SerializationException) {
            return false
        }
        return envelope?.get(KEY_ERR) != null && (envelope[KEY_UNSUPPORTED] as? JsonPrimitive)?.booleanOrNull == true
    }

    /** Decodes [raw], the answer to [op]; no answer or a malformed one is a failure, never a success. */
    fun decode(
        op: String,
        raw: String?,
    ): Result<Unit> {
        if (raw == null ||
            raw == BridgeConstants.NULL_SENTINEL
        ) {
            return Result.failure(IllegalStateException("$op: no answer from the plugin"))
        }
        val envelope = try {
            KarandashJson.Default.parseToJsonElement(raw) as? JsonObject
        } catch (e: SerializationException) {
            return Result.failure(IllegalStateException("$op: malformed answer from the plugin", e))
        }
        val err = (envelope?.get(KEY_ERR) as? JsonPrimitive)?.contentOrNull
        val unsupported = (envelope?.get(KEY_UNSUPPORTED) as? JsonPrimitive)?.booleanOrNull == true
        return when {
            err != null && unsupported -> Result.failure(UnsupportedPlatformOperationException(op))
            err != null -> Result.failure(IllegalStateException(err))
            (envelope?.get(KEY_OK) as? JsonPrimitive)?.booleanOrNull == true -> Result.success(Unit)
            else -> Result.failure(IllegalStateException("$op: malformed answer from the plugin"))
        }
    }
}

/**
 * Calls the `Result` op [op]. A plugin that does not export it answers [UnsupportedPlatformOperationException] (the SDK
 * default), logged once per op through [warned]; a failing call, no answer or a malformed one is a failure.
 */
internal suspend fun BridgeTransport.resultCall(
    op: String,
    warned: MutableStateFlow<Set<String>>,
    vararg args: String,
): Result<Unit> {
    if (!hasCapability(op)) {
        if (op !in warned.getAndUpdate { it + op }) logError(op, IllegalStateException(BridgeResultEnvelope.MISSING_EXPORT))
        return Result.failure(UnsupportedPlatformOperationException(op))
    }
    return BridgeResultEnvelope.decode(op, guarded(op, null) { call(op, *args) })
}

/**
 * Calls the query op [op] and decodes its JSON answer with [serializer]; null (the SDK default at the call site) when
 * the plugin does not export it, answers NULL, or the call or the decode fails (logged).
 */
internal suspend fun <T> BridgeTransport.query(
    op: String,
    serializer: DeserializationStrategy<T>,
    vararg args: String,
): T? {
    if (!hasCapability(op)) return null
    return guarded(op, null) { call(op, *args)?.let { decodeAnswer(serializer, it) } }
}

/**
 * Calls the query op [op] whose SDK default throws [UnsupportedPlatformOperationException] (`fetchFileContent`,
 * `fetchApprovalSignals`, `searchMergeRequests`, `listReviewThreads`): a missing export or the guest's unsupported
 * marker throws it, as the SDK default does; NULL is null; otherwise [decode] reads the answer. A failing call or
 * decode is null (logged).
 */
internal suspend fun <T> BridgeTransport.unsupportedQuery(
    op: String,
    decode: (String) -> T,
    vararg args: String,
): T? {
    if (!hasCapability(op)) throw UnsupportedPlatformOperationException(op)
    val raw = guarded(op, null) { call(op, *args) } ?: return null
    if (BridgeResultEnvelope.isUnsupported(raw)) throw UnsupportedPlatformOperationException(op)
    return guarded(op, null) { raw.takeUnless { it == BridgeConstants.NULL_SENTINEL }?.let(decode) }
}

/** [query] for a non-suspend SDK member, over [BridgeTransport.callSync]. */
internal fun <T> BridgeTransport.querySync(
    op: String,
    serializer: DeserializationStrategy<T>,
    vararg args: String,
): T? {
    if (!hasCapability(op)) return null
    return guarded(op, null) { callSync(op, *args)?.let { decodeAnswer(serializer, it) } }
}

private fun <T> decodeAnswer(
    serializer: DeserializationStrategy<T>,
    raw: String,
): T? = if (raw == BridgeConstants.NULL_SENTINEL) null else KarandashJson.Default.decodeFromString(serializer, raw)
