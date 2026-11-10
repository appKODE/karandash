package io.karandash.loader

import io.karandash.api.Module
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Tracks loaded plugins and their resources for lifecycle management.
 *
 * Provides lookup by plugin id and closes resources
 * when plugins are removed. Resources can be ClassLoaders, JNA libraries,
 * GraalJS Contexts, or any other closeable resource.
 *
 * Thread-safe via [MutableStateFlow] with immutable map snapshots for reads
 * and atomic [update] for writes.
 */
class PluginCache {
    /** Not @Serializable: holds a live Module and its AutoCloseable resource. */
    private data class Entry(
        val module: Module,
        val resource: AutoCloseable?,
        val sourcePath: String
    )

    private val state = MutableStateFlow<Map<String, Entry>>(emptyMap())

    /** All currently cached module ids. */
    fun ids(): Set<String> = state.value.keys

    /** Number of cached plugins. */
    fun size(): Int = state.value.size

    /** Get a cached module by id. */
    fun get(id: String): Module? = state.value[id]?.module

    /** Get the source path for a cached module. */
    fun sourcePath(id: String): String? = state.value[id]?.sourcePath

    /** Check whether a module id is cached. */
    fun contains(id: String): Boolean = state.value.containsKey(id)

    /** All cached modules. */
    fun modules(): List<Module> = state.value.values.map { it.module }

    /**
     * Store a loaded module with its resource.
     * If a module with the same id already exists, its resource is closed first.
     */
    fun put(
        module: Module,
        resource: AutoCloseable?,
        sourcePath: String
    ) {
        var oldResource: AutoCloseable? = null
        state.update { current ->
            oldResource = current[module.id]?.resource
            current + (module.id to Entry(module, resource, sourcePath))
        }
        oldResource?.close()
    }

    /**
     * Remove a plugin and close its resource.
     *
     * @return true if the plugin was found and removed.
     */
    suspend fun remove(id: String): Boolean {
        var entry: Entry? = null
        state.update { current ->
            entry = current[id]
            if (entry != null) current - id else current
        }
        val removed = entry ?: return false
        removed.module.shutdown()
        removed.resource?.close()
        return true
    }

    /**
     * Shut down all plugins and close all resources.
     */
    suspend fun clear() {
        var snapshot: Collection<Entry> = emptyList()
        state.update { current ->
            snapshot = current.values
            emptyMap()
        }
        snapshot.forEach { entry ->
            entry.module.shutdown()
            entry.resource?.close()
        }
    }

    /**
     * Remove a plugin and close its resource **without** calling [Module.shutdown] - for owners
     * that shut the module down themselves (e.g. via a module lifecycle manager).
     *
     * @return true if the plugin was found and removed.
     */
    fun evict(id: String): Boolean {
        var entry: Entry? = null
        state.update { current ->
            entry = current[id]
            if (entry != null) current - id else current
        }
        val removed = entry ?: return false
        removed.resource?.close()
        return true
    }

    /** Remove all plugins and close their resources without calling [Module.shutdown]. */
    fun evictAll() {
        var snapshot: Collection<Entry> = emptyList()
        state.update { current ->
            snapshot = current.values
            emptyMap()
        }
        snapshot.forEach { it.resource?.close() }
    }
}
