package io.karandash.plugins.ai.core

/**
 * Marks a module that can receive an [AiStatePersistence] instance before initialization.
 *
 * The CLI layer calls [configureStatePersistence] after creating a [io.karandash.cache.CacheManager]
 * from the resolved `cache.state` config block, and before calling [io.karandash.api.Module.initialize].
 */
interface AiStatePersistenceAware {
    /**
     * Provides the [AiStatePersistence] instance to the module.
     *
     * @param persistence The persistence instance backed by the `state` CAS namespace,
     *   or null if state caching is disabled.
     */
    fun configureStatePersistence(persistence: AiStatePersistence?)
}
