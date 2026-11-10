package io.karandash.sdk.finding

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.models.finding.Finding
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.KSerializer

/**
 * Typed key for a [Finding.metadata] entry. The value is stored JSON-encoded under [name],
 * so `metadata` stays `Map<String, String>`.
 */
@ExperimentalKarandashApi
public class MetaKey<T>(
    public val name: String,
    public val serializer: KSerializer<T>,
)

/** Returns a copy of this finding with [value] stored under [key]. */
@ExperimentalKarandashApi
public fun <T> Finding.withMeta(
    key: MetaKey<T>,
    value: T,
): Finding = copy(metadata = metadata + (key.name to KarandashJson.Default.encodeToString(key.serializer, value)))

/** Returns the value stored under [key], or null when absent. */
@ExperimentalKarandashApi
public fun <T> Finding.meta(key: MetaKey<T>): T? = metadata[key.name]?.let { KarandashJson.Default.decodeFromString(key.serializer, it) }
