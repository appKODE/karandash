package io.karandash.registry

/**
 * Abstracts the listing and updating of installed plugins for multiplatform use.
 *
 * On JVM, this is backed by PluginBootstrap; on native targets
 * a different implementation can be supplied.
 */
interface InstalledPluginLister {
    /**
     * Returns the default plugin directory path.
     */
    fun defaultPluginDirPath(): String

    /**
     * Check if the given path is an existing directory.
     */
    fun isDirectory(path: String): Boolean

    /**
     * Load plugins from the given directory path and return information
     * about each installed module.
     *
     * @param pluginDirPath Absolute path to the plugin directory.
     * @return List of installed plugin info, empty if none found.
     */
    suspend fun listInstalledPlugins(pluginDirPath: String): List<InstalledPluginInfo>

    /**
     * Download a file from the given HTTPS URL and install it as a plugin update.
     *
     * Backs up the old JAR (if it exists) and copies the downloaded file
     * to the plugin directory.
     *
     * @param downloadUrl The HTTPS URL to download from.
     * @param pluginDirPath The plugin directory path.
     * @param oldSourcePath The path of the existing JAR to back up (nullable).
     * @param pluginId The plugin ID (used as fallback filename).
     * @param expectedChecksum Registry checksum (`sha256:<hex>`); when set, a mismatching download installs nothing.
     * @param allowLocalhost The manifest's `plugins.allow-localhost`: the download may reach a loopback/private host.
     * @param signatureKeys Trusted DER public keys: a `.jar` must carry a `<downloadUrl>.sig` one of them verifies.
     *   `null` installs unsigned plugins (`--allow-unsigned`).
     * @return Result with a pair of (backedUpFileName, newFileName) on success.
     */
    suspend fun downloadAndUpdate(
        downloadUrl: String,
        pluginDirPath: String,
        oldSourcePath: String?,
        pluginId: String,
        expectedChecksum: String? = null,
        allowLocalhost: Boolean = false,
        signatureKeys: List<ByteArray>? = null,
    ): Result<Pair<String?, String>>

    /**
     * Shut down any resources held by the lister (e.g. classloaders).
     */
    suspend fun shutdown()

    /**
     * Returns the set of plugin file formats supported on this platform.
     * Examples: {"jar"} for JVM-only JAR support, {"dylib"} for macOS native, {"jar", "dylib", "so"} for JVM with native support.
     */
    fun supportedFormats(): Set<String>

    /**
     * Verifies that a plugin file at the given path is valid for this platform.
     * Returns null if valid, or an error message if invalid.
     */
    suspend fun verifyPlugin(pluginPath: String): String?

    /**
     * Installs a plugin from the source path to the plugin directory.
     * Returns the installed plugin info on success, or failure with an error.
     */
    suspend fun installPlugin(
        sourcePath: String,
        pluginDir: String
    ): Result<InstalledPluginInfo>

    /**
     * Removes a plugin by its ID from the plugin directory.
     * Returns true if removed successfully, false if plugin not found.
     */
    suspend fun removePlugin(
        pluginId: String,
        pluginDir: String
    ): Boolean
}
