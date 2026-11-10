package io.karandash.plugins.outputs

import io.karandash.api.finding.FormattedReport
import io.karandash.api.module.PlatformServiceModule
import io.karandash.api.support.rethrowCancellation
import io.karandash.helpers.output.NotificationState
import io.karandash.logger.Logger

/**
 * Posts or updates the summary comment of one merge request, and decides whether it needs posting at all: with a
 * [NotificationState] it posts only what `when` allows and makes no call when the body is unchanged.
 *
 * @lifecycle per-run -- built for one output call.
 */
internal class SummaryCommentPoster(
    private val platform: PlatformServiceModule,
    private val project: String,
    private val mr: String,
    private val logger: Logger,
    private val stamp: UpdatedStamp? = null,
) {
    /** What the last summary told the MR, or null when it carries no state (or reading it failed). */
    @Suppress("TooGenericExceptionCaught") // boundary: an unreadable previous state only means "post as before"
    suspend fun previousState(): NotificationState? =
        try {
            NotificationState.parse(platform.findCommentBody(project, mr, NotificationState.PREFIX))
        } catch (e: Exception) {
            // boundary: platform API call; the summary is then posted as if nothing was there
            e.rethrowCancellation()
            logger.warn("Could not read the previous summary state, posting as usual: ${e.message}")
            null
        }

    /** The marker the summary comment starts with; null when the format has none (no summary is posted). */
    fun marker(
        report: FormattedReport,
        config: PlatformOutputConfig,
    ): String? = report.commentMarker?.let { config.commentMarker ?: it }

    /**
     * The summary body to post, or null when there is nothing to post: no marker, `when` holds it back, or the body
     * is the one already on the MR ([previous]). Without [PlatformOutputConfig.notificationState] it posts as before.
     * The footer's `updated …` time ([stamp]) is added after the body is hashed, so it never makes a body look changed;
     * the inline [delta] section is hashed with the body.
     */
    fun bodyToPost(
        report: FormattedReport,
        config: PlatformOutputConfig,
        previous: NotificationState?,
        delta: String = "",
    ): String? {
        val marker = marker(report, config) ?: return null
        val base = if (report.body.trimStart().startsWith(marker)) report.body else "$marker\n${report.body}"
        val withDelta = if (delta.isEmpty()) base else "$base\n\n$delta"
        val sent = stamp?.summary(withDelta) ?: withDelta
        val state = config.notificationState ?: return sent
        val current = state.copy(body = NotificationState.hash(withDelta.replace(DURATION_CHIP, "$1")))
        // a changed text with the same findings (a new layout, an inline thread opened or fixed) still edits the comment
        // already on the MR unless `updateOnTextChange: false`: an edit notifies nobody
        val textChanged = config.updateOnTextChange && previous != null && previous.body != current.body
        if (!config.notifyWhen.allows(state.passed, changed = !state.sameOutcome(previous) || textChanged)) {
            logger.info("Summary comment not posted: when=${config.notifyWhen.name.lowercase()} and nothing calls for it")
            return null
        }
        if (previous?.body == current.body) {
            logger.info("Summary comment unchanged, not updated")
            return null
        }
        return "$sent\n${current.toMarker()}"
    }

    /** Updates the comment carrying [marker] (deleting duplicates) or posts a new one. */
    suspend fun post(
        body: String,
        marker: String,
    ) {
        val allMatching = platform.findAllMatchingComments(project, mr, marker)
        val existingCommentId = allMatching.firstOrNull()
        deleteDuplicateComments(allMatching.drop(1))
        if (existingCommentId != null) {
            platform.updateComment(project, mr, existingCommentId, body)
            logger.info("Comment updated (id=$existingCommentId)")
        } else {
            platform.postComment(project, mr, body)
            logger.info("Comment created")
        }
    }

    @Suppress("TooGenericExceptionCaught") // boundary: duplicate cleanup is non-fatal
    private suspend fun deleteDuplicateComments(duplicateIds: List<String>) {
        for (duplicateId in duplicateIds) {
            try {
                platform.deleteComment(project, mr, duplicateId)
                logger.debug("Deleted duplicate header comment (id=$duplicateId)")
            } catch (e: Exception) {
                // boundary: platform API call; failure is logged and reported
                e.rethrowCancellation()
                logger.warn("Failed to delete duplicate comment $duplicateId: ${e.message}")
            }
        }
    }

    private companion object {
        // limit: only the per-run duration chips (`aiDurationMs: 92`) are left out of the body hash; add any other
        // value that changes from run to run with the same findings here, or the summary is re-posted every run
        val DURATION_CHIP = Regex("""\b(\w*DurationMs: )\d+""")
    }
}
