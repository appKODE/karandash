package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.Module
import io.karandash.api.ModuleLookup
import io.karandash.api.PluginContext
import io.karandash.api.context.EnrichmentContext

/** Read view of one validation run: modules, run services, enrichment and per-run handles. */
@ExperimentalKarandashApi
public interface RunContext : ModuleLookup {
    /** Enrichment published for this run; [EnrichmentContext.EMPTY] until enrichment has run. */
    public val enrichment: EnrichmentContext

    /** Returns the run service for [key], or `null`. */
    public fun <T : Any> service(key: ServiceKey<T>): T?

    /**
     * SHARED module: returns [module]. PER_RUN module: returns the memoized, initialized and
     * tracked handle for [context]'s config, or `null` when its initialization failed.
     */
    public suspend fun <M : Module> handle(
        module: M,
        context: PluginContext,
    ): M?
}

/** Owner side of a run: provides services, tracks modules and tears them down. */
@ExperimentalKarandashApi
public interface RunSession : RunContext {
    /** Coroutine-context element exposing this run's services; run the pipeline inside it. */
    public val services: RunScopedServices

    public fun <T : Any> provide(
        key: ServiceKey<T>,
        value: T,
    )

    public fun publishEnrichment(enrichment: EnrichmentContext)

    /**
     * Tracks a module initialized elsewhere so [close] shuts it down. Tracking is a set.
     * A SHARED module is leased: track it *before* initializing it, and [close] shuts it down
     * only when no other open run still tracks it.
     */
    public suspend fun track(module: Module)

    /** Shuts down tracked modules in reverse order, then drops the handles. Idempotent; never throws. */
    public suspend fun close()
}
