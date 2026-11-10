package io.karandash.registry

import io.karandash.Version
import io.karandash.api.exception.RegistrySecurityException
import io.karandash.api.support.suspendCatching
import io.karandash.duration.KarandashDuration
import io.karandash.http.HostCredentials
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.models.support.KarandashJson
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.createDirectories
import io.karandash.platform.exists
import io.karandash.platform.lastModifiedMillis
import io.karandash.platform.readText
import io.karandash.platform.safeParent
import io.karandash.platform.userHomeDirectory
import io.karandash.platform.writeText
import io.karandash.utils.VersionComparator
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Default registry: `tools/registry/registry.json` of the karandash repository, served raw from GitHub `main`.
 * Override per project with `plugins.registry-url`.
 */
const val REGISTRY_URL = "https://raw.githubusercontent.com/appKODE/karandash/main/tools/registry/registry.json"

/**
 * Cache duration in milliseconds (24 hours).
 */
const val CACHE_DURATION_MS = 24 * 60 * 60 * 1000L

/**
 * Registry schema matching the JSON structure.
 */
@Serializable
data class PluginRegistry(
    val version: String,
    val lastUpdated: String,
    val plugins: List<PluginRegistryEntry>
)

@Serializable
data class ArtifactInfo(
    val url: String,
    val checksum: String,
    // "jar", "dylib", "so", "dll"
    val format: String
)

@Serializable
data class PluginRegistryEntry(
    val id: String,
    val name: String,
    val description: String,
    val author: String,
    val version: String,
    val type: String,
    val tags: List<String>,
    val url: String, // Default artifact, used when no `artifacts` entry fits
    val checksum: String,
    val artifacts: Map<String, ArtifactInfo>? = null, // Platform-specific
    val minHostVersion: String,
    val repository: String,
    val documentation: String
) {
    /**
     * Returns a user-facing reason when this entry cannot be installed on [hostVersion]
     * (its [minHostVersion] is newer), or `null` when it is compatible. Blank or
     * unparseable versions are treated as compatible.
     */
    fun hostIncompatibility(hostVersion: String = Version.VERSION): String? =
        if (VersionComparator.isBelow(hostVersion, minHostVersion)) {
            "Plugin '$id' v$version requires karandash >= $minHostVersion (this is $hostVersion)"
        } else {
            null
        }
}

/**
 * Client for fetching and caching the karandash plugin registry.
 *
 * [auth] is keyed for [registryUrl]'s host: a client built here (or with the same [HostCredentials]) drops it on a
 * redirect to another host.
 */
