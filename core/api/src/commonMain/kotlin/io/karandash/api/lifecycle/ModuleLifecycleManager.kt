package io.karandash.api.lifecycle

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.InternalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleResult
import io.karandash.api.PluginContext
import io.karandash.api.errors.KarandashError
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Host implementation of [ModuleLifecycle]: ordered initialization and reverse-order shutdown.
 *
 * Every `shutdown()` is isolated: a failure is logged at `warn`, a hang is bounded by
 * [shutdownTimeout] (via `withTimeoutOrNull`, so a timeout never cancels the remaining
 * shutdowns), and only a cancellation of the calling coroutine propagates.
 *
 * Application-scoped and thread-safe: tracked modules live in a [MutableStateFlow] snapshot.
 * Public only so loaders in other modules can share it; not part of the plugin contract.
 */
@InternalKarandashApi
@OptIn(ExperimentalKarandashApi::class)
public class ModuleLifecycleManager(
    private val logger: Logger,
    private val shutdownTimeout: Duration = DEFAULT_SHUTDOWN_TIMEOUT,
) : ModuleLifecycle {
    private val tracked = MutableStateFlow<List<Module>>(emptyList())

    /** IDs of the tracked modules, in initialization order. */
    public val trackedIds: List<String> get() = tracked.value.map { it.id }

    /** Whether this exact [module] instance is tracked (identity, not ID). */
    public fun isTracked(module: Module): Boolean = tracked.value.any { it === module }

    override suspend fun initialize(
        module: Module,
        context: PluginContext,
    ): Boolean {
        val initialized = module.initialize(context)
        if (initialized) track(module)
        return initialized
    }

    /**
     * [initialize] as a [ModuleResult]: `Success(true)` tracked, `Success(false)` skipped (bad config, opted out),
     * `Failure` when `initialize()` threw (code [KarandashError.PLUGIN_INIT_FAILED]). Throws only on cancellation.
     */
    @Suppress("TooGenericExceptionCaught") // boundary: module initialize() is plugin code
    public suspend fun initializeResult(
        module: Module,
        context: PluginContext,
    ): ModuleResult<Boolean> =
        try {
            ModuleResult.success(initialize(module, context))
        } catch (e: Exception) {
            // boundary: module initialize()/shutdown() is plugin code
            e.rethrowCancellation()
            val init = KarandashError.PLUGIN_INIT_FAILED
            ModuleResult.failure(init.format(mapOf("plugin" to module.id, "error" to e.message.orEmpty())), init.code, e)
        }

    override suspend fun track(module: Module) {
        tracked.update { list -> list.filterNot { it === module } + module }
    }

    override suspend fun untrack(moduleId: String) {
        tracked.update { list -> list.filterNot { it.id == moduleId } }
    }

    /**
     * Untracks and shuts down every tracked module with [moduleId] (same isolation as
     * [shutdownAll]). Returns `false` when no such module was tracked.
     */
    public suspend fun shutdown(moduleId: String): Boolean {
        val removed = tracked
            .getAndUpdate { list -> list.filterNot { it.id == moduleId } }
            .filter { it.id == moduleId }
        removed.asReversed().forEach { shutdownSafely(it) }
        return removed.isNotEmpty()
    }

    override suspend fun shutdownAll() {
        shutdownAll(shutdownTimeout)
    }

    /** [shutdownAll] with a per-module bound of [timeout] instead of the constructor's (`timeouts.pluginShutdown`). */
    public suspend fun shutdownAll(timeout: Duration) {
        tracked.getAndUpdate { emptyList() }.asReversed().forEach { shutdownSafely(it, timeout) }
    }

    /**
     * Initializes [modules] in order with contexts from [contextFactory], tracking each one that
     * returns `true`. Never throws except on cancellation.
     */
    public suspend fun initializeAll(
        modules: List<Module>,
        contextFactory: suspend (Module) -> PluginContext,
    ): InitializationReport {
        val skippedIds = mutableListOf<String>()
        val failedIds = mutableListOf<String>()
        for (module in modules) {
            when (val result = initializeResult(module, contextFactory(module))) {
                is ModuleResult.Success -> {
                    if (!result.value) skippedIds.add(module.id)
                }

                is ModuleResult.Failure -> {
                    logger.warn("${KarandashError.PLUGIN_INIT_FAILED.bracketCode} ${result.errorMessage}")
                    failedIds.add(module.id)
                }
            }
        }
        return InitializationReport(
            initialized = modules.size - skippedIds.size - failedIds.size,
            failed = failedIds.size,
            failedIds = failedIds,
            skippedIds = skippedIds,
        )
    }

    @Suppress("TooGenericExceptionCaught") // boundary: module shutdown must not throw
    private suspend fun shutdownSafely(
        module: Module,
        timeout: Duration = shutdownTimeout,
    ) {
        try {
            withTimeoutOrNull(timeout) { module.shutdown() }
                ?: logger.warn("Module '${module.id}' shutdown timed out after $timeout")
        } catch (e: Exception) {
            // boundary: module initialize()/shutdown() is plugin code
            e.rethrowCancellation()
            logger.warn("Module '${module.id}' shutdown failed: ${e.message}")
        }
    }

    public companion object {
        /** Default per-module shutdown bound. */
        public val DEFAULT_SHUTDOWN_TIMEOUT: Duration = 10.seconds
    }
}

/**
 * Summary of [ModuleLifecycleManager.initializeAll].
 *
 * @param initialized Modules whose `initialize()` returned `true`.
 * @param failed Number of modules whose `initialize()` threw.
 * @param failedIds IDs of modules whose `initialize()` threw.
 * @param skippedIds IDs of modules whose `initialize()` returned `false` (bad config, opted out).
 */
@Serializable
public data class InitializationReport(
    val initialized: Int,
    val failed: Int,
    val failedIds: List<String>,
    val skippedIds: List<String> = emptyList(),
)
