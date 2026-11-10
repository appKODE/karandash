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
import platform.posix.WSAData
import platform.windows.AF_UNSPEC
import platform.windows.EAI_NODATA
import platform.windows.EAI_NONAME
import platform.windows.NI_NUMERICHOST
import platform.windows.WSACleanup
import platform.windows.WSAStartup
import platform.windows.addrinfo
import platform.windows.freeaddrinfo
import platform.windows.getaddrinfo
import platform.windows.getnameinfo

private const val NUMERIC_HOST_MAX = 1025

/** Winsock 2.2, `MAKEWORD(2, 2)`. */
private const val WINSOCK_VERSION: UShort = 0x0202u

/**
 * Winsock getaddrinfo. Each lookup brackets itself with WSAStartup/WSACleanup (reference-counted by Winsock), so it
 * works whether or not anything else in the process initialised Winsock. ws2_32 is linked by `karandash.kmp-library`.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual suspend fun resolveHostAddresses(host: String): List<String> =
    withContext(Dispatchers.IO) {
        memScoped {
            // Winsock unavailable: nothing can be vetted, so fail closed
            require(
                WSAStartup(WINSOCK_VERSION, alloc<WSAData>().ptr) == 0
            ) { "Cannot resolve host '$host' to check it for SSRF (WSAStartup failed)" }
            try {
                lookup(host)
            } finally {
                WSACleanup()
            }
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.lookup(host: String): List<String> {
    val hints = alloc<addrinfo>().apply { ai_family = AF_UNSPEC }
    val result = allocPointerTo<addrinfo>()
    val rc = getaddrinfo(host, null, hints.ptr, result.ptr)
    // no such name: the request itself fails with the engine's DNS error, nothing to vet here
    if (rc == EAI_NONAME || rc == EAI_NODATA) return emptyList()
    // any other failure (resolver down, timeout) may succeed for the engine a moment later: fail closed
    require(rc == 0) { "Cannot resolve host '$host' to check it for SSRF (getaddrinfo error $rc)" }
    return try {
        generateSequence(result.value) { it.pointed.ai_next }.mapNotNull { numericHost(it.pointed) }.distinct().toList()
    } finally {
        freeaddrinfo(result.value)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun MemScope.numericHost(info: addrinfo): String? {
    val buffer = allocArray<ByteVar>(NUMERIC_HOST_MAX)
    val rc = getnameinfo(info.ai_addr, info.ai_addrlen.convert(), buffer, NUMERIC_HOST_MAX.convert(), null, 0.convert(), NI_NUMERICHOST)
    return if (rc == 0) buffer.toKString() else null
}
