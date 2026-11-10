package io.karandash.api.module

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.duration.KarandashDuration

/**
 * Host-provided byte store backing the `cache.state` namespace, so modules can persist
 * their own per-run state without depending on the host's cache implementation.
 * Entries expire after [ttl].
 */
@ExperimentalKarandashApi
public interface ExtensionStateStore {
    /** Time-to-live applied to every [put]. */
    public val ttl: KarandashDuration

    /**
     * False under `--no-cache`: modules must not reuse cached results (responses, findings,
     * snapshots) but still write state and may read user-supplied state such as feedback.
     */
    public val reuseCached: Boolean get() = true

    public fun get(key: String): ByteArray?

    /**
     * Like [get], but the host may also look in a shared remote cache when [key] is not stored locally, and keep
     * what it finds so later [get] calls see it. Call it once per key early in the run, from a suspending hook.
     */
    public suspend fun fetch(key: String): ByteArray? = get(key)

    public fun put(
        key: String,
        content: ByteArray,
    )

    public fun invalidate(key: String)

    public fun keys(): List<String>
}

/**
 * Implemented by a [Module] or [PipelineExtension] that persists state. The host calls
 * [configureStateStore] before initialization with the `cache.state` store, or null when
 * state caching is disabled.
 */
@ExperimentalKarandashApi
public interface StatePersistenceAware {
    public fun configureStateStore(store: ExtensionStateStore?)
}
