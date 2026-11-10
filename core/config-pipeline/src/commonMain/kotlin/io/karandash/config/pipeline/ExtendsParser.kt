package io.karandash.config.pipeline

import io.karandash.api.config.parseConfigDuration
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.config.KarandashLimits
import io.karandash.config.MergeStrategy
import io.karandash.config.merge.MergeMode
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.config.schema.ConfigSections
import io.karandash.duration.KarandashDuration
import io.karandash.http.HttpAuth
import io.karandash.manifest.CachePolicy
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import io.karandash.manifest.RemoteFileResolution
import io.karandash.normalize.ConfigKeyNormalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Keys an `extends:` object entry is read with, camelCase (kebab-case and snake_case are normalized first). The parser
 * reads every key through [EntryKey], so this set cannot drift from it; `docs/reference/configuration-schema.md` is
 * checked against it.
 */
val EXTENDS_ENTRY_KEYS: Set<String> = EntryKey.entries.map { it.key }.toSet()

/** Keys of an entry's `cache:` object, read by the parser and checked against the docs. */
val EXTENDS_CACHE_KEYS: Set<String> = setOf(KEY_TTL)

private enum class EntryKey(
    val key: String,
) {
    URL("url"),
    PATTERN("pattern"),
    IF("if"),
    CACHE("cache"),
    MERGE_STRATEGY("mergeStrategy"),
    FILE_RESOLUTION("fileResolution"),
    AUTH("auth"),
    TRUST("trust"),
}

private val KEY_URL = EntryKey.URL.key
private val KEY_PATTERN = EntryKey.PATTERN.key
private val KEY_IF = EntryKey.IF.key
private val KEY_CACHE = EntryKey.CACHE.key
private val KEY_MERGE_STRATEGY = EntryKey.MERGE_STRATEGY.key
private val KEY_FILE_RESOLUTION = EntryKey.FILE_RESOLUTION.key
private val KEY_AUTH = EntryKey.AUTH.key
private val KEY_TRUST = EntryKey.TRUST.key
private const val KEY_TTL = "ttl"

/**
 * Parses the `extends:` config section into [ExtendsEntry] objects.
 *
 * Supports string, object, and array forms. Extracted from [ConfigMapParser]
 * to keep the main parser under the 500-line threshold.
 */
internal object ExtendsParser {
    /**
     * Extract extends entries from a [JsonObject], parsing all forms into [ExtendsEntry]. `${VAR}` in urls, patterns
     * and `auth:` values falls back to [env], the `env:` defaults in scope for [data].
     */
    fun parse(
        data: JsonObject,
        env: Map<String, String>,
        maxEntries: Int = KarandashLimits.MAX_EXTENDS_ENTRIES,
    ): List<ExtendsEntry> =
        when (val raw = data[ConfigSections.EXTENDS]) {
            null, is JsonNull -> {
                emptyList()
            }

            is JsonPrimitive -> {
                if (!raw.isString) {
                    throw ConfigException(
                        "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid extends value: expected string, object, or array",
                    )
                }
                listOf(parseSingleFromString(raw.content, env))
            }

            is JsonObject -> {
                listOf(parseObjectEntry(raw, 0, env, data))
            }

            is JsonArray -> {
                if (raw.size > maxEntries) {
                    throw ConfigException(
                        "${KarandashError.TOO_MANY_EXTENDS_ENTRIES.bracketCode} Too many entries in extends array: ${raw.size} (max: $maxEntries)",
                    )
                }
                raw.mapIndexed { index, item ->
                    when (item) {
                        is JsonPrimitive if item.isString -> {
                            parseSingleFromString(item.content, env)
                        }

                        is JsonObject -> {
                            parseObjectEntry(item, index, env, data)
                        }

                        else -> {
                            throw ConfigException(
                                "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid extends entry at index $index: expected string or object",
                            )
                        }
                    }
                }
            }
        }

