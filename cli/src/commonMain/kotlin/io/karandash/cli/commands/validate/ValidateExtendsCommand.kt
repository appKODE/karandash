package io.karandash.cli.commands.validate

import com.github.ajalt.clikt.core.Context
import com.github.ajalt.clikt.core.ProgramResult
import com.github.ajalt.clikt.parameters.groups.provideDelegate
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import io.karandash.api.errors.ConfigChainException
import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.exception.KarandashException
import io.karandash.api.support.rethrowCancellation
import io.karandash.bootstrap.CliLogger
import io.karandash.bootstrap.PluginBootstrap
import io.karandash.cli.CliLogLevel
import io.karandash.cli.ConfigFileOptions
import io.karandash.cli.SuspendCliktCommand
import io.karandash.cli.errors.ExitCodes
import io.karandash.cli.pipeline.ConfigLoader
import io.karandash.config.pipeline.ChainFetchKeys
import io.karandash.config.pipeline.ConfigChain
import io.karandash.config.pipeline.ConfigDiscovery
import io.karandash.config.pipeline.ConfigFormatRegistry
import io.karandash.config.pipeline.ManifestResolver
import io.karandash.detect.CIDetector
import io.karandash.platform.absolutePathOrSelf
import io.karandash.platform.currentWorkingDirectory
import io.karandash.platform.exists
import kotlinx.io.files.Path

/**
 * Command to validate extends chains without running full validation.
 *
 * Validates the extends directive in a config file by:
 * - Discovering and parsing the config file
 * - Merging the extends chain exactly as `validate` does ([ValidateConfigPipeline.mergeChain]): parents are
 *   fetched with their auth through the cache layer, and `{ file: }` references are resolved
 * - Reporting cycle detection (E0010), depth (E0011) and size violations from chain resolution
 *
 * `--list-sets` instead prints the named sets of `.karandash/extends-manifest.yaml`.
 *
 * Exit codes:
 * - 0: Success (all extends valid)
 * - 1: Validation errors found
 * - 2: Config error (including a missing manifest for `--list-sets`)
 * - 4: Internal error
 */
