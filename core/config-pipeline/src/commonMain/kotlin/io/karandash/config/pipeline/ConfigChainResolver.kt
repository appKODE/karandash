package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigChainException
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.exception.ConfigParseException
import io.karandash.api.module.ConfigFormatModule
import io.karandash.config.ExtendsLimitsConfig
import io.karandash.config.KarandashLimits
import io.karandash.config.MergeStrategy
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.config.schema.ConfigSections
import io.karandash.http.HttpAuth
import io.karandash.http.NetworkPolicy
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import io.karandash.manifest.RemoteFileResolution
import io.karandash.models.support.KarandashJson
import io.karandash.platform.PathSecurity
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.exists
import io.karandash.platform.isAbsolutePath
import io.karandash.platform.safeName
import io.karandash.platform.safeParent
import io.karandash.security.UrlSanitizer
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.io.files.Path
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

private const val HTTP_PREFIX = "http://"
private const val HTTPS_PREFIX = "https://"
private const val EXTENDS_KEY = "extends"

/**
 * First stage of two-stage config inheritance: resolve the extends chain.
 *
 * Builds a [ConfigChain] by performing BFS traversal of the extends graph,
 * fetching only the `extends:` block from each config (via [ConfigFormatModule.parseExtendsOnly]).
 * Full config parsing is deferred to the second stage ([ConfigChainParser]).
 *
 * Each layer's parents are fetched under the [ChainFetchKeys] inherited on its path (the layer itself, its
 * children, up to the root; the closer layer wins): `limits.extends`, `limits.glob`, `limits.fetchTimeout`,
 * `cache.downloads`, `allow-localhost` and `allow-private-network`. The run's [NetworkPolicy] is scoped per fetch step;
 * the caller's own value is never changed.
 *
 * A config reachable by several paths (a diamond) is fetched once, under the keys inherited on the path closest to
 * the root (smallest depth); between paths of equal depth, the one through the later-declared `extends:` entry
 * wins, matching merge precedence (left-to-right, a later entry overrides an earlier one). BFS gives this order:
 * levels are visited by depth, each in reverse declaration order, and the first path to queue a URL keeps it.
 *
 * @param formatRegistry Registry of available config format modules.
 * @param fetcher Remote/local config fetcher; its own `allowLocalhost` and timeout apply unless a layer sets them.
 * @param entryResolver Resolves [ExtendsEntry] instances into [ResolvedSource] instances.
 * @param cacheLayer Optional disk-cache layer for remote extends configs. Pass null (default) to
 *   skip caching and always fetch directly - useful in tests and offline scenarios.
 * @param limits Default `limits.extends`, below every layer's own (clamped).
 */
