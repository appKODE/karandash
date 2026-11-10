@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.loader.native

import io.karandash.loader.NativeAbi
import io.karandash.loader.bridge.host.HostReply
import io.karandash.loader.bridge.host.HostServiceDispatcher
import io.karandash.platform.findNativeSymbol
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.invoke
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import platform.posix.free
import platform.posix.strdup

/**
 * Hands a dlopen'ed v2 guest the host-service channel through `karandash_set_host(abi, host_ctx, call_fn, free_fn)`.
 * A C callback cannot capture state, so `host_ctx` is a [StableRef] to this guest's [host]; [dispose] releases it
 * when the library is closed. Responses are `strdup`'ed here and freed by the guest through `free_fn`.
 */
internal class CInteropHostChannel(
    host: HostServiceDispatcher,
) {
    private val ref = StableRef.create(host)

    /** True when [handle] exports `karandash_set_host` and accepted the channel; a v1 guest has no such export. */
    fun register(handle: COpaquePointer): Boolean {
        val setHost =
            findNativeSymbol(handle, NativeAbi.SET_HOST)
                ?.reinterpret<CFunction<(Int, COpaquePointer?, COpaquePointer?, COpaquePointer?) -> Int>>()
                ?: return false
        return setHost(NativeAbi.HOST_ABI, ref.asCPointer(), CALL, FREE) == 1
    }

    fun dispose() = ref.dispose()

    private companion object {
        val CALL =
            staticCFunction { ctx: COpaquePointer?, service: CPointer<ByteVar>?, request: CPointer<ByteVar>? ->
                val reply =
                    ctx?.asStableRef<HostServiceDispatcher>()?.get()?.call(service?.toKString().orEmpty(), request?.toKString().orEmpty())
                        ?: HostReply.err("no host")
                strdup(reply)
            }

        val FREE = staticCFunction { ptr: CPointer<ByteVar>? -> free(ptr) }
    }
}
