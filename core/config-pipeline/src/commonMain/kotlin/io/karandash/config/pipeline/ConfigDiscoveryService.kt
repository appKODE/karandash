package io.karandash.config.pipeline

import io.karandash.api.module.ConfigFormatModule
import io.karandash.sdk.Logger
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem

/**
 * Discovers config files by searching standard locations and file extensions.
 *
 * Extracted from [io.karandash.cli.commands.validate.ValidateConfigPipeline] to
 * be reusable by `init`, `wizard`, and `doctor` commands.
 */
class ConfigDiscoveryService(
    private val formatRegistry: ConfigFormatRegistry,
    private val logger: Logger,
) {
    /**
     * Result of config discovery.
     *
     * @param configFile Resolved config file path, or null if not found.
     * @param usingZeroConfig True when no config file was found and zero-config defaults should be used.
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `Path?`.
     */
    data class DiscoveryResult(
        val configFile: Path?,
        val usingZeroConfig: Boolean,
    )

    /**
     * Discover a config file, either from an explicit path or by searching standard locations.
     *
     * @param explicitPath User-provided config path (e.g. `--config` flag), or null for auto-discovery.
     * @return Discovery result indicating the resolved path or zero-config fallback.
     */
    fun discover(explicitPath: String?): DiscoveryResult {
        if (explicitPath != null) {
            logger.debug("ConfigDiscoveryService: using explicit config path: $explicitPath")
            return DiscoveryResult(configFile = Path(explicitPath), usingZeroConfig = false)
        }

        logger.debug("ConfigDiscoveryService: no explicit path provided, starting auto-discovery")
        val discovery = ConfigDiscovery(formatRegistry, logger).discover()
        if (discovery.file != null) {
            val format = detectFormat(discovery.file)
            logger.debug(
                "ConfigDiscoveryService: config file found at ${discovery.file}, format: ${format?.let {
                    it::class.simpleName
                } ?: "unknown"}"
            )
            return DiscoveryResult(configFile = discovery.file, usingZeroConfig = false)
        }

        logger.debug("ConfigDiscoveryService: no config file found, activating zero-config fallback")
        logger.info("No config found, using default rules. Run 'karandash init' to customize.")
        return DiscoveryResult(configFile = null, usingZeroConfig = true)
    }

    /**
     * Detect the config format for a given file path.
     *
     * @return The matching [ConfigFormatModule], or null if the format is not recognized.
     */
    fun detectFormat(path: Path): ConfigFormatModule? = formatRegistry.getModuleForFile(path.toString())

    /**
     * Verify that a config file path exists and is a regular file.
     *
     * @return True if the file exists and is a regular file.
     */
    fun exists(path: Path): Boolean =
        SystemFileSystem.exists(path) &&
            SystemFileSystem.metadataOrNull(path)?.isRegularFile == true
}
