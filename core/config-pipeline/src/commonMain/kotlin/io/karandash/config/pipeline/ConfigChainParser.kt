package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.RemoteFileResolution
import io.karandash.normalize.ConfigKeyNormalizer
import io.karandash.normalize.deepNormalizeKeys
import io.karandash.normalize.normalizedKeyCollisionMessage
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.safeName
import io.karandash.platform.safeParent
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Second stage of two-stage config inheritance: parse and merge the resolved chain.
 *
 * Given a [ConfigChain] (ordered deepest-ancestor-first), parses all entries in
 * parallel, resolves `{ file: "..." }` references per-entry against each entry's
 * own source (local or remote), normalizes each entry's keys to camelCase, and deep-merges them in order.
 * The root entry (last) wins on conflicts.
 *
 * @param formatRegistry Registry of available config format modules.
 * @param fetcher Optional remote config fetcher for resolving `{ file: "..." }` references
 *   in remote configs. When null, a remote config holding a file reference fails with [ConfigException].
 * @param onEntryParsed Receives each entry's source URL and parsed element in merge order
 *   (deepest ancestor first, root last) - lets callers tell which file set a key.
 */
class ConfigChainParser(
    private val formatRegistry: ConfigFormatRegistry,
    private val fetcher: RemoteConfigFetcher? = null,
    private val onWarning: ((String) -> Unit)? = null,
    private val logger: Logger = NoOpLogger,
    private val onEntryParsed: ((url: String, element: JsonElement) -> Unit)? = null,
    /** Set for a base validated on its own: a missing local `{ file: }` is reported here and resolves to `""`. */
    private val onMissingFile: ((reference: String, resolved: Path) -> Unit)? = null,
    /** Module-reported [io.karandash.api.Module.preserveKeys], kept as written on top of [ConfigMapParser.CONFIG_PRESERVE_KEYS]. */
    extraPreserveKeys: Set<String> = emptySet(),
) {
    private val preserveKeys: Set<String> = ConfigMapParser.CONFIG_PRESERVE_KEYS + extraPreserveKeys

    /**
     * Parse all chain entries in parallel and merge them into a single [JsonElement].
     *
     * Each entry's `{ file: "..." }` references are resolved against its own source
     * before merging: local entries resolve from the filesystem, remote entries fetch
     * from the remote server using the same auth as the parent `extends:` entry.
     *
     * Merge order: entry[0] (deepest ancestor) is base; each subsequent entry overrides.
     * entry[last] (root) wins on all conflicts.
     *
     * @param chain The [ConfigChain] to parse and merge.
     * @return Single merged [JsonElement] representing the full resolved config.
     */
    suspend fun parseAndMerge(chain: ConfigChain): JsonElement =
        coroutineScope {
            // Derive the local config directory from the root (last) local entry.
            // Remote file references with fallback-remote/fallback-local resolve
            // local paths relative to this directory (e.g. .karandash/).
            val localConfigDir = chain.entries
                .lastOrNull { entry ->
                    val url = entry.source.url
                    !url.startsWith("http://") && !url.startsWith("https://")
                }?.let { Path(it.source.url).safeParent }

            logger.debug("ConfigChainParser: resolving ${chain.entries.size} chain entry(s)")
            val parsedElements = chain.entries
                .map { entry ->
                    async {
                        logger.debug("ConfigChainParser: parsing chain entry format='${entry.format}' source='${entry.source.url}'")
                        val module = try {
                            formatRegistry.getModule(entry.format)
                        } catch (e: IllegalArgumentException) {
                            throw ConfigException(
                                "${KarandashError.UNSUPPORTED_CONFIG_FORMAT.bracketCode} " +
                                    "Unsupported config format '${entry.format}' for ${entry.source.url}. " +
                                    "Supported formats: ${formatRegistry.allExtensions().joinToString(", ") { ".$it" }}",
                                e
                            )
                        }
                        val element = try {
                            entry.parsed ?: module.parseToJsonElement(entry.content)
                        } catch (e: IllegalArgumentException) {
                            throw ConfigException(
                                "Failed to parse config ${entry.source.url}: ${e.message}",
                                e
                            )
                        }
                        val resolved = resolveFileReferences(element, entry.source, entry.fileResolution, localConfigDir)
                        // Normalize per layer so a parent `dry-run` and a child `dryRun` merge as one key.
                        entry to deepNormalizeKeys(resolved, preserveKeys) { key, originals ->
                            logger.warn("${entry.source.url}: ${normalizedKeyCollisionMessage(key, originals)}")
                        }
                    }
                }.awaitAll()

            logger.debug("ConfigChainParser: merging ${parsedElements.size} parsed entry(s) (deepest ancestor first, root wins)")
            var merged: JsonElement = JsonObject(emptyMap())
            for ((entry, element) in parsedElements) {
                logger.debug("ConfigChainParser: merging entry '${entry.source.url}' with strategy ${entry.mergeStrategy}")
                merged = JsonElementMerger.mergeLayers(
                    merged,
                    element,
                    entry.mergeStrategy?.let(::normalizeStrategyPaths),
                    entry.topLevelMerge,
                )
                onEntryParsed?.invoke(entry.source.url, element)
            }
            logger.debug("ConfigChainParser: chain merge complete, ${parsedElements.size} entry(s) resolved")
            ReportLanguage.applyLayerPrecedence(merged, parsedElements.map { it.second })
        }

    /** Strategy paths name keys of the normalized layers, so their segments are camelCased the same way. */
    private fun normalizeStrategyPaths(strategy: MergeStrategyMap): MergeStrategyMap =
        strategy.mapKeys { (path, _) ->
            path
                .split('.')
                .fold(emptyList<String>()) { segments, segment ->
                    val preserved = segments.lastOrNull() in preserveKeys
                    segments + if (preserved) segment else ConfigKeyNormalizer.kebabToCamelCase(segment)
                }.joinToString(".")
        }

    /**
     * Resolves `{ file: "..." }` references in [element] against [source].
     *
     * Remote sources (http/https) use [fetcher] to download referenced files.
     * When [fileResolution] is [RemoteFileResolution.FALLBACK_LOCAL], a failed remote fetch
     * falls back to resolving against the local working directory with a warning.
     * Local sources resolve from the filesystem using the source path's parent directory.
     *
     * @throws ConfigException when a remote source holds a reference and there is no [fetcher] to resolve it.
     */
    private suspend fun resolveFileReferences(
        element: JsonElement,
        source: ConfigSource,
        fileResolution: RemoteFileResolution,
        localConfigDir: Path?,
    ): JsonElement {
        val url = source.url
        val isRemote = url.startsWith("http://") || url.startsWith("https://")

        if (isRemote && fetcher != null) {
            val parsed = ApiUrlParser.tryParse(url)
            val baseUrl = if (parsed != null) "" else getParentDirectoryOf(url)
            val localBase = localConfigDir ?: currentWorkingDirectory()
            val context = FileResolutionContext.Remote(
                baseUrl = baseUrl,
                auth = source.auth,
                fetcher = fetcher::fetchFileContent,
                localBasePath = localBase,
                resolution = fileResolution,
                onWarning = onWarning,
                parsedApiUrl = parsed,
                // the project root holds `.karandash/`; a remote layer reads no local file above it
                localRoot = if (localBase.safeName == CONFIG_DIR_NAME) localBase.safeParent else localBase,
                trusted = source.trusted,
            )
            return ExternalValueResolver.resolve(element, context, logger)
        }

        if (!isRemote) {
            val basePath = Path(url).safeParent ?: Path(".")
            return ExternalValueResolver.resolve(element, FileResolutionContext.Local(basePath, onMissingFile), logger)
        }

        if (ExternalValueResolver.hasFileReference(element)) {
            throw ConfigException("Config '$url' has a { file: } reference, but no remote fetcher is available to resolve it")
        }
        return element
    }

    private companion object {
        /** The directory discovery finds the config in; its parent is the project root. */
        const val CONFIG_DIR_NAME = ".karandash"
    }
}
