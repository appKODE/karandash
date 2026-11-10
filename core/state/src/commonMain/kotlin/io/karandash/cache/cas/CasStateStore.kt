package io.karandash.cache.cas

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.module.ExtensionStateStore
import io.karandash.duration.KarandashDuration

/** Adapts a [CasCache] to the SPI [ExtensionStateStore]; [KarandashDuration.NEVER] maps to a far-future expiry. */
@OptIn(ExperimentalKarandashApi::class)
fun CasCache.asStateStore(
    ttl: KarandashDuration,
    reuseCached: Boolean = true,
): ExtensionStateStore {
    val cas = this
    val ttlMs = if (ttl == KarandashDuration.NEVER) Long.MAX_VALUE / 2 else ttl.millis
    return object : ExtensionStateStore {
        override val ttl: KarandashDuration = ttl
        override val reuseCached: Boolean = reuseCached

        override fun get(key: String): ByteArray? = cas.get(key)

        override suspend fun fetch(key: String): ByteArray? = cas.fetch(key)

        override fun put(
            key: String,
            content: ByteArray,
        ) = cas.put(key, content, ttlMs)

        override fun invalidate(key: String) = cas.invalidate(key)

        override fun keys(): List<String> = cas.keys()
    }
}
