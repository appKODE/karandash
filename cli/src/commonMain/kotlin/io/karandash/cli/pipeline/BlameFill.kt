package io.karandash.cli.pipeline

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.suspendCatching
import io.karandash.models.ChangeType
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

// limit: fixed cap of per-file calls; raise if large MRs need full ownership data.
internal const val MAX_BLAME_FILES = 50

/** Blame lookups in flight at once: enough to hide latency, few enough to stay clear of platform rate limits. */
internal const val BLAME_PARALLELISM = 4

/**
 * Fills `FileChange.blameData` for modified and deleted files from [provider] at the MR base commit
 * (`diffRefs.baseSha`, else the target branch). At most [MAX_BLAME_FILES] files, [BLAME_PARALLELISM] at a
 * time; a failed lookup is logged at warn and leaves that file without blame.
 */
internal suspend fun withBlame(
    mr: MergeRequest,
    provider: PlatformServiceModule,
    projectId: String,
    logger: Logger,
): MergeRequest {
    val ref = mr.diffRefs?.baseSha ?: mr.targetBranch
    val targets = mr.changes
        .filter { it.changeType == ChangeType.MODIFIED || it.changeType == ChangeType.DELETED }
        .take(MAX_BLAME_FILES)
        .mapTo(mutableSetOf()) { it.path }
    val permits = Semaphore(BLAME_PARALLELISM)
    val changes = coroutineScope {
        mr.changes
            .map { change ->
                async {
                    if (change.path !in targets) return@async change
                    permits
                        .withPermit { suspendCatching { provider.fetchFileBlame(projectId, change.path, ref) } }
                        .onFailure { logger.warn("Blame for ${change.path} unavailable: ${it.message}") }
                        .getOrNull()
                        ?.let { change.copy(blameData = it) }
                        ?: change
                }
            }.awaitAll()
    }
    return mr.copy(changes = changes)
}
