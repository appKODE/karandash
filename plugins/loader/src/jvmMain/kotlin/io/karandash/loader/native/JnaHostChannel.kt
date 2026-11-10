package io.karandash.loader.native

import com.sun.jna.Callback
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import io.karandash.loader.NativeAbi
import io.karandash.loader.bridge.host.HostServiceDispatcher

/**
 * Hands a JNA-loaded v2 guest the host-service channel through `karandash_set_host(abi, host_ctx, call_fn, free_fn)`.
 *
 * `call_fn(host_ctx, service, request_json)` answers a response the host allocated with `malloc`; the guest releases
 * it through `free_fn`, never its own `free()` (no cross-CRT free). Each channel has its own callbacks, so
 * `host_ctx` is unused here. The callbacks must stay reachable while the library is loaded: the transport holds this.
 */
internal class JnaHostChannel(
    val host: HostServiceDispatcher,
) {
    /** `char* (*)(void* host_ctx, const char* service, const char* request_json)`. */
    fun interface CallFn : Callback {
        fun invoke(
            hostCtx: Pointer?,
            service: Pointer?,
            request: Pointer?,
        ): Pointer?
    }

    /** `void (*)(char* ptr)`. */
    fun interface FreeFn : Callback {
        fun invoke(ptr: Pointer?)
    }

    private val call =
        CallFn { _, service, request ->
            val reply = host.call(service?.getString(0, UTF8).orEmpty(), request?.getString(0, UTF8).orEmpty())
            val bytes = reply.encodeToByteArray()
            Pointer(Native.malloc(bytes.size + 1L)).apply {
                write(0, bytes, 0, bytes.size)
                setByte(bytes.size.toLong(), 0)
            }
        }

    private val free = FreeFn { ptr -> ptr?.let { Native.free(Pointer.nativeValue(it)) } }

    /** True when [library] exports `karandash_set_host` and accepted the channel; a v1 guest has no such export. */
    fun register(library: NativeLibrary): Boolean {
        val setHost =
            try {
                library.getFunction(NativeAbi.SET_HOST)
            } catch (_: UnsatisfiedLinkError) {
                return false
            }
        return setHost.invokeInt(arrayOf(NativeAbi.HOST_ABI, Pointer.NULL, call, free)) == 1
    }

    private companion object {
        const val UTF8 = "UTF-8"
    }
}
