package io.karandash.api.run

import io.karandash.api.Module
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Process-wide lease counts on SHARED module instances, keyed by identity.
 *
 * Every run that tracks a SHARED module acquires a lease before initializing it and releases
 * it on close; the release that drops the count to zero shuts the module down. Acquire and
 * the final shutdown hold one [Mutex], so a run starting meanwhile initializes the module only
 * after that shutdown finished, and a run still holding a lease never sees it shut down.
 *
 * Application-scoped and thread-safe (limit: one global lock, per-module locks if shutdown
 * latency ever matters).
 */
internal object SharedModuleLeases {
    private val lock = Mutex()
    private val counts = MutableStateFlow<List<Pair<Module, Int>>>(emptyList())

    suspend fun acquire(module: Module) {
        lock.withLock {
            val current = count(module)
            counts.value = counts.value.filterNot { it.first === module } + (module to current + 1)
        }
    }

    /** Releases one lease on [module]; runs [shutdown] under the lock when it was the last one. */
    suspend fun release(
        module: Module,
        shutdown: suspend (Module) -> Unit,
    ) {
        lock.withLock {
            val remaining = count(module) - 1
            val others = counts.value.filterNot { it.first === module }
            counts.value = if (remaining > 0) others + (module to remaining) else others
            if (remaining <= 0) shutdown(module)
        }
    }

    /** Leases currently held on [module]; for tests. */
    fun count(module: Module): Int = counts.value.firstOrNull { it.first === module }?.second ?: 0
}
