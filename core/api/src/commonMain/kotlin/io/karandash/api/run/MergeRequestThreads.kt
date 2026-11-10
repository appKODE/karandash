package io.karandash.api.run

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.finding.ReviewThread
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.platform.nullIfUnsupported

/** The run MR's review threads and the calls to act on them; see [RunServiceKeys.REVIEW_THREADS]. */
@ExperimentalKarandashApi
public interface MergeRequestThreads {
    /** Every thread with its notes, or `null` when the platform cannot list threads. */
    public suspend fun list(): List<ReviewThread>?

    /** Whether [username] is a project member; `false` when the platform cannot tell. */
    public suspend fun isMember(username: String): Boolean

    /** Username the bot posts as, or `null` when the platform cannot tell (the default). */
    public suspend fun botUsername(): String? = null

    /** Resolves thread [threadId] with the reply [note]. */
    public suspend fun resolve(
        threadId: String,
        note: String,
    ): Result<Unit>

    /** Replies [body] to thread [threadId], leaving it open. */
    public suspend fun reply(
        threadId: String,
        body: String,
    ): Result<Unit>

    public companion object {
        /** [MergeRequestThreads] of MR [mrId] in [projectId] backed by [platform]'s thread calls. */
        public fun of(
            platform: PlatformServiceModule,
            projectId: String,
            mrId: String,
        ): MergeRequestThreads =
            object : MergeRequestThreads {
                override suspend fun list() = nullIfUnsupported { platform.listReviewThreads(projectId, mrId) }

                override suspend fun isMember(username: String) = platform.isProjectMember(projectId, username) == true

                override suspend fun botUsername() = platform.currentUsername()

                override suspend fun resolve(
                    threadId: String,
                    note: String,
                ) = platform.resolveThread(projectId, mrId, threadId, note)

                override suspend fun reply(
                    threadId: String,
                    body: String,
                ) = platform.replyToThread(projectId, mrId, threadId, body)
            }
    }
}
