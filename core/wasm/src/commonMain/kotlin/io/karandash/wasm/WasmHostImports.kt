package io.karandash.wasm

import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.plusAssign
import kotlin.time.Duration
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** The host's answer to one `karandash_host_call(service, request_json)`: response JSON, never throws. */
typealias WasmHostCall = (service: String, request: String) -> String

/**
 * The two host functions a guest may import from module `env`, the same on every engine:
 *
 * - `karandash_host_call(service_ptr, service_len, request_ptr, request_len) -> i32`: serves the call and returns the
 *   UTF-8 length of the response, which the host keeps until it is fetched.
 * - `karandash_host_response(dst_ptr) -> i32`: copies that response to `dst_ptr` (a buffer the guest allocated with
 *   the returned length), forgets it and returns its length; -1 when no response is pending.
 *
 * Two steps, so the host never calls back into the guest (its allocator) while a guest call is running.
 * One instance per engine: a guest runs one call at a time.
 */
@OptIn(ExperimentalAtomicApi::class)
class WasmHostImports(
    private val hostCall: WasmHostCall,
) {
    private val pending = AtomicReference<ByteArray?>(null)
    private val hostNanos = AtomicLong(0L)
    private val running = AtomicReference<TimeMark?>(null)

    /** Time spent serving host calls, the one in progress included; the guest-call deadline is extended by it. */
    val hostTime: Duration get() = hostNanos.load().nanoseconds + (running.load()?.elapsedNow() ?: Duration.ZERO)

    internal fun call(
        read: (offset: Int, length: Int) -> ByteArray,
        args: IntArray,
    ): Int {
        val service = read(args[0], args[1]).decodeToString()
        val request = read(args[2], args[3]).decodeToString()
        val started = TimeSource.Monotonic.markNow()
        running.store(started)
        val response =
            try {
                hostCall(service, request).encodeToByteArray()
            } finally {
                hostNanos += started.elapsedNow().inWholeNanoseconds
                running.store(null)
            }
        pending.store(response)
        return response.size
    }

    internal fun response(
        write: (offset: Int, data: ByteArray) -> Unit,
        destination: Int,
    ): Int {
        val response = pending.exchange(null) ?: return NO_RESPONSE
        write(destination, response)
        return response.size
    }

    companion object {
        const val MODULE = "env"
        const val HOST_CALL = "karandash_host_call"
        const val HOST_RESPONSE = "karandash_host_response"
        internal const val HOST_CALL_PARAMS = 4

        /** For an engine with no host behind it: every service answers `unsupported`. */
        val UNSUPPORTED: WasmHostCall = { _, _ -> """{"err":"unsupported"}""" }
        private const val NO_RESPONSE = -1
    }
}
