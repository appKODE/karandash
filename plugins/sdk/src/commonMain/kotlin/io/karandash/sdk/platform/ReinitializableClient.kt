package io.karandash.sdk.platform

import io.karandash.api.ExperimentalKarandashApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate

/**
 * Holds a module's client across re-initializations.
 *
 * [rebuild] is a no-op when [key] (the resolved URL, credentials and flags the client is built
 * from) equals the current client's key, so a re-initialize with an unchanged config never
 * closes a client another run is using. On a changed key it builds the replacement first,
 * publishes it, and only then closes the replaced client.
 *
 * limit: a run still mid-request on a replaced client (an overlapping run with a different
 * config) sees it closed; per-run module handles remove that.
 *
 * @param close Releases one client.
 */
@ExperimentalKarandashApi
public class ReinitializableClient<C : Any>(
    private val close: suspend (C) -> Unit,
) {
    private val state = MutableStateFlow<Pair<Any, C>?>(null)

    /** The current client, or `null` before the first [rebuild] and after [close]. */
    public val value: C? get() = state.value?.second

    /** Returns the client for [key], building it with [build] unless the current one already matches. */
    public suspend fun rebuild(
        key: Any,
        build: () -> C,
    ): C {
        while (true) {
            val current = state.value
            if (current != null && current.first == key) return current.second
            val fresh = build()
            if (state.compareAndSet(current, key to fresh)) {
                current?.second?.let { close(it) }
                return fresh
            }
            // another initialize won the race: drop ours and re-check against the winner
            close(fresh)
        }
    }

    /** Closes and forgets the current client. Idempotent. */
    public suspend fun close() {
        state.getAndUpdate { null }?.second?.let { close(it) }
    }
}