class ConfigChainResolver(
    private val formatRegistry: ConfigFormatRegistry,
    private val fetcher: RemoteConfigFetcher = RemoteConfigFetcher(),
    private val entryResolver: ExtendsEntryResolver = ExtendsEntryResolver(),
    private val cacheLayer: CacheLayer? = null,
    private val logger: Logger = NoOpLogger,
    limits: ExtendsLimitsConfig = ExtendsLimitsConfig(),
) {
    companion object {
        /** Default depth; the resolver enforces `limits.extends.maxDepth`. */
        const val MAX_DEPTH = KarandashLimits.MAX_CHAIN_DEPTH

        /** Default chain size; the resolver enforces `limits.extends.maxChainSize`. */
        const val MAX_CHAIN_SIZE = KarandashLimits.MAX_CHAIN_SIZE
    }

    /** [ChainFetchKeys] below every layer: the constructor's `limits.extends`. */
    private val baseKeys = JsonObject(
        mapOf(
            ConfigSections.LIMITS to JsonObject(
                mapOf(EXTENDS_KEY to KarandashJson.Default.encodeToJsonElement(ExtendsLimitsConfig.serializer(), limits)),
            ),
        ),
    )

    /**
     * Not @Serializable: runtime context holder, holds a non-serializable `ConfigSource`.
     *
     * @property inherited [ChainFetchKeys] merged from the root down to the layer that declared this entry.
     */
    private data class QueueEntry(
        val source: ConfigSource,
        val depth: Int,
        val strategy: MergeStrategyMap?,
        val fileResolution: RemoteFileResolution = RemoteFileResolution.LOCAL,
        val inherited: JsonObject = JsonObject(emptyMap()),
        val topLevelMerge: MergeStrategy = MergeStrategy.DEEP,
    )

    /**
     * Build the full [ConfigChain] for [rootSource].
     *
     * Algorithm: BFS from root outward. For each node, fetch content, extract `extends:` block,
     * resolve entries, and enqueue parents. Result is reversed so deepest ancestor is first.
     *
     * @param rootSource The root config source (child config).
     * @param rootContent The root's content when already in hand (an editor buffer); `null` fetches [rootSource].
     * @return Complete [ConfigChain] ordered deepest-ancestor-first, root-last.
     * @throws ConfigChainException `E0010` when a config extends itself, directly or through its parents.
     * @throws ConfigChainException `E0011` on depth limit exceeded.
     * @throws ConfigChainException `E0024` on chain size limit exceeded.
     */
    suspend fun buildChain(
        rootSource: ConfigSource,
        rootContent: String? = null,
    ): ConfigChain {
        val startAllowPrivateNetwork = NetworkPolicy.allowsPrivateNetwork()
        val visited = mutableSetOf<String>()
        val queued = mutableSetOf<String>()
        // normalized URL -> the normalized URLs it extends, for every layer parsed so far
        val edges = mutableMapOf<String, List<String>>()
        val ordered = mutableListOf<ChainEntry>()
        val projectRoots = localProjectRoots(rootSource.url)

        logger.debug("ConfigChain: starting from ${UrlSanitizer.sanitize(rootSource.url)}")
        queued.add(normalizeUrl(rootSource.url))
        var currentLevel = listOf(QueueEntry(rootSource, 0, null))

        while (currentLevel.isNotEmpty()) {
            val nextLevel = mutableListOf<QueueEntry>()
            val fetched =
                if (rootContent != null && ordered.isEmpty()) {
                    currentLevel.map { it to rootContent }
                } else {
                    fetchLevel(currentLevel, startAllowPrivateNetwork, projectRoots)
                }
            for ((entry, content) in fetched) {
                val normalizedUrl = normalizeUrl(entry.source.url)
                if (normalizedUrl in visited) continue
                checkBounds(entry, ordered.size)
                visited.add(normalizedUrl)
                val (chainEntry, effective, extends) = parseLayer(entry, content)
                ordered.add(chainEntry)
                logger.debug(
                    "ConfigChain: resolved ${UrlSanitizer.sanitize(entry.source.url)} (depth=${entry.depth}, format=${chainEntry.format})"
                )
                if (extends.isNotEmpty()) {
                    val parents = resolveParents(entry.source, extends, effective)
                    ordered[ordered.lastIndex] = chainEntry.declaring(parents)
                    edges[normalizedUrl] = parents.map { normalizeUrl(it.source.url) }
                    // Queued last-declared first: the chain is reversed below, so a later `extends:` entry lands
                    // closer to the root and overrides an earlier one (left-to-right merge, as documented).
                    for (parent in parents.asReversed()) {
                        val normalizedParentUrl = normalizeUrl(parent.source.url)
                        checkNoCycle(edges, normalizedParentUrl, normalizedUrl)
                        // queued already by a shallower or later-declared path (see the class KDoc): that path's keys win
                        if (normalizedParentUrl in queued) continue
                        queued.add(normalizedParentUrl)
                        nextLevel.add(parent.copy(depth = entry.depth + 1))
                    }
                }
            }
            currentLevel = nextLevel
        }

        val chain = ConfigChain(ordered.reversed())
        val chainUrls = chain.entries.map { UrlSanitizer.sanitize(it.source.url) }
        logger.debug("ConfigChain: built ${chain.entries.size}-entry chain (deepest first): $chainUrls")
        return chain
    }

    /**
     * The `merge-strategy` this layer declares on its (condition-met) extends entries governs how the layer merges onto
     * them, as documented; it used to land on the parent's own entry and so never applied. Path modes of
     * several entries combine, the last entry winning a path; the last non-deep top-level strategy wins.
     */
    private fun ChainEntry.declaring(parents: List<QueueEntry>): ChainEntry =
        copy(
            mergeStrategy = parents.mapNotNull { it.strategy }.takeIf { it.isNotEmpty() }?.reduce { a, b -> a + b },
            topLevelMerge = parents.lastOrNull { it.topLevelMerge != MergeStrategy.DEEP }?.topLevelMerge ?: MergeStrategy.DEEP,
        )

    /**
     * A layer extending [parentUrl] closes a cycle only if [parentUrl] already reaches the layer ([childUrl]) through
     * recorded `extends:` edges. A diamond (two paths to one config) is no cycle. Every edge is checked when it is
     * recorded, so the last edge of any cycle finds the rest of it.
     */
    private fun checkNoCycle(
        edges: Map<String, List<String>>,
        parentUrl: String,
        childUrl: String,
    ) {
        val seen = mutableSetOf<String>()
        val stack = ArrayDeque(listOf(listOf(childUrl, parentUrl)))
        while (stack.isNotEmpty()) {
            val path = stack.removeLast()
            val url = path.last()
            if (url == childUrl) {
                throw ConfigChainException(
                    KarandashError.CIRCULAR_CONFIG_INHERITANCE,
                    "Circular config inheritance detected: ${path.joinToString(" -> ") { UrlSanitizer.sanitize(it) }}",
                )
            }
            if (seen.add(url)) edges[url].orEmpty().forEach { stack.add(path + it) }
        }
    }

    /** `limits.extends` inherited by [entry] bounds the depth and the size of the chain. */
    private fun checkBounds(
        entry: QueueEntry,
        chainSize: Int,
    ) {
        val limits = ChainFetchKeys.limits(withBase(entry.inherited), logger).extends
        val maxDepth = limits.effectiveMaxDepth
        val maxChainSize = limits.effectiveMaxChainSize
        if (entry.depth > maxDepth) {
            throw ConfigChainException(
                KarandashError.CONFIG_INHERITANCE_DEPTH_EXCEEDED,
                "Config inheritance depth exceeded (max: $maxDepth)",
            )
        }
        if (chainSize >= maxChainSize) {
            throw ConfigChainException(
                KarandashError.CONFIG_INHERITANCE_CHAIN_TOO_LARGE,
                "Config inheritance chain too large (max: $maxChainSize nodes)",
            )
        }
    }

    /**
     * Not @Serializable: runtime context holder, holds a non-serializable `ChainEntry`.
     *
     * @property effective The layer's [ChainFetchKeys] under those inherited by it: its parents are fetched under them.
     */
    private data class ParsedLayer(
        val chainEntry: ChainEntry,
        val effective: JsonObject,
        val extends: List<ExtendsEntry>,
    )

    private suspend fun parseLayer(
        entry: QueueEntry,
        content: String,
    ): ParsedLayer {
        val url = entry.source.url
        val format = formatRegistry.detectFormat(url)
        val module = try {
            formatRegistry.getModule(format)
        } catch (e: IllegalArgumentException) {
            throw ConfigException(
                "${KarandashError.UNSUPPORTED_CONFIG_FORMAT.bracketCode} Unsupported config format '$format' for $url. " +
                    "Supported formats: ${formatRegistry.allExtensions().joinToString(", ") { ".$it" }}",
                e
            )
        }
        val (extendsRaw, parsed) = try {
            module.parseExtendsOnly(content) to module.parseToJsonElement(content)
        } catch (e: IllegalArgumentException) {
            // built-in format modules wrap syntax errors (SerializationException included) in IllegalArgumentException
            throw ConfigException("Failed to parse config $url: ${e.message}", e)
        } catch (e: ConfigParseException) {
            throw ConfigException("Failed to parse config $url: ${e.message}", e, e.hint)
        }
        val ownKeys = ChainFetchKeys.of(parsed)
        // every layer closer to the root overrides this layer's own keys
        val effective = ChainFetchKeys.merge(ownKeys, entry.inherited)
        val extends = if (extendsRaw != null) {
            // the layer's `auth:` block rides along: an entry written `auth: <name>` names one of its entries
            val ownAuth = (parsed as? JsonObject)?.get(ConfigSections.AUTH)?.let { mapOf(ConfigSections.AUTH to it) }.orEmpty()
            val minimalElement = JsonObject(mapOf(EXTENDS_KEY to extendsRaw) + ownAuth)
            val maxEntries = ChainFetchKeys.limits(withBase(effective), logger).extends.effectiveMaxEntries
            // `${VAR}` in this layer's entries falls back to the `env:` defaults inherited so far, like the chain-fetch keys
            ExtendsParser.parse(minimalElement, ChainFetchKeys.envDefaults(effective), maxEntries)
        } else {
            emptyList()
        }
        val chainEntry = ChainEntry(
            source = entry.source,
            depth = entry.depth,
            mergeStrategy = null,
            content = content,
            format = format,
            fileResolution = entry.fileResolution,
            fetchKeys = ownKeys,
            parsed = parsed,
        )
        return ParsedLayer(chainEntry, effective, extends)
    }

    /** The parents [source] declares in [entries], to fetch under [effective] (their depth is set by the caller). */
    private suspend fun resolveParents(
        source: ConfigSource,
        entries: List<ExtendsEntry>,
        effective: JsonObject,
    ): List<QueueEntry> {
        logger.debug("ConfigChain: ${UrlSanitizer.sanitize(source.url)} extends ${entries.size} parent(s)")
        val parentDir = getParentDirectory(source.url)
        // a parent's globs resolve beside it, not beside the root config; a remote
        // parent cannot be listed, so its globs expand braces only, relative to its URL
        val isRemote = parentDir.startsWith(HTTP_PREFIX) || parentDir.startsWith(HTTPS_PREFIX)
        val globBaseDir = if (isRemote) null else Path(parentDir)
        return entryResolver
            .resolveToSources(
                entries,
                globBaseDir,
                remoteParent = isRemote,
                glob = ChainFetchKeys.glob(effective, logger),
                env = ChainFetchKeys.envDefaults(effective),
            ).map { resolved ->
                QueueEntry(
                    resolveRelativeTo(resolved.source, parentDir, source),
                    depth = 0,
                    strategy = resolved.mergeStrategy,
                    topLevelMerge = resolved.topLevelMerge,
                    fileResolution = resolved.fileResolution,
                    inherited = effective,
                )
            }
    }

    private fun withBase(keys: JsonObject): JsonObject = ChainFetchKeys.merge(baseKeys, keys)

    /**
     * Fetches one BFS level in parallel, in order, each entry under a [NetworkPolicy] of its inherited
     * `allow-private-network` (unset: [startAllowPrivateNetwork]). Local files may sit under [projectRoots].
     */
    private suspend fun fetchLevel(
        level: List<QueueEntry>,
        startAllowPrivateNetwork: Boolean,
        projectRoots: List<Path>,
    ): List<Pair<QueueEntry, String>> =
        coroutineScope {
            level
                .map { entry ->
                    val policy = NetworkPolicy(ChainFetchKeys.allowPrivateNetwork(entry.inherited) ?: startAllowPrivateNetwork)
                    async(policy) { entry to fetchContent(entry, projectRoots) }
                }.awaitAll()
        }

    private suspend fun fetchContent(
        entry: QueueEntry,
        projectRoots: List<Path>,
    ): String {
        val source = entry.source
        if (source.emptyAuthVars.isNotEmpty()) {
            logger.warn(
                "${KarandashError.ENV_VAR_NOT_SET.bracketCode} extends auth for ${UrlSanitizer.sanitize(source.url)} is empty: " +
                    "set ${source.emptyAuthVars.joinToString()}",
            )
        }
        val stepFetcher = ChainFetchKeys.fetcherFor(fetcher, entry.inherited, logger)
        if (!source.url.startsWith(HTTP_PREFIX) && !source.url.startsWith(HTTPS_PREFIX)) return stepFetcher.fetch(source, projectRoots)
        val stepCache = cacheLayer?.let { ChainFetchKeys.cacheFor(it, stepFetcher, entry.inherited, logger) }
        return stepCache?.getOrFetch(source, source.cachePolicy) ?: stepFetcher.fetch(source)
    }

    private fun getParentDirectory(url: String): String = getParentDirectoryOf(url)

    private fun resolveRelativeTo(
        source: ConfigSource,
        baseDir: String,
        parentSource: ConfigSource,
    ): ConfigSource {
        val url = source.url
        if (url.startsWith(HTTP_PREFIX) || url.startsWith(HTTPS_PREFIX) || isAbsolutePath(url)) return source
        val resolvedUrl = if (baseDir.startsWith("http://") || baseDir.startsWith("https://")) {
            val parsed = ApiUrlParser.tryParse(parentSource.url)
            parsed?.resolve(url) ?: "$baseDir/$url"
        } else {
            PathSecurity.normalizeLexical(Path(baseDir, url).toString())
        }
        val inherited = if (source.auth == null) inheritAuthIfSameOrigin(parentSource, resolvedUrl) else null
        if (inherited == null) return source.copy(url = resolvedUrl)
        return source.copy(url = resolvedUrl, auth = inherited, emptyAuthVars = parentSource.emptyAuthVars)
    }

    private fun inheritAuthIfSameOrigin(
        parentSource: ConfigSource,
        childUrl: String
    ): HttpAuth? {
        val parentAuth = parentSource.auth ?: return null
        if (!childUrl.startsWith(HTTP_PREFIX) && !childUrl.startsWith(HTTPS_PREFIX)) return null
        val parentOrigin = PathSecurity.extractOrigin(parentSource.url)
        val childOrigin = PathSecurity.extractOrigin(childUrl)
        return if (parentOrigin == childOrigin) parentAuth else null
    }

    /**
     * Normalizes a URL for cycle detection by resolving `.` and `..` path segments.
     * Prevents bypass via `./config.yaml` vs `config.yaml` vs `../dir/config.yaml`.
     */
    internal fun normalizeUrl(url: String): String {
        val parsed = ApiUrlParser.tryParse(url)
        if (parsed != null) return "${parsed.origin}/${parsed.repoFilePath}"
        if (url.startsWith(HTTP_PREFIX) || url.startsWith(HTTPS_PREFIX)) {
            val protocolEnd = url.indexOf("://") + 3
            val hostEnd = url.indexOf('/', protocolEnd).let { if (it < 0) url.length else it }
            return url.substring(0, hostEnd) + PathSecurity.normalizeLexical(url.substring(hostEnd))
        }
        return PathSecurity.normalizeLexical(url)
    }
}