class RegistryClient(
    private val cacheDir: Path = defaultCacheDir(),
    private val registryUrl: String = REGISTRY_URL,
    private val auth: RegistryAuth? = null,
    private val httpClient: HttpClient =
        ProviderHttpClientFactory.create(
            timeout = KarandashDuration.ofMillis(REGISTRY_TIMEOUT_MS),
            credentials = HostCredentials.of(registryUrl, auth?.toHttpAuth()),
        ),
    private val clock: () -> Long = { Clock.System.now().toEpochMilliseconds() }
) {
    private val json = io.karandash.models.support.KarandashJson.Default

    companion object {
        private const val REGISTRY_TIMEOUT_MS = 10_000L

        /**
         * Default cache directory: ~/.karandash/cache/
         */
        fun defaultCacheDir(): Path = Path(userHomeDirectory(), ".karandash", "cache")
    }

    /**
     * Fetches the plugin registry, using cache if available and fresh.
     *
     * @param forceRefresh If true, ignores cache and fetches fresh data
     * @return Result containing the registry or error
     */
    suspend fun fetchRegistry(forceRefresh: Boolean = false): Result<PluginRegistry> {
        val cacheFile = cacheFile()

        if (!forceRefresh && isCacheValid(cacheFile)) {
            return readCacheFile(cacheFile)
        }

        return fetchRemoteRegistry().onSuccess { registry ->
            cacheRegistry(cacheFile, registry)
        }
    }

    /**
     * The on-disk registry cache regardless of its age, paired with that age in milliseconds; null when
     * there is no readable cache. The offline fallback of `plugin search` after a failed [fetchRegistry].
     */
    fun staleCache(): Pair<PluginRegistry, Long>? {
        val cacheFile = cacheFile()
        if (!cacheFile.exists()) return null
        val registry = readCacheFile(cacheFile).getOrNull() ?: return null
        return registry to (clock() - cacheFile.lastModifiedMillis())
    }

    private fun cacheFile(): Path = Path(cacheDir, "registry-${cacheKey(registryUrl)}.json").absolutePathOrSelf()

    /**
     * Checks if the cache file exists and is less than 24 hours old.
     */
    private fun isCacheValid(cacheFile: Path): Boolean {
        if (!cacheFile.exists()) {
            return false
        }

        val lastModified = cacheFile.lastModifiedMillis()
        val now = clock()
        return (now - lastModified) < CACHE_DURATION_MS
    }

    /**
     * Reads and parses the cache file.
     */
    private fun readCacheFile(cacheFile: Path): Result<PluginRegistry> =
        suspendCatching {
            val content = cacheFile.readText()
            json.decodeFromString<PluginRegistry>(content)
        }

    /**
     * Fetches the registry from the remote URL.
     */
    private suspend fun fetchRemoteRegistry(): Result<PluginRegistry> =
        suspendCatching {
            if (!registryUrl.startsWith("https://")) {
                throw RegistrySecurityException(
                    "Only HTTPS registry URLs are allowed. Got: ${registryUrl.substringBefore("://") + "://"}"
                )
            }

            val response = httpClient.get(registryUrl) {
                auth?.toHttpAuth()?.applyTo(this)
            }
            if (!response.status.isSuccess()) {
                error("Failed to fetch registry: HTTP ${response.status.value}")
            }

            val content = response.bodyAsText()
            json.decodeFromString<PluginRegistry>(content)
        }

    /**
     * Writes the registry to the cache file.
     */
    private fun cacheRegistry(
        cacheFile: Path,
        registry: PluginRegistry
    ) {
        try {
            val parent = cacheFile.safeParent
            if (parent != null) {
                createDirectories(parent)
            }
            val content = json.encodeToString(PluginRegistry.serializer(), registry)
            cacheFile.writeText(content)
        } catch (ignored: IOException) {
            // Silently fail cache write - not critical
        }
    }

    /**
     * Generates a cache key from a URL.
     */
    private fun cacheKey(url: String): String = url.hashCode().toString().replace("-", "")

    /**
     * Filters registry entries by search query.
     * Matches against name, description, and tags.
     */
    fun filterPlugins(
        registry: PluginRegistry,
        query: String? = null,
        tag: String? = null,
        type: String? = null
    ): List<PluginRegistryEntry> {
        var results = registry.plugins

        if (type != null) {
            val normalizedType = normalizePluginType(type)
            results = results.filter { normalizePluginType(it.type) == normalizedType }
        }

        // Filter by tag
        if (tag != null) {
            results = results.filter { plugin ->
                plugin.tags.any { it.equals(tag, ignoreCase = true) }
            }
        }

        // Filter by query (name, description, tags)
        if (query != null && query.isNotBlank()) {
            val lowerQuery = query.lowercase()
            results = results.filter { plugin ->
                plugin.name.lowercase().contains(lowerQuery) ||
                    plugin.description.lowercase().contains(lowerQuery) ||
                    plugin.tags.any { it.lowercase().contains(lowerQuery) }
            }
        }

        return results
    }

    /** Lower-kebab label; provider/mutator/comment-poster collapse to platform-service, the module type they share. */
    private fun normalizePluginType(value: String): String =
        value
            .trim()
            .lowercase()
            .replace('_', '-')
            .let { TEMPLATE_TYPE_LABELS[it]?.cliLabel ?: it }
}
