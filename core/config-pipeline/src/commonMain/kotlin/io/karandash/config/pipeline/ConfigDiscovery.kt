package io.karandash.config.pipeline

import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.platform.PlatformEnvironment
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.canRead
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.isFile
import io.karandash.platform.safeParent
import kotlinx.io.files.Path

/**
 * Discovers karandash configuration files by walking up the directory tree.
 *
 * Searches from the starting directory to the filesystem root, checking candidates
 * in priority order at each level. Candidates are derived from the [fileExtensions]
 * of every registered [ConfigFormatModule] by [ConfigFormatRegistry.allCandidates] -
 * no naming conventions are hardcoded here.
 *
 * @param formatRegistry Format registry used to generate the candidate filenames.
 * @param logger Logger for debug output during discovery.
 */
class ConfigDiscovery(
    private val formatRegistry: ConfigFormatRegistry,
    private val logger: Logger = NoOpLogger,
) {
    /**
     * Discovers a configuration file by walking up the directory tree.
     *
     * @param startDir The directory to start searching from (defaults to [defaultStartDir])
     * @return A DiscoveryResult containing the found file path and matched pattern, or nulls if not found
     */
    fun discover(startDir: Path = defaultStartDir()): DiscoveryResult {
        val candidates = formatRegistry.allCandidates()
        var dir: Path? = startDir.absolutePathOrSelf()
        logger.debug("ConfigDiscovery: starting search from $dir with ${candidates.size} candidate(s): $candidates")
        while (dir != null) {
            logger.debug("ConfigDiscovery: searching directory $dir")
            for (candidate in candidates) {
                val file = Path(dir, candidate).absolutePathOrSelf()
                if (file.isFile() && canRead(file)) {
                    logger.debug("ConfigDiscovery: detected config file $file (matched pattern '$candidate')")
                    val shadowed = candidates.filter { other ->
                        other != candidate && Path(dir, other).absolutePathOrSelf().let { it.isFile() && canRead(it) }
                    }
                    if (shadowed.isNotEmpty()) {
                        logger.warn("Several config files in $dir: using $candidate, ignoring ${shadowed.joinToString()}")
                    }
                    return DiscoveryResult(file, candidate)
                }
            }
            dir = dir.safeParent
        }
        logger.debug("ConfigDiscovery: no config file found, zero-config fallback will be used")
        return DiscoveryResult(null, null)
    }

    /**
     * Result of configuration file discovery.
     *
     * @property file The discovered configuration file path, or null if not found
     * @property matchedPattern The candidate pattern that matched, or null if not found
     *
     * Not @Serializable: runtime context holder, holds a non-serializable `Path?`.
     */
    data class DiscoveryResult(
        val file: Path?,
        val matchedPattern: String?
    )

    companion object {
        /** Overrides the directory config discovery starts from. */
        const val CONFIG_DIR_ENV = "KARANDASH_CONFIG_DIR"

        /** `KARANDASH_CONFIG_DIR` when set and non-blank, else the current working directory. */
        fun defaultStartDir(env: (String) -> String? = PlatformEnvironment::getEnv): Path =
            env(CONFIG_DIR_ENV)?.trim()?.takeIf { it.isNotEmpty() }?.let(::Path) ?: currentWorkingDirectory()

        /**
         * Convenience method that uses the built-in format registry.
         * Prefer constructing [ConfigDiscovery] directly with a plugin-loaded registry
         * when one is available.
         */
        fun discover(
            startDir: Path = defaultStartDir(),
            registry: ConfigFormatRegistry,
            logger: Logger = NoOpLogger,
        ): DiscoveryResult = ConfigDiscovery(registry, logger).discover(startDir)
    }
}