    private fun parseSingleFromString(
        value: String,
        env: Map<String, String>,
    ): ExtendsEntry {
        val interpolated = ConfigMapParser.interpolate(value, env)
        return when {
            interpolated.startsWith("@") -> ExtendsEntry.ManifestRef(name = interpolated.substring(1))
            isGlobPattern(interpolated) -> ExtendsEntry.GlobEntry(pattern = interpolated)
            else -> ExtendsEntry.UrlEntry(source = ConfigSource(url = interpolated))
        }
    }

    private fun parseObjectEntry(
        raw: JsonObject,
        index: Int,
        env: Map<String, String>,
        layer: JsonObject,
    ): ExtendsEntry {
        // entry keys in any spelling (`merge-strategy`, `mergeStrategy`); nested values kept as written
        val obj = JsonObject(raw.mapKeys { (key, _) -> ConfigKeyNormalizer.kebabToCamelCase(key) })
        // Kept raw: ConditionEvaluator resolves ${VAR} itself, so a missing variable compares as "" instead of vanishing.
        val condition = (obj[KEY_IF] as? JsonPrimitive)?.content
        val cache = parseCachePolicy(obj[KEY_CACHE] as? JsonObject)
        val mergeStrategy = parseMergeStrategy(obj[KEY_MERGE_STRATEGY] as? JsonObject, index)
        val topLevelMerge = parseTopLevelMerge(obj[KEY_MERGE_STRATEGY] as? JsonPrimitive, index)

        return when {
            obj.containsKey(KEY_PATTERN) -> {
                val pattern = (obj[KEY_PATTERN] as? JsonPrimitive)?.content
                    ?: throw ConfigException(
                        "${KarandashError.MISSING_REQUIRED_FIELD.bracketCode} Missing 'pattern' value in extends object at index $index",
                    )
                ExtendsEntry.GlobEntry(
                    pattern = ConfigMapParser.interpolate(pattern, env),
                    condition = condition,
                    cache = cache,
                    mergeStrategy = mergeStrategy,
                    topLevelMerge = topLevelMerge,
                )
            }

            obj.containsKey(KEY_URL) -> {
                val url = (obj[KEY_URL] as? JsonPrimitive)?.content
                    ?: throw ConfigException(
                        "${KarandashError.MISSING_REQUIRED_FIELD.bracketCode} Missing 'url' value in extends object at index $index",
                    )
                val interpolated = ConfigMapParser.interpolate(url, env)
                if (interpolated.startsWith("@")) {
                    return ExtendsEntry.ManifestRef(
                        name = interpolated.substring(1),
                        condition = condition,
                        mergeStrategy = mergeStrategy,
                        topLevelMerge = topLevelMerge,
                    )
                }
                val authValue = obj[KEY_AUTH]
                val named = (authValue as? JsonPrimitive)
                    ?.takeIf { it.isString }
                    ?.let { NamedExtendsAuth.resolve(it.content, layer, env, index) }
                val authObj = named?.raw ?: authValue as? JsonObject
                val auth = named?.config?.toHttpAuth() ?: parseAuth(authObj, env)
                val fileResolution = (obj[KEY_FILE_RESOLUTION] as? JsonPrimitive)
                    ?.content
                    ?.let { parseFileResolution(it, "in extends object at index $index") }
                    ?: named?.config?.fileResolution?.let { parseFileResolution(it, "in auth entry used at extends index $index") }
                    ?: RemoteFileResolution.LOCAL
                ExtendsEntry.UrlEntry(
                    source = ConfigSource(
                        url = interpolated,
                        auth = auth,
                        emptyAuthVars = emptyAuthVars(authObj, env),
                        trusted = (obj[KEY_TRUST] as? JsonPrimitive)?.booleanOrNull == true,
                    ),
                    condition = condition,
                    cache = cache,
                    mergeStrategy = mergeStrategy,
                    fileResolution = fileResolution,
                    topLevelMerge = topLevelMerge,
                )
            }

            else -> {
                throw ConfigException(
                    "${KarandashError.MISSING_REQUIRED_FIELD.bracketCode} Missing required field 'url' or 'pattern' in extends object at index $index",
                )
            }
        }
    }

