@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.platforms.gerrit

import io.karandash.api.platform.MergeRequestSearchHit
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User

/**
 * The change messages as approval signals of their authors; Gerrit has no reactions, and a message without an author
 * (posted by Gerrit itself) is skipped.
 */
internal fun approvalSignals(messages: List<GerritChangeMessageInfo>): List<ApprovalSignal> =
    messages.mapNotNull { message ->
        message.author?.let { author ->
            val user = User(
                username = author.username ?: author.email ?: "user-${author.accountId}",
                name = author.name ?: "Unknown",
                email = author.email,
            )
            ApprovalSignal(user, ApprovalMethod.COMMENT_KEYWORD, message.message, message.date)
        }
    }

/** [GerritChange] as a search hit: `NEW` is open, `MERGED` merged and `ABANDONED` closed. */
internal fun GerritChange.toSearchHit(): MergeRequestSearchHit =
    MergeRequestSearchHit(
        id = number.toString(),
        title = subject,
        state = when (status) {
            "NEW" -> MergeRequestSearchHit.OPENED
            "MERGED" -> MergeRequestSearchHit.MERGED
            else -> MergeRequestSearchHit.CLOSED
        },
        author = owner.username ?: owner.email,
    )
