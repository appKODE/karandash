@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.cli.pipeline

import io.karandash.api.run.MergeRequestThreads
import io.karandash.sdk.Logger

/** [inner] that only reads: resolving and replying are logged instead of sent (`--dry-run-comment`). */
internal class ReadOnlyMergeRequestThreads(
    private val inner: MergeRequestThreads,
    private val logger: Logger,
) : MergeRequestThreads by inner {
    override suspend fun resolve(
        threadId: String,
        note: String,
    ): Result<Unit> = Result.success(Unit).also { logger.info("--dry-run-comment: would resolve thread $threadId") }

    override suspend fun reply(
        threadId: String,
        body: String,
    ): Result<Unit> = Result.success(Unit).also { logger.info("--dry-run-comment: would reply to thread $threadId") }
}