    /** [value] as a [RemoteFileResolution]; [where] names the place it was written, for the error. */
    internal fun parseFileResolution(
        value: String,
        where: String,
    ): RemoteFileResolution =
        RemoteFileResolution.fromString(value)
            ?: throw ConfigException(
                "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid file-resolution value '$value' " +
                    "$where. Expected: 'local', 'fallback-remote', 'fallback-local', or 'strict'",
            )

    private fun parseCachePolicy(data: JsonObject?): CachePolicy? {
        if (data == null) return null
        val ttl = (data[KEY_TTL] as? JsonPrimitive)?.let { prim ->
            prim.content
                .toLongOrNull()
                ?.let { seconds -> if (seconds == NEVER_TTL_SECONDS) KarandashDuration.NEVER else KarandashDuration.ofSeconds(seconds) }
                ?: parseConfigDuration("cache.ttl", prim.content)
        } ?: DEFAULT_CACHE_TTL
        return CachePolicy(ttl = ttl)
    }

    /** A string `merge-strategy` (`deep`, `shallow`, `replace`) sets how the whole entry merges. */
    private fun parseTopLevelMerge(
        value: JsonPrimitive?,
        index: Int,
    ): MergeStrategy {
        if (value == null || !value.isString) return MergeStrategy.DEEP
        return try {
            MergeStrategy.fromString(value.content)
        } catch (e: IllegalArgumentException) {
            throw ConfigException(
                "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid merge-strategy '${value.content}' in extends " +
                    "object at index $index. Expected 'deep', 'shallow', 'replace' or a map of field paths to array modes",
                e,
            )
        }
    }

    /** A mode no [MergeMode] matches fails the config instead of silently falling back to replace. */
    private fun parseMergeStrategy(
        data: JsonObject?,
        index: Int,
    ): MergeStrategyMap? {
        if (data == null) return null
        return data.entries
            .associate { (fieldPath, modeValue) ->
                val raw = (modeValue as? JsonPrimitive)?.content
                val mode = raw?.let { MergeMode.fromString(it) }
                    ?: throw ConfigException(
                        "${KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode} Invalid merge-strategy mode '$raw' for field " +
                            "'$fieldPath' in extends object at index $index. Expected replace, append, prepend, merge or merge-by-key",
                    )
                fieldPath to mode
            }.takeIf { it.isNotEmpty() }
    }

    private fun parseAuth(
        data: JsonObject?,
        env: Map<String, String>,
    ): HttpAuth? {
        if (data == null) return null
        return ExtendsAuthParser.fromJsonObject(
            data = data,
            missingFieldCode = KarandashError.MISSING_REQUIRED_FIELD.bracketCode,
            invalidTypeCode = KarandashError.INVALID_CONFIG_VALUE_TYPE.bracketCode,
            interpolate = { value -> ConfigMapParser.interpolate(value, env) },
        )
    }

    /** Env vars named by an auth value (other than `type`) that interpolates to blank: unset or set to `""`. */
    private fun emptyAuthVars(
        data: JsonObject?,
        env: Map<String, String>,
    ): List<String> =
        data
            .orEmpty()
            .filterKeys { it != "type" }
            .values
            .filterIsInstance<JsonPrimitive>()
            .filter { it.isString }
            .flatMap { value ->
                val names = mutableListOf<String>()
                val resolved = ConfigMapParser.interpolate(value.content, env, onResolve = { name, _, _ -> names += name })
                if (resolved.isBlank()) names else emptyList()
            }.distinct()

    private fun isGlobPattern(value: String): Boolean {
        val hasGlobChars = value.contains('*') || value.contains('?') || value.contains('{')
        val isHttpUrl = value.startsWith("http://") || value.startsWith("https://")
        return hasGlobChars && !isHttpUrl
    }

    private val DEFAULT_CACHE_TTL = KarandashDuration.ofSeconds(3600)

    /** `ttl: -1` means never expire. */
    private const val NEVER_TTL_SECONDS = -1L
}
