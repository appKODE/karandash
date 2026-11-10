package io.karandash.cli.pipeline

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.suspendCatching
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** Most files whose head content is fetched per run (one platform call each). */
internal const val MAX_HEAD_CONTENT_FILES = 100

/** Most head-content bytes kept per run across all files; files past it stay without `newContent`. */
internal const val MAX_HEAD_CONTENT_TOTAL_BYTES = 2 * 1024 * 1024

/** Consecutive failed lookups after which the remaining files are not fetched. */
private const val MAX_CONSECUTIVE_FAILURES = 3

private val HEAD_CONTENT_TYPES = setOf(ChangeType.ADDED, ChangeType.MODIFIED, ChangeType.RENAMED)

/**
 * Fills `FileChange.newContent` for added, modified and renamed files at the MR head commit (`diffRefs.headSha`, else
 * the source branch): from disk when the working copy is [WorkingCopyAtHead], from its git ([withLocalGitCommits]),
 * else from [provider]. At most [MAX_HEAD_CONTENT_FILES] files and
 * [MAX_HEAD_CONTENT_TOTAL_BYTES] in total, [BLAME_PARALLELISM] calls at a time; content a change already
 * carries is kept. Nothing is fetched when the platform does not [PlatformServiceModule.supportsFileContent]. A
 * failed lookup (an API error; a missing file is just `null`) is skipped; [MAX_CONSECUTIVE_FAILURES] in a row stop the
 * lookups not yet started and log once at warn that head content is partial, so a broken endpoint costs one
 * warning instead of one per file.
 */
internal suspend fun withHeadContent(
    mr: MergeRequest,
    provider: PlatformServiceModule,
    projectId: String,
    logger: Logger,
): MergeRequest {
    val local = WorkingCopyAtHead.matches(mr)
    if (!local && !provider.supportsFileContent) return mr
    val ref = mr.diffRefs?.headSha ?: mr.sourceBranch
    val targets = mr.changes
        .filter { it.newContent == null && it.changeType in HEAD_CONTENT_TYPES }
        .take(MAX_HEAD_CONTENT_FILES)
        .mapTo(mutableSetOf()) { it.path }
    val reader = localRepository(mr, NoOpLogger) { r, p -> provider.fetchFileContent(projectId, r, p) }
    val permits = Semaphore(BLAME_PARALLELISM)
    val failures = MutableStateFlow(0)
    val fetched = coroutineScope {
        mr.changes
            .map { change ->
                async {
                    if (change.path !in targets) return@async null
                    permits.withPermit {
                        if (failures.value >= MAX_CONSECUTIVE_FAILURES) return@withPermit null
                        suspendCatching {
                            reader.read(ref, change.path)
                        }.onSuccess { failures.value = 0 }
                            .onFailure {
                                if (failures.updateAndGet { n -> n + 1 } == MAX_CONSECUTIVE_FAILURES) {
                                    logger.warn(
                                        "Head content is partial: $MAX_CONSECUTIVE_FAILURES lookups in a row failed " +
                                            "(last: ${change.path}: ${it.message}), skipping the remaining files",
                                    )
                                }
                            }.getOrNull()
                    }
                }
            }.awaitAll()
    }
    // limit: files are fetched before the byte budget is applied; fetch lazily if the budget is often hit.
    val (changes, _) = mr.changes.zip(fetched).fold(emptyList<FileChange>() to 0) { (acc, total), (change, content) ->
        val size = content?.encodeToByteArray()?.size ?: 0
        if (content == null || total + size > MAX_HEAD_CONTENT_TOTAL_BYTES) {
            acc + change to total
        } else {
            acc + change.copy(newContent = content) to total + size
        }
    }
    return mr.copy(changes = changes)
}

/**
 * Reads [paths] from the local git (when it holds the base commit) or [provider] at the MR base (`diffRefs.baseSha`, else the target branch), never the head or the
 * working tree, for [io.karandash.api.module.PipelineExtension.baseFiles]: files the MR author must not be able to
 * change for their own run. A missing file is left out; a failed lookup is left out and logged at warn. Empty when the
 * platform does not [PlatformServiceModule.supportsFileContent].
 */
internal suspend fun withBaseFiles(
    mr: MergeRequest,
    provider: PlatformServiceModule,
    projectId: String,
    paths: Set<String>,
    logger: Logger,
): Map<String, String> {
    if (!provider.supportsFileContent) return emptyMap()
    val ref = mr.diffRefs?.baseSha ?: mr.targetBranch
    val reader = withLocalGitCommits(mr, NoOpLogger) { r, p -> provider.fetchFileContent(projectId, r, p) }
    val permits = Semaphore(BLAME_PARALLELISM)
    return coroutineScope {
        paths
            .map { path ->
                async {
                    permits.withPermit {
                        suspendCatching { reader.read(ref, path) }
                            .onFailure { logger.warn("Could not read $path at $ref: ${it.message}") }
                            .getOrNull()
                            ?.let { path to it }
                    }
                }
            }.awaitAll()
            .filterNotNull()
            .toMap()
    }
}
