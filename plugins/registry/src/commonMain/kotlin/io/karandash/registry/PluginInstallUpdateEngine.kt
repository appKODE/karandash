package io.karandash.registry

import io.karandash.api.support.suspendCatching
import io.karandash.cache.CacheManager
import io.karandash.cache.DownloadsCacheConfig
import io.karandash.cache.cas.CasCache
import io.karandash.crypto.ChecksumVerifier
import io.karandash.crypto.SignatureVerifier
import io.karandash.download.ArtifactDownloader
import io.karandash.loader.DownloadCache
import io.karandash.platform.absolutePath
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.copyTo
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.exists
import io.karandash.platform.extension
import io.karandash.platform.moveFile
import io.karandash.platform.safeName
import io.karandash.platform.setExecutable
import io.ktor.client.HttpClient
import kotlinx.io.IOException
import kotlinx.io.files.Path

/**
 * Shared plugin download/update workflow used by JVM and native listers.
 */
object PluginInstallUpdateEngine {
    /**
     * The download cache configured by `cache.downloads`: `null` when [config] disables it (every
     * install/update then downloads), otherwise entries live for [DownloadsCacheConfig.ttl].
     */
    fun downloadCache(
        config: DownloadsCacheConfig = DownloadsCacheConfig(),
        casCache: () -> CasCache = { CacheManager.defaultInstance().getCasCache(CacheManager.DOWNLOADS_NAMESPACE) },
    ): DownloadCache? = if (config.enabled) DownloadCache(casCache = casCache(), ttlMillis = config.ttl.millis) else null

    /**
     * Downloads [downloadUrl] through [ArtifactDownloader] (HTTPS only, also after redirects; 50 MB cap) and installs
     * it into [pluginDirPath], replacing [oldSourcePath] when given.
     *
     * [signatureKeys] non-null means a `.jar` must carry a `<downloadUrl>.sig` that one of these DER keys verifies;
     * the signature is installed next to the JAR. `null` installs unsigned plugins (`--allow-unsigned`).
     */
    suspend fun downloadAndUpdate(
        downloadUrl: String,
        pluginDirPath: String,
        oldSourcePath: String?,
        pluginId: String,
        supportedFormats: Set<String>,
        verifyPlugin: suspend (String) -> String?,
        httpClient: HttpClient,
        downloadCache: DownloadCache? = downloadCache(),
        expectedChecksum: String? = null,
        signatureKeys: List<ByteArray>? = null,
    ): Result<Pair<String?, String>> =
        suspendCatching {
            require(downloadUrl.startsWith("https://")) {
                "Only HTTPS download URLs are allowed: $downloadUrl"
            }

            val normalizedFormats = supportedFormats.mapTo(linkedSetOf()) { it.lowercase() }
            val pluginDir = Path(pluginDirPath).absolutePathOrSelf()
            createDirectories(pluginDir)

            val resolvedExtension = oldSourcePath
                ?.let { Path(it).extension().lowercase() }
                ?.takeIf { it in normalizedFormats }
                ?: inferExtensionFromUrl(downloadUrl)?.takeIf { it in normalizedFormats }
                ?: throw IllegalArgumentException(
                    "Cannot determine compatible plugin extension from URL: $downloadUrl. " +
                        "Supported: ${normalizedFormats.joinToString(", ") { ".$it" }}"
                )

            val tempFile = Path(pluginDir, ".karandash-download-$pluginId.tmp.$resolvedExtension")
            val tempSig = Path("$tempFile.sig")
            deleteIfExists(tempFile)
            deleteIfExists(tempSig)

            try {
                val cached = downloadCache?.get(downloadUrl)
                if (cached != null) {
                    cached.filePath.copyTo(tempFile, overwrite = true)
                } else {
                    ArtifactDownloader(httpClient).fetch(downloadUrl, tempFile).getOrThrow()
                }
                check(tempFile.exists()) { "Plugin file was not written: $tempFile" }
                // The registry checksum was never checked; a mismatch installs (and caches) nothing.
                if (expectedChecksum != null && !ChecksumVerifier.verify(tempFile, expectedChecksum)) {
                    error("Checksum mismatch for $pluginId: downloaded file does not match the registry checksum")
                }
                if (signatureKeys != null && resolvedExtension == JAR_EXTENSION) {
                    verifySignature(httpClient, downloadUrl, tempFile, tempSig, signatureKeys, pluginId)
                }
                if (cached == null) {
                    downloadCache?.put(downloadUrl, tempFile, extension = resolvedExtension)
                }

                val verifyError = verifyPlugin(tempFile.toString())
                if (verifyError != null) {
                    error("Downloaded plugin failed verification: $verifyError")
                }

                val oldPath = oldSourcePath?.let { Path(it) }
                val targetPath = oldPath ?: Path(pluginDir, "$pluginId.$resolvedExtension")
                if (oldPath == null && targetPath.exists()) {
                    error("Plugin file already exists: $targetPath")
                }

                var backupPath: Path? = null
                if (oldPath != null && oldPath.exists()) {
                    backupPath = Path("$oldPath.bak")
                    deleteIfExists(backupPath)
                    moveFile(oldPath, backupPath, overwrite = true)
                }

                try {
                    moveFile(tempFile, targetPath, overwrite = true)
                    // A signature of the replaced file would not match the new one.
                    val targetSig = Path("$targetPath.sig")
                    if (tempSig.exists()) moveFile(tempSig, targetSig, overwrite = true) else deleteIfExists(targetSig)
                } catch (moveError: IOException) {
                    if (oldPath != null && backupPath != null && backupPath.exists() && !oldPath.exists()) {
                        suspendCatching { moveFile(backupPath, oldPath, overwrite = true) }
                    }
                    throw moveError
                }

                // native libraries are loaded straight from the plugin dir, so they must be executable
                if (resolvedExtension in NATIVE_LIBRARY_EXTENSIONS) setExecutable(targetPath.absolutePath())

                backupPath?.safeName to targetPath.safeName
            } finally {
                deleteIfExists(tempFile)
                deleteIfExists(tempSig)
            }
        }

    private suspend fun verifySignature(
        httpClient: HttpClient,
        downloadUrl: String,
        jar: Path,
        sig: Path,
        keys: List<ByteArray>,
        pluginId: String,
    ) {
        val fetched = ArtifactDownloader(httpClient).fetch(signatureUrl(downloadUrl), sig)
        val failure = fetched.exceptionOrNull() ?: SignatureVerifier.verifyJar(jar, keys).exceptionOrNull()
        failure?.let { reason ->
            error(
                "Plugin $pluginId is not signed by a trusted key (${reason.message}); " +
                    "pass --allow-unsigned to install it anyway"
            )
        }
    }

    /** `<file>.sig` next to the downloaded file: `.sig` goes before any `?query` or `#fragment` of [downloadUrl]. */
    internal fun signatureUrl(downloadUrl: String): String {
        val end = downloadUrl.indexOfFirst { it == '?' || it == '#' }.takeIf { it >= 0 } ?: downloadUrl.length
        return downloadUrl.substring(0, end) + ".sig" + downloadUrl.substring(end)
    }

    fun inferExtensionFromUrl(url: String): String? {
        val cleanUrl = url.substringBefore('?').substringBefore('#')
        val ext = cleanUrl.substringAfterLast('.', "")
        return ext.lowercase().takeIf { it.isNotBlank() }
    }

    private const val JAR_EXTENSION = "jar"
    private val NATIVE_LIBRARY_EXTENSIONS = setOf("so", "dylib", "dll")
}
