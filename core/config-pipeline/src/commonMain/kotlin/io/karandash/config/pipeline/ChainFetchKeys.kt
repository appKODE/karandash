package io.karandash.config.pipeline

import io.karandash.cache.DownloadsCacheConfig
import io.karandash.config.GlobLimitsConfig
import io.karandash.config.LimitsConfig
import io.karandash.config.schema.ConfigSections
import io.karandash.logger.Logger
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.deepNormalizeKeys
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * The chain-fetch keys of a config: `limits`, `cache.downloads`, `allow-localhost` and `allow-private-network`,
 * which govern fetching the `extends:` chain itself. They inherit like any other key: before the parents of a
 * layer are fetched, each key takes the merge of the layers on the path from the root down to that layer
 * (the closer layer wins, the root over everything), so a parent's setting applies to fetching its own parents.
 *
 * Values are `${VAR}`-interpolated like the run does ([ConfigMapParser.deepInterpolateValues], with
 * `KarandashEnvSecurity.DENYLIST`), against the `env:` defaults merged the same way; after interpolation a
 * non-boolean flag counts as unset.
 */
object ChainFetchKeys {
    private const val DOWNLOADS = "downloads"
    private const val FETCH_TIMEOUT = "fetchTimeout"
    private const val GLOB = "glob"
    private const val ALLOW_LOCALHOST = "allowLocalhost"
    private const val ALLOW_PRIVATE_NETWORK = "allowPrivateNetwork"
    private const val ENABLED = "enabled"
    private const val TTL = "ttl"
    private val EMPTY = JsonObject(emptyMap())

    /**
     * The chain-fetch keys of [layer] (any key spelling), normalized to camelCase, plus its `env:` block as is (the
     * defaults their `${VAR}` references resolve against); all other keys dropped.
     */
    fun of(layer: JsonElement?): JsonObject {
        val obj = layer as? JsonObject ?: return EMPTY
        val top = obj.entries.associate { (key, value) -> ConfigKeyNormalizer.kebabToCamelCase(key) to value }
        val downloads = (top[ConfigSections.CACHE] as? JsonObject)
            ?.entries
            ?.firstOrNull { ConfigKeyNormalizer.kebabToCamelCase(it.key) == DOWNLOADS }
            ?.value
        val keys = buildMap {
            top[ConfigSections.LIMITS]?.let { put(ConfigSections.LIMITS, it) }
            downloads?.let { put(ConfigSections.CACHE, JsonObject(mapOf(DOWNLOADS to it))) }
            top[ALLOW_LOCALHOST]?.let { put(ALLOW_LOCALHOST, it) }
            top[ALLOW_PRIVATE_NETWORK]?.let { put(ALLOW_PRIVATE_NETWORK, it) }
        }
        val normalized = deepNormalizeKeys(JsonObject(keys)) as JsonObject
        // env var names are kept verbatim; a malformed `env:` is reported by the full parse
        val env = obj[ConfigSections.ENV]?.takeIf { it is JsonObject || it is JsonArray } ?: return normalized
        return JsonObject(normalized + (ConfigSections.ENV to env))
    }

    /** [closer] over [farther], key by key: the layer closer to the root wins. */
    fun merge(
        farther: JsonObject,
        closer: JsonObject,
    ): JsonObject = JsonElementMerger.mergeLayers(farther, closer) as? JsonObject ?: closer

    /** `allow-localhost` of [keys]; false when unset or not a boolean. */
    fun allowLocalhost(keys: JsonObject): Boolean = flag(resolve(keys), ALLOW_LOCALHOST) == true

    /** `allow-private-network` of [keys]; null when unset or not a boolean. */
    fun allowPrivateNetwork(keys: JsonObject): Boolean? = flag(resolve(keys), ALLOW_PRIVATE_NETWORK)

    /** `limits` of [keys]; a malformed block gives defaults, with a warning. */
    fun limits(
        keys: JsonObject,
        logger: Logger,
    ): LimitsConfig =
        try {
            ConfigMapParser.parseRootLimits(resolve(keys))
        } catch (e: IllegalArgumentException) {
            // includes SerializationException
            logger.warn("Ignoring 'limits' for config resolution (${e.message}); using defaults")
            LimitsConfig()
        }

    /** `limits.fetchTimeout` of [keys] in millis (clamped); null when unset. */
    fun fetchTimeoutMs(
        keys: JsonObject,
        logger: Logger,
    ): Long? = if (hasLimit(keys, FETCH_TIMEOUT)) limits(keys, logger).effectiveFetchTimeout.millis else null

    /** `limits.glob` of [keys]; null when unset. */
    fun glob(
        keys: JsonObject,
        logger: Logger,
    ): GlobLimitsConfig? = if (hasLimit(keys, GLOB)) limits(keys, logger).glob else null

    /** `cache.downloads` of [keys]; null when unset, defaults (with a warning) when malformed. */
    fun downloads(
        keys: JsonObject,
        logger: Logger,
    ): DownloadsCacheConfig? {
        if (ConfigSections.CACHE !in keys) return null
        return try {
            ConfigMapParser.parseRootDownloadsCache(resolve(keys))
        } catch (e: IllegalArgumentException) {
            // includes SerializationException
            logger.warn("Ignoring 'cache.downloads' for config resolution (${e.message}); using defaults")
            DownloadsCacheConfig()
        }
    }

    /** [fetcher] with the `allow-localhost` and `limits.fetchTimeout` of [keys]; unset keys keep the fetcher's own. */
    fun fetcherFor(
        fetcher: RemoteConfigFetcher,
        keys: JsonObject,
        logger: Logger,
    ): RemoteConfigFetcher = fetcher.withSettings(allowLocalhost(keys), fetchTimeoutMs(keys, logger))

    /**
     * [cache] fetching through [fetcher] under the `cache.downloads` of [keys], field by field: a field [keys] do not
     * set keeps the layer's own value.
     */
    fun cacheFor(
        cache: CacheLayer,
        fetcher: RemoteConfigFetcher,
        keys: JsonObject,
        logger: Logger,
    ): CacheLayer {
        val set = ((keys[ConfigSections.CACHE] as? JsonObject)?.get(DOWNLOADS) as? JsonObject)?.keys.orEmpty()
        val downloads = downloads(keys, logger)
        return cache.with(
            fetcher,
            ttl = downloads?.ttl?.takeIf { TTL in set },
            enabled = downloads?.enabled?.takeIf { ENABLED in set },
        )
    }

    /** The `env:` defaults merged into [keys]. */
    fun envDefaults(keys: JsonObject): Map<String, String> =
        keys[ConfigSections.ENV]
            ?.let { ConfigMapParser.parseEnvBlock(JsonObject(mapOf(ConfigSections.ENV to it))).defaults }
            .orEmpty()

    /** [keys] without `env:`, `${VAR}`-interpolated against its defaults. */
    private fun resolve(keys: JsonObject): JsonObject =
        ConfigMapParser.deepInterpolateValues(JsonObject(keys - ConfigSections.ENV), envDefaults(keys)) as JsonObject

    private fun hasLimit(
        keys: JsonObject,
        name: String,
    ): Boolean = (keys[ConfigSections.LIMITS] as? JsonObject)?.containsKey(name) == true

    private fun flag(
        keys: JsonObject,
        name: String,
    ): Boolean? = (keys[name] as? JsonPrimitive)?.booleanOrNull
}
