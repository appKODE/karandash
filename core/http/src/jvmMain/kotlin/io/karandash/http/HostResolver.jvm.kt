package io.karandash.http

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.InetAddress
import java.net.UnknownHostException

internal actual suspend fun resolveHostAddresses(host: String): List<String> =
    withContext(Dispatchers.IO) {
        try {
            InetAddress.getAllByName(host).mapNotNull { it.hostAddress }
        } catch (e: UnknownHostException) {
            // NXDOMAIN and resolver failures look the same here; the engine resolves through the same InetAddress,
            // whose negative cache (10 s by default) makes it fail on the same name too, so nothing to vet
            emptyList()
        }
    }
