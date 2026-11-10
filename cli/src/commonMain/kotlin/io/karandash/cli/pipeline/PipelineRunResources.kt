@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.module.PipelineExtension
import io.karandash.api.module.RuleModule
import io.karandash.api.run.RunSession
import io.karandash.cache.CacheManager
import io.karandash.cli.commands.validate.ValidateCommandScope
import io.karandash.helpers.RegexHelper
import io.karandash.patterns.PatternAwareCompilerImpl
import io.karandash.sdk.Logger
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext

/**
 * Resources the [ValidationPipeline] stages acquire one by one during a run. Each field stays null (or empty)
 * until its stage creates it; [release] tears down whatever was acquired, whether the run finished,
 * returned early or threw.
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
internal class PipelineRunResources(
    private val session: RunSession,
    private val logger: Logger,
) {
    var scope: ValidateCommandScope? = null
    var cacheManager: CacheManager? = null
    var diag: PipelineDiagnosticBridge? = null
    var clearCompileLimits: (suspend () -> Unit)? = null
    var remoteCache: RemoteCachePushScope? = null
    var patternCompiler: PatternAwareCompilerImpl? = null
    var configuredExtensions: List<PipelineExtension> = emptyList()

    /** Runs `onPipelineComplete`, closes the session and releases every acquired resource in reverse order. */
    suspend fun release() {
        withContext(NonCancellable) {
            // Always, after auto-approve and fix, once extensions were configured.
            for (ext in configuredExtensions) {
                runExtensionHook(ext, "onPipelineComplete", logger, Unit) { ext.onPipelineComplete() }
            }
            configuredExtensions.forEach { ext -> ext.costSummary()?.let(logger::info) }
            scope?.registry?.ruleModules()?.let { logRuleModuleUsage(it, logger) }
            session.close()
            remoteCache?.drain()
        }
        patternCompiler?.let(RegexHelper::clearPatternCompiler)
        clearCompileLimits?.invoke()
        PipelineInfrastructure.runCacheGc(cacheManager, logger)
        cacheManager?.takeIf { it.enabled }?.saveRunStats()
        cacheManager?.shutdown()
        scope?.cleanup()
    }
}

/**
 * logs one `info` line per rule module that reports [RuleModule.usage] (tokens, requests, cost) at the
 * end of the run; modules that do not track usage, or used nothing, stay silent.
 */
internal fun logRuleModuleUsage(
    modules: List<RuleModule>,
    logger: Logger,
) {
    for (module in modules) {
        val usage = module.usage ?: continue
        if (usage.tokens == 0L && usage.requests == 0 && usage.costUsd == null) continue
        val cost = usage.costUsd?.let { ", cost ~${'$'}$it" }.orEmpty()
        logger.info("Module ${module.id} usage: ${usage.tokens} tokens, ${usage.requests} requests$cost")
    }
}
