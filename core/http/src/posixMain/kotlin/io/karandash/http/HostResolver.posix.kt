package io.karandash.http

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.MemScope
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.allocPointerTo
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.posix.AF_UNSPEC
import platform.posix.EAI_NONAME
import platform.posix.NI_NUMERICHOST
import platform.posix.addrinfo
import platform.posix.freeaddrinfo
import platform.posix.getaddrinfo
import platform.posix.getnameinfo

private const val NUMERIC_HOST_MAX = 1025

/** `EAI_NODATA` (name exists, no address): macOS exports it, glibc keeps it behind `__USE_GNU`. */
internal expect val EAI_NO_ADDRESS: Int

@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun resolveHostAddresses(host: String): List<String> =
    withContext(Dispatchers.IO) {
        memScoped {
            val hints = alloc<addrinfo>().apply { ai_family = AF_UNSPEC }
            val result = allocPointerTo<addrinfo>()
            val rc = getaddrinfo(host, null, hints.ptr, result.ptr)
            // no such name: the request itself fails with the engine's DNS error, nothing to vet here
            if (rc == EAI_NONAME || rc == EAI_NO_ADDRESS) return@memScoped emptyList()
            // any other failure (resolver down, timeout) may succeed for the engine a moment later: fail closed
            require(rc == 0) { "Cannot resolve host '$host' to check it for SSRF (getaddrinfo error $rc)" }
            try {
                generateSequence(result.value) { it.pointed.ai_next }.mapNotNull { numericHost(it.pointed) }.distinct().toList()
            } finally {
                freeaddrinfo(result.value)
            }
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.numericHost(info: addrinfo): String? {
    val buffer = allocArray<ByteVar>(NUMERIC_HOST_MAX)
    val rc = getnameinfo(info.ai_addr, info.ai_addrlen.convert(), buffer, NUMERIC_HOST_MAX.convert(), null, 0.convert(), NI_NUMERICHOST)
    return if (rc == 0) buffer.toKString() else null
}
