package io.karandash.sdk.typed

import io.karandash.api.platform.UnsupportedPlatformOperationException
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.MergeRequest
import io.karandash.sdk.PlatformCloseable
import io.karandash.sdk.finding.MessageCode
import io.karandash.sdk.finding.ModuleMessage
import kotlinx.atomicfu.atomic
import kotlinx.serialization.KSerializer

/**
 * Abstract base class for [TypedPlatformServiceModule] implementations.
 *
 * Provides default stubs for provider and mutator methods, plus a default
 * [messages] implementation that emits a missing-auth hint when [authTokenHint]
 * is non-null.
 *
 * @param C The typed config class.
 */
public abstract class AbstractTypedPlatformServiceModule<C : Any>(
    override val configSerializer: KSerializer<C>,
) : TypedPlatformServiceModule<C> {
    private val managedCloseableRef = atomic<PlatformCloseable?>(null)

    /**
     * Registers the resource [shutdown] closes. Replacing a previously registered resource
     * does not close it; callers set this once from `initialize()`.
     */
    protected fun setManagedCloseable(closeable: PlatformCloseable?) {
        managedCloseableRef.value = closeable
    }

    override suspend fun shutdown() {
        managedCloseableRef.getAndSet(null)?.close()
    }

    override suspend fun messages(): List<ModuleMessage> {
        val hint = authTokenHint() ?: return emptyList()
        return listOf(
            ModuleMessage(
                MessageCode.MISSING_AUTH,
                "$name authentication not configured. Set $hint or configure auth.$dslName in your config."
            )
        )
    }

    protected suspend fun <Client : Any, R> withInitializedClient(
        client: Client?,
        platformName: String = name,
        block: suspend (Client) -> R
    ): R {
        val c = checkNotNull(client) { "$platformName not initialized" }
        return block(c)
    }

    /**
     * Runs [block] and returns [default] when it fails, passing `"$label: <message>"` to [log] first. For best-effort
     * reads (blame, enrichment) whose failure must not fail the run; cancellation is rethrown.
     */
    @Suppress("TooGenericExceptionCaught")
    protected suspend fun <T> bestEffort(
        label: String,
        default: T,
        log: ((String) -> Unit)? = null,
        block: suspend () -> T,
    ): T =
        try {
            block()
        } catch (e: Exception) {
            // boundary: best-effort platform read; the failure is logged and the default returned
            e.rethrowCancellation()
            log?.invoke("$label: ${e.message}")
            default
        }

    /**
     * Thrown by the default [fetchMergeRequestByUrl] when [parseMergeRequestUrl] does not accept [url]. Override to keep a
     * platform's own message; the default keeps the pre-existing "does not support fetching" error.
     */
    protected open fun invalidMergeRequestUrl(url: String): Nothing = fetchUnsupported()

    /**
     * One implementation behind [resolveMarkedInlineComments] (`filePaths == null`) and
     * [resolveMarkedInlineCommentsForFiles]. The default keeps the interface fallbacks: nothing for all files, and the
     * all-files call for a file subset, so a module that overrides only [resolveMarkedInlineComments] behaves as before.
     */
    protected open suspend fun resolveMarked(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>?,
    ): Int = if (filePaths == null) 0 else resolveMarkedInlineComments(projectId, mrId, marker)

    override suspend fun resolveMarkedInlineComments(
        projectId: String,
        mrId: String,
        marker: String,
    ): Int = resolveMarked(projectId, mrId, marker, filePaths = null)

    override suspend fun resolveMarkedInlineCommentsForFiles(
        projectId: String,
        mrId: String,
        marker: String,
        filePaths: Set<String>,
    ): Int = resolveMarked(projectId, mrId, marker, filePaths)

    private fun fetchUnsupported(): Nothing = error("$id does not support fetching merge requests")

    private fun mutationUnsupported(operation: String): Nothing = throw UnsupportedPlatformOperationException(operation)

    // ── Provider defaults (unsupported unless overridden) ──

    override suspend fun fetchMergeRequest(
        projectId: String,
        mrId: String
    ): MergeRequest = fetchUnsupported()

    /** Fetches the merge request [parseMergeRequestUrl] reads from [url]; [invalidMergeRequestUrl] when it reads none. */
    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val parsed = parseMergeRequestUrl(url) ?: invalidMergeRequestUrl(url)
        return fetchMergeRequest(parsed.projectId, parsed.mrId)
    }

    // ── Mutator defaults (unsupported unless overridden) ──

    override suspend fun addLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ): Unit = mutationUnsupported("addLabels")

    override suspend fun removeLabels(
        projectId: String,
        mrId: String,
        labels: List<String>
    ): Unit = mutationUnsupported("removeLabels")

    override suspend fun setDraft(
        projectId: String,
        mrId: String,
        draft: Boolean
    ): Unit = mutationUnsupported("setDraft")

    override suspend fun updateTitle(
        projectId: String,
        mrId: String,
        title: String
    ): Unit = mutationUnsupported("updateTitle")

    override suspend fun updateDescription(
        projectId: String,
        mrId: String,
        description: String
    ): Unit = mutationUnsupported("updateDescription")

    override suspend fun approve(
        projectId: String,
        mrId: String
    ): Unit = mutationUnsupported("approve")

    override suspend fun unapprove(
        projectId: String,
        mrId: String
    ): Unit = mutationUnsupported("unapprove")
}
