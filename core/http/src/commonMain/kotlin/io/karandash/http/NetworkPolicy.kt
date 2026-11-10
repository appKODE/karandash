package io.karandash.http

import kotlinx.atomicfu.atomic
import kotlinx.coroutines.currentCoroutineContext
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/**
 * The network policy of one run, carried in the coroutine context: `allow_private_network:` lives here, not in a
 * process-wide flag, so two runs (an LSP request next to a `validate`, two `--watch` passes) never see each
 * other's setting. Clients built by [ProviderHttpClientFactory] read it per request; with no policy in the context
 * private and dotless hosts are refused.
 *
 * The value is set while the run's config loads (from the `extends:` chain, then the merged config), so it is a
 * mutable holder shared by every coroutine of the run; [withContext] a new instance to scope a different value
 * to a block (one `extends:` fetch group).
 *
 * @lifecycle per-run -- do not reuse across invocations
 */
class NetworkPolicy(
    allowPrivateNetwork: Boolean = false,
) : AbstractCoroutineContextElement(Key) {
    private val allowPrivate = atomic(allowPrivateNetwork)

    /** `allow_private_network:` - whether [SsrfHostKind.PRIVATE] and [SsrfHostKind.DOTLESS] hosts are permitted. */
    var allowPrivateNetwork: Boolean
        get() = allowPrivate.value
        set(value) {
            allowPrivate.value = value
        }

    companion object Key : CoroutineContext.Key<NetworkPolicy> {
        /** The policy of the calling coroutine's run, or null outside one. */
        suspend fun current(): NetworkPolicy? = currentCoroutineContext()[Key]

        /** Whether the calling run permits private and dotless hosts; false outside a run. */
        suspend fun allowsPrivateNetwork(): Boolean = current()?.allowPrivateNetwork == true

        /** Sets `allow_private_network:` for the calling run; outside a run there is nothing to set. */
        suspend fun setAllowPrivateNetwork(allow: Boolean) {
            current()?.allowPrivateNetwork = allow
        }
    }
}
