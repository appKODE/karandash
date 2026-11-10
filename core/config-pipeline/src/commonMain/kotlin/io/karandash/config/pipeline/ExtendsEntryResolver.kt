package io.karandash.config.pipeline

import io.karandash.api.errors.ConfigException
import io.karandash.api.errors.KarandashError
import io.karandash.api.support.rethrowCancellation
import io.karandash.config.GlobLimitsConfig
import io.karandash.config.MergeStrategy
import io.karandash.config.merge.MergeStrategyMap
import io.karandash.logger.Logger
import io.karandash.logger.NoOpLogger
import io.karandash.logger.debug
import io.karandash.manifest.ConfigSource
import io.karandash.manifest.ExtendsEntry
import io.karandash.manifest.RemoteFileResolution
import io.karandash.platform.currentWorkingDirectory
import io.karandash.utils.GlobExpander
import kotlinx.io.files.Path

/**
 * Orchestrator that coordinates condition evaluation, glob expansion, manifest resolution,
 * and caching before delegating to [ConfigChainResolver].
 *
 * Turns [ExtendsEntry] instances into sources and merge strategies:
 *
 * 1. Skips entries whose `if:` condition is false.
 * 2. Expands [ExtendsEntry.GlobEntry] into [ExtendsEntry.UrlEntry]s.
 * 3. Resolves [ExtendsEntry.ManifestRef] into entries from the manifest file.
 * 4. Attaches cache policies to the fetcher layer.
 * 5. Collects merge strategies for [ConfigChainResolver].
 */
class ExtendsEntryResolver(
    private val globExpander: GlobExpander? = null,
    private val manifestResolver: ManifestResolver? = null,
    private val conditionEvaluator: ConditionEvaluator? = null,
    private val logger: Logger = NoOpLogger,
) {
    companion object {
        /**
         * The resolver config loading uses: glob entries expand under [configDir] (the root config's directory)
         * within `limits.glob`, `if:` conditions read allowlisted env vars and [platformName], and `@name`
         * entries read `.karandash/extends-manifest.<ext>` (any registered format) under [projectDir].
         */
        fun forConfig(
            configDir: Path,
            glob: GlobLimitsConfig,
            formatRegistry: ConfigFormatRegistry,
            platformName: String? = null,
            projectDir: Path = currentWorkingDirectory(),
            logger: Logger = NoOpLogger,
        ): ExtendsEntryResolver =
            ExtendsEntryResolver(
                globExpander = GlobExpander(configDir, glob.effectiveMaxExpansion, glob.effectiveMaxDirectoryDepth, logger),
                manifestResolver = ManifestResolver(projectDir, formatRegistry),
                conditionEvaluator = ConditionEvaluator(platformName = platformName),
                logger = logger,
            )
    }

    /**
     * Resolve entries into a flat list of [ResolvedSource] instances, each carrying
     * its own per-entry merge strategy (not a shared flattened map).
     *
     * Used by [ConfigChainResolver] for the two-phase inheritance pipeline.
     *
     * @param entries List of [ExtendsEntry] to resolve.
     * @param globBaseDir Directory of the config declaring [entries]; glob entries expand there instead of the
     *   root config's directory. Null keeps the expander's own base directory.
     * @param remoteParent [entries] come from a remote config: globs expand braces only (see
     *   [GlobExpander.expandRemote]) into paths the caller resolves against that config's URL.
     * @param glob `limits.glob` inherited by the config declaring [entries]; null keeps the expander's own.
     * @param env `env:` defaults in scope for the config declaring [entries], for the `@name` sets it names.
     * @return List of [ResolvedSource] with per-source strategies.
     */
    suspend fun resolveToSources(
        entries: List<ExtendsEntry>,
        globBaseDir: Path? = null,
        remoteParent: Boolean = false,
        glob: GlobLimitsConfig? = null,
        env: Map<String, String> = emptyMap(),
    ): List<ResolvedSource> {
        if (entries.isEmpty()) return emptyList()
        val located = if (globBaseDir != null) globExpander?.withBaseDir(globBaseDir) else globExpander
        val globs = if (glob != null) located?.withLimits(glob.effectiveMaxExpansion, glob.effectiveMaxDirectoryDepth) else located
        val result = mutableListOf<ResolvedSource>()

        for ((index, entry) in entries.withIndex()) {
            try {
                val condition = entry.condition
                if (condition != null) {
                    val conditionMet = conditionEvaluator?.evaluate(condition)
                        ?: throw ConfigException(
                            "${KarandashError.CONDITION_EVALUATION_FAILED.bracketCode} Condition evaluation not available"
                        )
                    logger.debug { "ExtendsEntry: entry $index condition ${if (conditionMet) "met" else "not met, skipped"}" }
                    if (!conditionMet) continue
                }

                when (entry) {
                    is ExtendsEntry.UrlEntry -> {
                        val source = if (entry.cache != null) entry.source.copy(cachePolicy = entry.cache) else entry.source
                        result.add(ResolvedSource(source, entry.mergeStrategy, entry.fileResolution, entry.topLevelMerge))
                    }

                    is ExtendsEntry.GlobEntry -> {
                        val expanded = (if (remoteParent) globs?.expandRemote(entry.pattern) else globs?.expand(entry.pattern))
                            ?: throw ConfigException("${KarandashError.GLOB_EXPANSION_FAILED.bracketCode} Glob expansion not available")
                        logger.debug {
                            "ExtendsEntry: glob '${entry.pattern}' expanded to ${expanded.size} file(s)" +
                                if (remoteParent) " (remote parent: braces only)" else ""
                        }
                        for (path in expanded) {
                            result.add(ResolvedSource(ConfigSource(url = path), entry.mergeStrategy, topLevelMerge = entry.topLevelMerge))
                        }
                    }

                    is ExtendsEntry.ManifestRef -> {
                        val resolved = manifestResolver?.resolve(entry.name, env)
                            ?: throw ConfigException(
                                "${KarandashError.EXTENDS_MANIFEST_NOT_FOUND.bracketCode} Manifest resolution not available"
                            )
                        logger.debug { "ExtendsEntry: manifest '@${entry.name}' resolved to ${resolved.size} source(s)" }
                        for (resolvedEntry in resolved) {
                            val src = if (resolvedEntry.cache != null) {
                                resolvedEntry.source.copy(cachePolicy = resolvedEntry.cache)
                            } else {
                                resolvedEntry.source
                            }
                            result.add(ResolvedSource(src, entry.mergeStrategy, topLevelMerge = entry.topLevelMerge))
                        }
                    }
                }
            } catch (e: ConfigException) {
                throw e
            } catch (e: IllegalStateException) {
                e.rethrowCancellation()
                throw ConfigException(resolveEntryError(index, e))
            } catch (e: IllegalArgumentException) {
                throw ConfigException(resolveEntryError(index, e))
            }
        }
        return result
    }

    private fun resolveEntryError(
        index: Int,
        e: Exception
    ): String = "${KarandashError.CONDITION_EVALUATION_FAILED.bracketCode} Failed to resolve extends entry at index $index: ${e.message}"
}

/**
 * A single resolved config source with its own per-entry merge strategy.
 *
 * Each [ResolvedSource] carries its own strategy
 * independently - sibling entries are not merged into one combined map.
 *
 * Not @Serializable: runtime context holder, holds a non-serializable `ConfigSource`.
 */
data class ResolvedSource(
    val source: ConfigSource,
    val mergeStrategy: MergeStrategyMap?,
    val fileResolution: RemoteFileResolution = RemoteFileResolution.LOCAL,
    val topLevelMerge: MergeStrategy = MergeStrategy.DEEP,
)
