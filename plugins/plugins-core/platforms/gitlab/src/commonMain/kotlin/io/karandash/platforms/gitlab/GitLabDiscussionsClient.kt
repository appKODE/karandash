package io.karandash.platforms.gitlab

import io.karandash.api.exception.ProviderApiException
import io.karandash.api.finding.FindingFeedback
import io.karandash.api.finding.InlineFindingMarker
import io.karandash.api.finding.ReviewAward
import io.karandash.api.finding.ReviewNote
import io.karandash.api.finding.ReviewThread
import io.karandash.api.finding.ReviewThreadPosition
import io.karandash.api.support.rethrowCancellation
import io.karandash.models.ApprovalMethod
import io.karandash.models.ApprovalSignal
import io.karandash.models.User
import io.karandash.sdk.Logger
import io.karandash.sdk.NoOpLogger
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.encodeURLParameter
import kotlinx.io.IOException

/**
 * Merge request discussions (inline threads) on top of [api]'s connection and auth: listing, posting, resolving
 * and deleting threads. Stateless; build one per call.
 */
class GitLabDiscussionsClient(
    private val api: GitLabClient,
    private val logger: Logger = NoOpLogger,
) {
    private val baseUrl: String get() = api.baseUrl

    private fun mrUrl(
        projectId: String,
        mrIid: Int,
    ): String = "$baseUrl/api/v4/projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid"

    /**
     * List all discussions on a merge request, paginating through all pages.
     *
     * Returns full discussion objects including notes, which is needed for deleting
     * DiffNote-type inline comments via [deleteDiscussionNote].
     */
    suspend fun listDiscussions(
        projectId: String,
        mrIid: Int,
    ): List<GitLabDiscussionDetail> {
        val url = "${mrUrl(projectId, mrIid)}/discussions"
        return api.paginateGet(url, "GET /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/discussions")
    }

    /**
     * Delete a note from a diff discussion on a merge request.
     *
     * Use this for DiffNote-type notes (inline diff comments created via the discussions API).
     * Regular notes (`individual_note = true`) should use [GitLabClient.deleteNote] instead.
     */
    suspend fun deleteDiscussionNote(
        projectId: String,
        mrIid: Int,
        discussionId: String,
        noteId: Long,
    ) {
        val url = "${mrUrl(projectId, mrIid)}/discussions/$discussionId/notes/$noteId"
        val response = api.send { delete(url) { api.authorize(this) } }
        api.ensureSuccess(
            response,
            "DELETE /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/discussions/$discussionId/notes/$noteId",
        )
    }

    /**
     * Resolve a discussion thread on a merge request: `PUT .../discussions/:discussion_id` with `{"resolved": true}`.
     */
    suspend fun resolveDiscussion(
        projectId: String,
        mrIid: Int,
        discussionId: String,
    ) {
        val url = "${mrUrl(projectId, mrIid)}/discussions/$discussionId"
        val response = api.send {
            put(url) {
                api.authorize(this)
                contentType(ContentType.Application.Json)
                setBody(mapOf("resolved" to true))
            }
        }
        api.ensureSuccess(response, "PUT /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/discussions/$discussionId")
    }

    /**
     * Create a diff discussion (inline comment) on a merge request at [newLine] of [newPath].
     * [oldPath] defaults to [newPath] for non-rename cases. A deletion anchors on [oldLine] of the base file
     * with a null [newLine]; a context line carries both; GitLab rejects a position with neither.
     */
    suspend fun createDiffDiscussion(
        projectId: String,
        mrIid: Int,
        body: String,
        baseSha: String,
        startSha: String,
        headSha: String,
        newPath: String,
        newLine: Int?,
        oldPath: String = newPath,
        oldLine: Int? = null,
    ): GitLabDiscussion {
        require(newLine != null || oldLine != null) { "A diff discussion needs a new line or an old line" }
        val url = "${mrUrl(projectId, mrIid)}/discussions"
        val response = api.send {
            post(url) {
                api.authorize(this)
                contentType(ContentType.Application.Json)
                setBody(
                    GitLabCreateDiffDiscussionRequest(
                        body = body,
                        position = GitLabDiffPosition(
                            baseSha = baseSha,
                            startSha = startSha,
                            headSha = headSha,
                            oldPath = oldPath,
                            newPath = newPath,
                            newLine = newLine,
                            oldLine = oldLine,
                        ),
                    ),
                )
            }
        }
        return api.decodeResponse(response, "POST /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/discussions")
    }

    /**
     * Posts [drafts] as draft notes, then publishes them all at once (`bulk_publish`): one review, one notification.
     * A GitLab without draft notes answers the first `POST` with 404 ([ProviderApiException]) before any draft exists.
     */
    suspend fun publishDraftNotes(
        projectId: String,
        mrIid: Int,
        drafts: List<GitLabCreateDraftNoteRequest>,
    ) {
        val url = "${mrUrl(projectId, mrIid)}/draft_notes"
        val label = "POST /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/draft_notes"
        for (draft in drafts) {
            val response = api.send {
                post(url) {
                    api.authorize(this)
                    contentType(ContentType.Application.Json)
                    setBody(draft)
                }
            }
            api.ensureSuccess(response, label)
        }
        api.ensureSuccess(api.send { post("$url/bulk_publish") { api.authorize(this) } }, "$label/bulk_publish")
    }

    /**
     * Approval-like signals: every user comment ([ApprovalMethod.COMMENT_KEYWORD], the note body) and every reaction
     * on the merge request itself ([ApprovalMethod.EMOJI_REACTION], the emoji name). System notes carry no author intent.
     */
    suspend fun listApprovalSignals(
        projectId: String,
        mrIid: Int,
    ): List<ApprovalSignal> {
        val comments = api.listNotes(projectId, mrIid).filter { !it.system }.mapNotNull { note ->
            note.author?.let { ApprovalSignal(it.toUser(), ApprovalMethod.COMMENT_KEYWORD, note.body, note.createdAt) }
        }
        val reactions = api
            .paginateGet<GitLabAwardEmoji>(
                "${mrUrl(projectId, mrIid)}/award_emoji",
                "GET /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/award_emoji",
            ).mapNotNull { award ->
                award.user?.let { ApprovalSignal(it.toUser(), ApprovalMethod.EMOJI_REACTION, award.name) }
            }
        return comments + reactions
    }

    private fun GitLabUser.toUser() = User(username = username, name = name, email = email)

    /** Reactions on note [noteId], all pages. */
    suspend fun listAwardEmoji(
        projectId: String,
        mrIid: Int,
        noteId: Long,
    ): List<GitLabAwardEmoji> =
        api.paginateGet(
            "${mrUrl(projectId, mrIid)}/notes/$noteId/award_emoji",
            "GET /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/notes/$noteId/award_emoji",
        )

    /** Every discussion with all its notes; reactions are fetched per non-system note (one call each). */
    suspend fun listReviewThreads(
        projectId: String,
        mrIid: Int,
    ): List<ReviewThread> =
        listDiscussions(projectId, mrIid).map { discussion ->
            val notes = discussion.notes.map { note ->
                val awards = if (note.system) emptyList() else listAwardEmoji(projectId, mrIid, note.id)
                note.toReviewNote(awards)
            }
            val resolvable = discussion.notes.filter { it.resolvable }
            ReviewThread(
                id = discussion.id,
                notes = notes,
                resolved = discussion.resolved || (resolvable.isNotEmpty() && resolvable.all { it.resolved }),
                position = discussion.notes.firstNotNullOfOrNull { it.position }?.let {
                    ReviewThreadPosition(newPath = it.newPath, oldPath = it.oldPath, newLine = it.newLine, oldLine = it.oldLine)
                },
            )
        }

    /**
     * Marked notes a reviewer dismissed: their thread was resolved, or the note got a thumbs-down, by someone other
     * than the note's author. Reactions are only fetched for marked notes not already dismissed by a resolve.
     */
    suspend fun listDismissedInlineComments(
        projectId: String,
        mrIid: Int,
        marker: String,
    ): List<String> =
        // The bot authored the marked note, so a resolve or a thumbs-down by anyone else is a reviewer dismissing it.
        // GitLab discussion notes carry no award counts, so reactions cost one call per marked note a resolve did not dismiss.
        listDiscussions(projectId, mrIid)
            .flatMap { it.notes }
            .filter { !it.system && it.body.contains(marker) }
            .filter { note ->
                val author = note.author?.username
                val resolver = note.resolvedBy?.username
                (note.resolved && resolver != null && resolver != author) || thumbedDownByOther(projectId, mrIid, note)
            }.map { it.body }

    /**
     * Thumbs-downs on karandash inline notes (those carrying [InlineFindingMarker] lines): one [FindingFeedback] per
     * finding in the note and per user who reacted, except the note's author (the bot). Reactions cost one call per
     * marked note, for at most [MAX_FEEDBACK_NOTES] notes; every listing stays within the client's page cap.
     */
    suspend fun listFindingFeedback(
        projectId: String,
        mrIid: Int,
    ): List<FindingFeedback> {
        val marked = listDiscussions(projectId, mrIid).flatMap { it.notes }.filter { !it.system && InlineFindingMarker.PREFIX in it.body }
        if (marked.size > MAX_FEEDBACK_NOTES) {
            logger.warn("Reading feedback of the first $MAX_FEEDBACK_NOTES of ${marked.size} karandash notes")
        }
        return marked.take(MAX_FEEDBACK_NOTES).flatMap { note ->
            val reactors = listAwardEmoji(projectId, mrIid, note.id)
                .filter { it.name == THUMBS_DOWN }
                .mapNotNull { it.user?.username }
                .filter { it != note.author?.username }
                .distinct()
            val refs = InlineFindingMarker.parse(note.body)
            reactors.flatMap { user ->
                refs.map { FindingFeedback(rule = it.rule, fingerprint = it.fpv.ifEmpty { it.semanticFingerprint }, author = user) }
            }
        }
    }

    /** Whether someone other than [note]'s author thumbed it down; a failed reactions call counts as no. */
    private suspend fun thumbedDownByOther(
        projectId: String,
        mrIid: Int,
        note: GitLabNote,
    ): Boolean {
        val awards = try {
            listAwardEmoji(projectId, mrIid, note.id)
        } catch (e: ProviderApiException) {
            e.rethrowCancellation()
            return awardsUnavailable(note, e)
        } catch (e: IOException) {
            return awardsUnavailable(note, e)
        }
        val author = note.author?.username
        return awards.any { award ->
            val by = award.user?.username
            award.name == THUMBS_DOWN && by != null && by != author
        }
    }

    private fun awardsUnavailable(
        note: GitLabNote,
        e: Exception,
    ): Boolean {
        logger.warn("Reactions of note ${note.id} unavailable, treating it as not dismissed: ${e.message}")
        return false
    }

    /**
     * Resolve discussion [discussionId], then reply with [note] when it is given. Resolving first means a failed
     * resolve leaves no reply claiming the thread was handled.
     */
    suspend fun resolveThread(
        projectId: String,
        mrIid: Int,
        discussionId: String,
        note: String?,
    ) {
        resolveDiscussion(projectId, mrIid, discussionId)
        if (note != null) replyToThread(projectId, mrIid, discussionId, note)
    }

    /** Add a note with [body] to discussion [discussionId]: `POST .../discussions/:discussion_id/notes`. */
    suspend fun replyToThread(
        projectId: String,
        mrIid: Int,
        discussionId: String,
        body: String,
    ) {
        val url = "${mrUrl(projectId, mrIid)}/discussions/$discussionId/notes"
        val response = api.send {
            post(url) {
                api.authorize(this)
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        api.ensureSuccess(
            response,
            "POST /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/discussions/$discussionId/notes"
        )
    }

    /**
     * Whether [username] is a member of [projectId] with at least Developer access, inherited membership included:
     * `GET .../members/all?query=`. Guests and Reporters cannot push, so they do not steer the bot either.
     */
    suspend fun isProjectMember(
        projectId: String,
        username: String,
    ): Boolean {
        val encoded = api.encodeProjectId(projectId)
        val members: List<GitLabMember> = api.paginateGet(
            "$baseUrl/api/v4/projects/$encoded/members/all?query=${username.encodeURLParameter()}",
            "GET /projects/$encoded/members/all",
        )
        return members.any { it.username == username && it.accessLevel >= DEVELOPER_ACCESS }
    }

    /** Username of the account the token posts as: `GET /user`. */
    suspend fun currentUsername(): String {
        val response = api.send { get("$baseUrl/api/v4/user") { api.authorize(this) } }
        return api.decodeResponse<GitLabUser>(response, "GET /user").username
    }

    /** Delete every non-system [marker]ed MR note (the note strategy). Returns the number deleted. */
    suspend fun deleteMarkedMrNotes(
        projectId: String,
        mrIid: Int,
        marker: String,
    ): Int {
        val marked = api.listNotes(projectId, mrIid).filter { !it.system && it.body.contains(marker) }
        marked.forEach { api.deleteNote(projectId, mrIid, it.id) }
        return marked.size
    }

    /** Replace the body of note [noteId] in discussion [discussionId]. */
    suspend fun updateNote(
        projectId: String,
        mrIid: Int,
        discussionId: String,
        noteId: Long,
        body: String,
    ) {
        val url = "${mrUrl(projectId, mrIid)}/discussions/$discussionId/notes/$noteId"
        val response = api.send {
            put(url) {
                api.authorize(this)
                contentType(ContentType.Application.Json)
                setBody(mapOf("body" to body))
            }
        }
        api.ensureSuccess(
            response,
            "PUT /projects/${api.encodeProjectId(projectId)}/merge_requests/$mrIid/discussions/$discussionId/notes/$noteId",
        )
    }

    private fun GitLabNote.toReviewNote(awards: List<GitLabAwardEmoji>) =
        ReviewNote(
            id = id.toString(),
            body = body,
            author = author?.username,
            createdAt = createdAt,
            system = system,
            resolvable = resolvable,
            resolved = resolved,
            resolvedBy = resolvedBy?.username,
            awards = awards.map { ReviewAward(name = it.name, user = it.user?.username) },
        )

    private companion object {
        /** GitLab's award emoji name for a thumbs-down reaction. */
        const val THUMBS_DOWN = "thumbsdown"

        /** Most marked notes [listFindingFeedback] reads reactions of: one request each. */
        const val MAX_FEEDBACK_NOTES = 100

        /** GitLab `access_level` of the Developer role. */
        const val DEVELOPER_ACCESS = 30
    }

    /**
     * Resolve the open threads holding a [marker]ed note; with [filePaths], only threads positioned on one of them.
     * Resolving is best-effort: a failed resolve is logged and skipped. Returns the number resolved.
     */
    suspend fun resolveMarkedThreads(
        projectId: String,
        mrIid: Int,
        marker: String,
        filePaths: Set<String>? = null,
    ): Int {
        var resolvedCount = 0
        for (discussion in listDiscussions(projectId, mrIid)) {
            if (discussion.notes.all { !it.resolvable || it.resolved }) continue
            if (discussion.notes.none { !it.system && it.body.contains(marker) }) continue
            val onFile = filePaths == null ||
                discussion.notes.any { it.position?.newPath in filePaths || it.position?.oldPath in filePaths }
            if (!onFile) continue
            try {
                resolveDiscussion(projectId, mrIid, discussion.id)
                resolvedCount++
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                // boundary: platform API call; resolving is best-effort
                e.rethrowCancellation()
                logger.debug("resolveMarkedThreads: failed to resolve ${discussion.id}: ${e.message}")
            }
        }
        return resolvedCount
    }

    /** Delete every non-system [marker]ed note, discussion by discussion. Returns the number deleted. */
    suspend fun deleteMarkedNotes(
        projectId: String,
        mrIid: Int,
        marker: String,
    ): Int {
        var deletedCount = 0
        for (discussion in listDiscussions(projectId, mrIid)) {
            for (note in discussion.notes) {
                if (!note.system && note.body.contains(marker)) {
                    deleteDiscussionNote(projectId, mrIid, discussion.id, note.id)
                    deletedCount++
                }
            }
        }
        return deletedCount
    }
}