/**
 * Returns the parent directory of a URL or local file path.
 *
 * For HTTP(S) URLs, returns everything up to the last `/`.
 * For local paths, returns the absolute parent directory.
 */
internal fun getParentDirectoryOf(url: String): String {
    if (url.startsWith(HTTP_PREFIX) || url.startsWith(HTTPS_PREFIX)) {
        val lastSlash = url.lastIndexOf('/')
        return if (lastSlash > 0) url.take(lastSlash) else url
    }
    return Path(url).absolutePathOrSelf().safeParent?.toString() ?: "."
}

/**
 * The directories local configs of a chain rooted at [rootUrl] may be read from: the git top-level above the working
 * directory (the working directory itself outside a repository) and, for a local root, the directory holding its
 * `.karandash/` (or the root file's own directory) or the git top-level above that. A config discovered from a
 * subdirectory, or a monorepo package extending `../../base.yaml`, stays inside them; a remote root adds nothing.
 */
internal fun localProjectRoots(rootUrl: String): List<Path> {
    val cwd = currentWorkingDirectory().absolutePathOrSelf()
    val cwdRoot = gitTopLevel(cwd) ?: cwd
    if (rootUrl.startsWith(HTTP_PREFIX) || rootUrl.startsWith(HTTPS_PREFIX)) return listOf(cwdRoot)
    val configDir = Path(getParentDirectoryOf(rootUrl))
    val baseDir = configDir.takeIf { it.safeName == KARANDASH_DIR }?.safeParent ?: configDir
    return listOf(cwdRoot, gitTopLevel(baseDir) ?: baseDir).distinct()
}

private const val KARANDASH_DIR = ".karandash"

private fun gitTopLevel(start: Path): Path? = generateSequence(start) { it.safeParent }.firstOrNull { Path(it, ".git").exists() }