class ValidateExtendsCommand(
    private val sharedBootstrap: PluginBootstrap,
    private val workingDir: Path? = null,
    private val initialLogLevel: CliLogLevel = CliLogLevel.NORMAL,
) : SuspendCliktCommand(name = "validate-extends") {
    private companion object {
        private const val SECTION_RESOLUTION = "Resolution:"
    }

    override fun help(context: Context) = "Validate extends chain without running full validation"

    private val configOptions by ConfigFileOptions()

    private val verbose by option(
        "-v",
        "--verbose",
        help = "Show detailed output for each URL"
    ).flag()

    private val listSets by option(
        "--list-sets",
        help = "List the named extends sets of ${ManifestResolver.MANIFEST_PATH} and exit"
    ).flag()

    @Suppress("TooGenericExceptionCaught") // Top-level CLI error handler
    override suspend fun suspendRun() {
        val logLevel = CliLogLevel.fromFlags(quiet = false, verbose = verbose) ?: initialLogLevel
        val logger = CliLogger(quiet = logLevel.quiet, verbose = logLevel.verbose)
        if (listSets) return listManifestSets(logger)

        try {
            val configPath = discoverConfigPath(sharedBootstrap.formatRegistry).absolutePathOrSelf()
            logger.debug("Using config file: $configPath")

            if (!configPath.exists()) {
                throw ConfigException("${KarandashError.CONFIG_FILE_NOT_FOUND.bracketCode} Config file not found: $configPath")
            }

            echo("Extends Chain Validation")
            echo("========================")

            val pipeline = ValidateConfigPipeline(
                configPath.toString(),
                logger,
                sharedBootstrap.formatRegistry,
                platformName = CIDetector(sharedBootstrap.registry().platformServices()).detect()?.platform,
            )
            val rootElement = pipeline.loadConfigBootstrap().rawElement
            var chain: ConfigChain? = null
            try {
                // the same merge `validate` runs: cache layer, chain-fetch keys, auth and `{ file: }` references
                pipeline.mergeChain(rootElement, configPath, onEntryParsed = null, onChain = { chain = it })
            } catch (e: ConfigChainException) {
                reportChainViolation(e)
            } catch (e: KarandashException) {
                // any user-facing merge error (a parent its format parser rejects, a bad `{ file: }`) is a finding
                echo("  [FAIL] ${e.message}")
                echo("")
                echo("Result: 1 error(s), 0 warning(s)")
                throw ProgramResult(ExitCodes.VALIDATION_FAILED)
            }
            reportChain(checkNotNull(chain) { "mergeChain returned without resolving the chain" }, logger)
        } catch (e: ProgramResult) {
            throw e
        } catch (e: KarandashException) {
            // a root its format parser rejects (ConfigParseException) is a config error, not an internal one
            logger.error(e.message ?: "Config error")
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        } catch (e: Exception) {
            // boundary: CLI command entry maps any failure to an exit code
            e.rethrowCancellation()
            logger.error("Internal error: ${e.message}")
            logger.debug(e.stackTraceToString())
            throw ProgramResult(ExitCodes.INTERNAL_ERROR)
        }
    }

    private fun reportChainViolation(e: ConfigChainException): Nothing {
        echo("")
        echo(SECTION_RESOLUTION)
        when (e.error) {
            KarandashError.CIRCULAR_CONFIG_INHERITANCE -> echo("  Circular references: DETECTED")
            KarandashError.CONFIG_INHERITANCE_DEPTH_EXCEEDED -> echo("  Depth violation: DETECTED")
            KarandashError.CONFIG_INHERITANCE_CHAIN_TOO_LARGE -> echo("  Chain size violation: DETECTED")
            else -> echo("  Chain violation: DETECTED")
        }
        echo("  Error: ${e.message}")
        echo("")
        echo("Result: 1 error(s), 0 warning(s)")
        throw ProgramResult(ExitCodes.VALIDATION_FAILED)
    }

    private fun reportChain(
        chain: ConfigChain,
        logger: CliLogger,
    ) {
        // deepest ancestor first, root last: the parents are all but the root
        val parentEntries = chain.entries.dropLast(1)
        if (parentEntries.isEmpty()) {
            echo("No extends directive found in config")
            return
        }
        parentEntries.forEach { entry ->
            val url = entry.source.url
            val how = if (url.startsWith("http://") || url.startsWith("https://")) "fetched" else "local file"
            echo("  [OK]   $url ($how)")
        }

        echo("")
        echo(SECTION_RESOLUTION)
        val extendsLimits = ChainFetchKeys.limits(chain.fetchKeys, logger).extends
        echo("  Total depth: ${chain.entries.maxOf { it.depth }} / ${extendsLimits.effectiveMaxDepth} (max)")
        echo("  Total extends: ${parentEntries.size} / ${extendsLimits.effectiveMaxChainSize - 1} (max)")
        echo("  Circular references: none")
        echo("")
        echo("Result: 0 error(s), 0 warning(s)")
    }

    private suspend fun listManifestSets(logger: CliLogger) {
        val resolver = ManifestResolver(workingDir ?: currentWorkingDirectory(), sharedBootstrap.formatRegistry)
        val sets = try {
            resolver.listSets()
        } catch (e: KarandashException) {
            // a root its format parser rejects (ConfigParseException) is a config error, not an internal one
            logger.error(e.message ?: "Config error")
            throw ProgramResult(ExitCodes.CONFIG_ERROR)
        }
        echo("Extends sets (${ManifestResolver.MANIFEST_PATH}):")
        if (sets.isEmpty()) echo("  (none)")
        sets.forEach { (name, urls) ->
            echo("  @$name")
            urls.forEach { echo("    - $it") }
        }
    }

    private fun discoverConfigPath(formatRegistry: ConfigFormatRegistry): Path {
        configOptions.path?.let { return Path(it) }

        val discovery = ConfigLoader.discover(formatRegistry, workingDir ?: ConfigDiscovery.defaultStartDir())
        val discoveredFile = discovery.file
        if (discoveredFile != null) {
            return discoveredFile
        }

        throw ConfigException("No config file found. Use --config to specify a path.")
    }
}
