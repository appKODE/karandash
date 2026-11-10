package io.karandash.inspector

import io.karandash.duration.KarandashDuration
import io.karandash.http.ProviderHttpClientFactory
import io.karandash.loader.DownloadCache
import io.karandash.loader.PluginDiscovery
import io.karandash.platform.copyTo
import io.karandash.platform.createDirectories
import io.karandash.platform.deleteIfExists
import io.karandash.platform.exists
import io.karandash.platform.extension
import io.karandash.platform.isDirectory
import io.karandash.platform.isFile
import io.karandash.platform.listFiles
import io.karandash.registry.InstalledPluginInfo
import io.karandash.registry.InstalledPluginLister
import io.karandash.registry.PluginInstallUpdateEngine
import kotlinx.io.IOException
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Platform-agnostic [InstalledPluginLister] backed by a [PluginInspector].
 *
 * File I/O and directory traversal are handled here using [kotlinx.io]; the
 * platform-specific logic (how to read and verify a plugin file) is delegated
 * to the injected [inspector].
 */
class InstalledPluginListerImpl(
    private val inspector: PluginInspector,
    /** Download cache for install/update, read per call so `cache.downloads` applies; `null` bypasses caching. */
    private val downloadCache: suspend () -> DownloadCache? = { PluginInstallUpdateEngine.downloadCache() },
) : InstalledPluginLister {
    // SSRF-checked: download URLs come from registry content; loopback only under the manifest's `allow-localhost`.
    private val httpClient = ProviderHttpClientFactory.create(timeout = KarandashDuration.ofMillis(HTTP_TIMEOUT_MS))
    private val loopbackClient = lazy {
        ProviderHttpClientFactory.create(timeout = KarandashDuration.ofMillis(HTTP_TIMEOUT_MS), allowLocalhost = true)
    }

    override fun defaultPluginDirPath(): String = PluginDiscovery.defaultPluginDirPath()

    override fun isDirectory(path: String): Boolean {
        val file = Path(path)
        return SystemFileSystem.exists(file) && SystemFileSystem.metadataOrNull(file)?.isDirectory == true
    }

    override fun supportedFormats(): Set<String> = inspector.nativeLibraryExtensions() + setOf("js", "mjs", "wasm", "jar")

    override suspend fun listInstalledPlugins(pluginDirPath: String): List<InstalledPluginInfo> {
        val dirPath = Path(pluginDirPath)
        if (!dirPath.isDirectory()) return emptyList()

        val validExtensions = supportedFormats()
        val files = dirPath.listFiles() ?: return emptyList()
        val result = mutableListOf<InstalledPluginInfo>()

        for (file in files) {
            if (!file.isFile() || file.extension() !in validExtensions) continue
            val metadata = inspector.readMetadata(file.toString()) ?: continue
            result += InstalledPluginInfo(
                id = metadata.id,
                version = metadata.version,
                name = metadata.name,
                sourcePath = file.toString(),
            )
        }

        return result
    }

    override suspend fun verifyPlugin(pluginPath: String): String? {
        val path = Path(pluginPath)
        if (!path.exists() || !path.isFile()) {
            return "File not found: $pluginPath"
        }

        val ext = path.extension()
        val validExtensions = supportedFormats()
        if (ext !in validExtensions) {
            return "Unsupported file type: .$ext (expected one of: ${validExtensions.joinToString(", ") { ".$it" }})"
        }

        return inspector.verifyFile(pluginPath, ext)
    }

    override suspend fun installPlugin(
        sourcePath: String,
        pluginDir: String,
    ): Result<InstalledPluginInfo> {
        val verifyError = verifyPlugin(sourcePath)
        if (verifyError != null) {
            return Result.failure(IllegalArgumentException(verifyError))
        }

        val source = Path(sourcePath)
        val metadata = inspector.readMetadata(sourcePath)
            ?: return Result.failure(IllegalStateException("Failed to read plugin metadata: $sourcePath"))

        val targetDirPath = Path(pluginDir)
        createDirectories(targetDirPath)

        val ext = source.extension()
        val targetFile = Path(pluginDir, "${metadata.id}.$ext")

        if (targetFile.exists()) {
            return Result.failure(
                IllegalStateException("Plugin file already exists: $targetFile. Remove it first.")
            )
        }

        return try {
            source.copyTo(targetFile)
            // The JAR's signature travels with it, so the loader can verify the installed copy.
            val sourceSig = Path("$sourcePath.sig")
            val targetSig = Path("$targetFile.sig")
            if (sourceSig.exists()) sourceSig.copyTo(targetSig, overwrite = true) else deleteIfExists(targetSig)
            Result.success(
                InstalledPluginInfo(
                    id = metadata.id,
                    version = metadata.version,
                    name = metadata.name,
                    sourcePath = targetFile.toString(),
                )
            )
        } catch (e: IOException) {
            deleteIfExists(targetFile)
            Result.failure(e)
        }
    }

    override suspend fun removePlugin(
        pluginId: String,
        pluginDir: String,
    ): Boolean {
        val dirPath = Path(pluginDir)
        if (!dirPath.isDirectory()) return false

        val validExtensions = supportedFormats()
        val files = dirPath.listFiles() ?: return false

        for (file in files) {
            if (file.extension() !in validExtensions) continue
            val metadata = inspector.readMetadata(file.toString()) ?: continue
            if (metadata.id == pluginId) {
                return deleteIfExists(file)
            }
        }

        return false
    }

    override suspend fun downloadAndUpdate(
        downloadUrl: String,
        pluginDirPath: String,
        oldSourcePath: String?,
        pluginId: String,
        expectedChecksum: String?,
        allowLocalhost: Boolean,
        signatureKeys: List<ByteArray>?,
    ): Result<Pair<String?, String>> =
        PluginInstallUpdateEngine.downloadAndUpdate(
            downloadUrl = downloadUrl,
            pluginDirPath = pluginDirPath,
            oldSourcePath = oldSourcePath,
            pluginId = pluginId,
            supportedFormats = supportedFormats(),
            verifyPlugin = ::verifyPlugin,
            httpClient = if (allowLocalhost) loopbackClient.value else httpClient,
            downloadCache = downloadCache(),
            expectedChecksum = expectedChecksum,
            signatureKeys = signatureKeys,
        )

    override suspend fun shutdown() {
        httpClient.close()
        if (loopbackClient.isInitialized()) loopbackClient.value.close()
        inspector.shutdown()
    }

    private companion object {
        const val HTTP_TIMEOUT_MS = 30_000L
    }
}
