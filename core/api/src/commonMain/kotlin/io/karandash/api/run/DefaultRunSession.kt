package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleLookup
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext
import io.karandash.api.lifecycle.ModuleLifecycleManager
import io.karandash.api.module.ModuleScope
import io.karandash.api.support.rethrowCancellation
import io.karandash.logger.Logger
import kotlinx.atomicfu.atomic
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.safeCast

/**
 * Host implementation of [RunSession].
 *
 * Handles are memoized per module id and config JSON; a failed initialization is memoized as
 * `null` so a misconfigured module is reported once per run. All handle acquisition is
 * serialized by one [Mutex] (limit: one lock per session, per-key locks if init latency matters).
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
@OptIn(io.karandash.api.InternalKarandashApi::class)
@ExperimentalKarandashApi
internal class DefaultRunSession(
    private val lookup: ModuleLookup,
    private val logger: Logger,
) : RunSession,
    ModuleLookup by lookup {
    override val services: RunScopedServices = RunScopedServices().also { it.put(RunServiceKeys.LOGGER, logger) }

    private val lifecycle = ModuleLifecycleManager(logger)

    /** SHARED modules this run holds a [SharedModuleLeases] lease on, in tracking order. */
    private val leased = MutableStateFlow<List<Module>>(emptyList())
    private val handles = MutableStateFlow<Map<String, Module?>>(emptyMap())
    private val handleLock = Mutex()
    private val publishedEnrichment = MutableStateFlow(EnrichmentContext.EMPTY)
    private val closed = atomic(false)

    override val enrichment: EnrichmentContext get() = publishedEnrichment.value

    override fun <T : Any> service(key: ServiceKey<T>): T? = services[key]

    override fun <T : Any> provide(
        key: ServiceKey<T>,
        value: T,
    ) {
        services.put(key, value)
    }

    override fun publishEnrichment(enrichment: EnrichmentContext) {
        publishedEnrichment.value = enrichment
        services.put(RunServiceKeys.ENRICHMENT, enrichment)
    }

    override suspend fun track(module: Module) {
        if (module.scope == ModuleScope.PER_RUN) {
            lifecycle.track(module)
            return
        }
        // a SHARED instance serves concurrent runs: lease it so only the last run out shuts it down
        val newlyLeased = handleLock.withLock {
            val before = leased.value
            leased.value = before.filterNot { it === module } + module
            before.none { it === module }
        }
        if (newlyLeased) SharedModuleLeases.acquire(module)
    }

    override suspend fun <M : Module> handle(
        module: M,
        context: PluginContext,
    ): M? {
        if (module.scope != ModuleScope.PER_RUN) return module
        val key = module.id + KEY_SEPARATOR + context.pluginConfig.toString()
        val handle = handleLock.withLock {
            val memo = handles.value
            if (key in memo) {
                memo[key]
            } else {
                acquire(module, context).also { acquired -> handles.update { it + (key to acquired) } }
            }
        }
        return module::class.safeCast(handle)
    }

    @Suppress("TooGenericExceptionCaught") // boundary: newHandle()/initialize() of any module may throw
    private suspend fun acquire(
        module: Module,
        context: PluginContext,
    ): Module? {
        val handle = try {
            module.newHandle(context)
        } catch (e: Exception) {
            // boundary: third-party newHandle() may throw anything
            e.rethrowCancellation()
            logger.warn("Run handle for module '${module.id}' could not be created: ${e.message}")
            return null
        }
        val initialized = try {
            lifecycle.initialize(handle, context)
        } catch (e: Exception) {
            // boundary: third-party initialize() may throw anything
            e.rethrowCancellation()
            logger.warn("Run handle for module '${module.id}' failed to initialize: ${e.message}")
            false
        }
        if (initialized) return handle
        logger.warn("Run handle for module '${module.id}' is unavailable for this run")
        if (handle !== module) shutdownQuietly(handle)
        return null
    }

    @Suppress("TooGenericExceptionCaught") // boundary: shutdown() of a failed handle must not fail the run
    private suspend fun shutdownQuietly(handle: Module) {
        try {
            handle.shutdown()
        } catch (e: Exception) {
            // boundary: shutdown() contract says never throw; a broken module still must not escape
            e.rethrowCancellation()
            logger.warn("Run handle for module '${handle.id}' failed to shut down: ${e.message}")
        }
    }

    override suspend fun close() {
        if (!closed.compareAndSet(expect = false, update = true)) return
        lifecycle.shutdownAll()
        val shared = ModuleLifecycleManager(logger)
        leased.getAndUpdate { emptyList() }.asReversed().forEach { module ->
            SharedModuleLeases.release(module) {
                shared.track(it)
                shared.shutdownAll()
            }
        }
        handles.value = emptyMap()
    }

    private companion object {
        const val KEY_SEPARATOR = "\u0000"
    }
}
