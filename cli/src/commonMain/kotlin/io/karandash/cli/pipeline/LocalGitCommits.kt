package io.karandash.cli.pipeline

import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.run.RepositoryFileReader
import io.karandash.models.MergeRequest
import io.karandash.platform.GitRunner
import io.karandash.sdk.Logger

private val COMMIT_SHA = Regex("^[0-9a-fA-F]{40}([0-9a-fA-F]{24})?$")

/**
 * [delegate], except that the MR's base and head commits, when the working copy's git holds them, are read from git
 * objects ([GitRunner.readBlob]: size-capped, binary skipped): content-addressed, so never the author-controlled
 * working tree, and a path missing at that commit costs no platform call. Logs commits the local git lacks, so a missing fetch shows up.
 */
internal suspend fun withLocalGitCommits(
    mr: MergeRequest,
    logger: Logger,
    gitHead: String? = WorkingCopyAtHead.gitHead(),
    git: GitRunner = GitRunner.Default,
    delegate: RepositoryFileReader,
): RepositoryFileReader {
    if (gitHead == null) return delegate
    val shas = listOfNotNull(mr.diffRefs?.baseSha, mr.diffRefs?.headSha).filter { COMMIT_SHA.matches(it) }.distinct()
    val (local, missing) = shas.partition { git.git(listOf("cat-file", "-e", "--end-of-options", "$it^{commit}")) != null }
    if (missing.isNotEmpty()) {
        logger.info(
            "Commits ${missing.joinToString()} are not in the local git (fetch them to skip platform reads): read through the platform"
        )
    }
    if (local.isEmpty()) return delegate
    logger.info("Commits ${local.joinToString()} are in the local git: their files are read from git objects")
    return RepositoryFileReader { ref, path ->
        when {
            ref !in local -> delegate.read(ref, path)
            !WorkingCopyAtHead.isRepositoryPath(path) -> null
            else -> git.readBlob(ref, path, PlatformServiceModule.MAX_FILE_CONTENT_BYTES).textOrNull
        }
    }
}

/** [delegate] behind the working copy: head files from disk when it is at the head, else commits from its git. */
internal suspend fun localRepository(
    mr: MergeRequest,
    logger: Logger,
    delegate: RepositoryFileReader,
): RepositoryFileReader = withWorkingCopy(mr, logger, delegate = withLocalGitCommits(mr, logger, delegate = delegate))
