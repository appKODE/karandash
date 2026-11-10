package io.karandash.cache

/**
 * Serialization contract for file-tier cache entries.
 *
 * Implementations convert cache keys and values to/from strings
 * for persistent storage via [io.karandash.cache.cas.CasCache].
 */
interface CacheSerializer<K, V> {
    /** Serialize a cache key to a stable string representation for hashing. */
    fun serializeKey(key: K): String

    /** Serialize a cache value to a string for file storage. */
    fun serializeValue(value: V): String

    /** Deserialize a value from its string representation. */
    fun deserializeValue(data: String): V
}
