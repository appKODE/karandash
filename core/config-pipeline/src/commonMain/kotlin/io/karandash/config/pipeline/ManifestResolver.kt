package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.readText
import io.karandash.platform.safeName
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Parses YAML/JSON content into a [JsonElement].
 *
 * Injected into [ManifestResolver] to decouple it from the SDK's [io.karandash.api.module.ConfigFormatModule].
 */
fun interface ManifestContentParser {
    suspend fun parse(content: String): JsonElement
}

/**
 * Resolver for `.karandash/extends-manifest.<ext>` files, in any registered config format.
 *
 * Manifest files allow defining named sets of extends entries for reuse across projects.
 * Entries can reference these sets using the @name syntax in extends fields.
 *
 * Example manifest file:
 * ```yaml
 * extends-sets:
 *   standard:
 *     - "https://company.com/base.yaml"
 *     - url: "https://company.com/security.yaml"
 *       auth:
 *         type: bearer-token
 *         token: "${TOKEN}"
 *   minimal:
 *     - "https://company.com/base.yaml"
 * ```
 *
 * Usage: `extends: "@standard"` resolves to the entries defined under the `standard` set.
 *
 * @property baseDir Base directory to search for the manifest file (defaults to current working directory)
 * @property extensions File extensions probed as `.karandash/extends-manifest.<ext>`; more than one present is an error.
 * @property parserFor Parser for a manifest file name, null when no format reads it.
 */
class ManifestResolver private constructor(
    private val baseDir: Path,
    private val extensions: List<String>,
    private val parserFor: (String) -> ManifestContentParser?,
) {
    /** Reads only `.karandash/extends-manifest.yaml` with [contentParser]. */
    constructor(
        baseDir: Path = currentWorkingDirectory(),
        contentParser: ManifestContentParser,
    ) : this(baseDir, listOf(DEFAULT_EXTENSION), { contentParser })

    /** Probes every extension [formatRegistry] reads and parses the manifest with the module for its extension. */
    constructor(
        baseDir: Path,
        formatRegistry: ConfigFormatRegistry,
    ) : this(
        baseDir,
        formatRegistry.allExtensions().sorted(),
        { name -> formatRegistry.getModuleForFile(name)?.let { module -> ManifestContentParser { module.parseToJsonElement(it) } } },
    )

    companion object {
        const val MANIFEST_PATH = ".karandash/extends-manifest.yaml"
        private const val MANIFEST_STEM = ".karandash/extends-manifest"
        private const val DEFAULT_EXTENSION = "yaml"
    }

    /**
     * Resolve a named extends set from the manifest file.
     *
     * @param name Name of the extends set to resolve (without @ prefix)
     * @param env `env:` defaults of the config naming the set; `${VAR}` in its entries falls back to them
     * @return List of UrlEntry instances from the manifest set
     * @throws ConfigException with E0017 if manifest file not found
     * @throws ConfigException with E0018 if named set not found in manifest
     */
    suspend fun resolve(
        name: String,
        env: Map<String, String>,
    ): List<ExtendsEntry.UrlEntry> {
        val extendsSets = loadExtendsSets()

        val setArray = extendsSets[name] as? JsonArray
            ?: run {
                val availableSets = extendsSets.keys.joinToString(", ")
                throw ConfigException(
                    "${KarandashError.MANIFEST_SET_NOT_FOUND.bracketCode} Extends set '@$name' not found in manifest. Available: $availableSets"
                )
            }

        return setArray.mapIndexedNotNull { index, entry ->
            parseManifestEntry(entry, index, name, env)
        }
    }

    /**
     * List all available extends sets in the manifest file, interpolated without `env:` defaults (no config is read).
     *
     * @return Map of set names to their entry URLs (simplified view)
     * @throws ConfigException with E0017 if manifest file not found
     */
    suspend fun listSets(): Map<String, List<String>> {
        val extendsSets = loadExtendsSets()
        return extendsSets.mapValues { (_, value) ->
            val entries = value as? JsonArray ?: return@mapValues emptyList()
            entries.mapNotNull { entry ->
                when (entry) {
                    is JsonPrimitive -> if (entry.isString) ConfigMapParser.interpolate(entry.content, emptyMap()) else null

                    is JsonObject -> (entry["url"] as? JsonPrimitive)?.content?.let {
                        ConfigMapParser.interpolate(it, emptyMap())
                    }

                    else -> null
                }
            }
        }
    }

    /**
     * Load and parse the manifest file, returning the `extends-sets` [JsonObject].
     *
     * @throws ConfigException with E0017 if no manifest file, more than one, or an invalid one is found.
     */
    private suspend fun loadExtendsSets(): JsonObject {
        val candidates = extensions.map { Path(baseDir.toString(), "$MANIFEST_STEM.$it").absolutePathOrSelf() }
        val found = candidates.filter { SystemFileSystem.exists(it) }
        if (found.size > 1) {
            throw ConfigException(
                "${KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode} More than one extends manifest found: " +
                    "${found.joinToString(", ")}; keep one"
            )
        }
        val manifestPath = found.singleOrNull()
            ?: throw ConfigException(
                "${KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode} Extends manifest file not found at " +
                    candidates.joinToString(" or ")
            )
        val parser = checkNotNull(parserFor(manifestPath.safeName)) { "no config format reads $manifestPath" }
        val manifestData = parseManifest(parser, manifestPath.readText())
        return manifestData["extends-sets"] as? JsonObject
            ?: throw ConfigException(
                "${KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode} Invalid manifest file: missing 'extends-sets' section"
            )
    }

    /**
     * Parse a single entry from a manifest set into a UrlEntry.
     *
     * @param entry Raw entry ([JsonPrimitive] string or [JsonObject])
     * @param index Entry index in the set (for error messages)
     * @param setName Name of the set (for error messages)
     * @return UrlEntry or null if entry cannot be parsed
     */
    private fun parseManifestEntry(
        entry: kotlinx.serialization.json.JsonElement,
        index: Int,
        setName: String,
        env: Map<String, String>,
    ): ExtendsEntry.UrlEntry? {
        return when (entry) {
            is JsonPrimitive -> {
                if (!entry.isString) return null
                val interpolated = ConfigMapParser.interpolate(entry.content, env)
                ExtendsEntry.UrlEntry(
                    source = ConfigSource(url = interpolated)
                )
            }

            is JsonObject -> {
                val url = (entry["url"] as? JsonPrimitive)?.content
                    ?: throw ConfigException(
                        "${KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode} Invalid entry at index $index in set '@$setName': missing 'url' field"
                    )

                val auth = ExtendsAuthParser.fromJsonObject(
                    data = entry["auth"] as? JsonObject,
                    missingFieldCode = KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode,
                    invalidTypeCode = KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode,
                    interpolate = { value -> ConfigMapParser.interpolate(value, env) },
                )

                ExtendsEntry.UrlEntry(
                    source = ConfigSource(
                        url = ConfigMapParser.interpolate(url, env),
                        auth = auth,
                    )
                )
            }

            else -> {
                null
            }
        }
    }

    /** Parse manifest content into a [JsonObject] with [parser]. */
    private suspend fun parseManifest(
        parser: ManifestContentParser,
        content: String,
    ): JsonObject =
        try {
            val element = parser.parse(content)
            element as? JsonObject ?: JsonObject(emptyMap())
        } catch (e: IllegalArgumentException) {
            throw ConfigException("${KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode} Invalid manifest syntax: ${e.message}")
        }
}
