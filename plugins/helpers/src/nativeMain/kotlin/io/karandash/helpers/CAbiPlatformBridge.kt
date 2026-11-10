@file:OptIn(ExperimentalForeignApi::class)

package io.karandash.helpers

import io.karandash.api.module.PlatformServiceModule
import io.karandash.helpers.CAbiBridge.cabiInt
import io.karandash.helpers.CAbiBridge.cabiStr
import io.karandash.helpers.CAbiBridge.toKStringOrEmpty
import io.karandash.helpers.guest.GuestOpTable
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi

/**
 * C ABI wrappers for [PlatformServiceModule] exports: each decodes its C strings and answers from the shared
 * [GuestOpTable] (the same op table the JAR bridge serves) through [CAbiBridge.cabiInt] / [CAbiBridge.cabiStr].
 * Integer results are 1 on success, 0 on failure; string results are null on failure and must be freed with
 * [CAbiBridge.freeString].
 */
object CAbiPlatformBridge {
    /** Fetch a merge request by project ID and MR ID: the JSON-serialized `MergeRequest`, or null on error. */
    fun wrapFetchMergeRequest(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? = cabiStr { GuestOpTable.run(module, "fetchMergeRequest", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty()) }

    /** Fetch a merge request by URL: the JSON-serialized `MergeRequest`, or null on error. */
    fun wrapFetchMergeRequestByUrl(
        module: PlatformServiceModule,
        url: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? = cabiStr { GuestOpTable.run(module, "fetchMergeRequestByUrl", url.toKStringOrEmpty()) }

    /** Add labels (a JSON array) to an MR. */
    fun wrapAddLabels(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        labelsJson: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(module, "addLabels", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty(), labelsJson.toKStringOrEmpty())
        }

    /** Remove labels (a JSON array) from an MR. */
    fun wrapRemoveLabels(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        labelsJson: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(module, "removeLabels", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty(), labelsJson.toKStringOrEmpty())
        }

    /** Set draft status on an MR (`draft == 1`). */
    fun wrapSetDraft(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        draft: Int,
    ): Int = cabiInt { GuestOpTable.run(module, "setDraft", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty(), draft.toString()) }

    /** Update the title of an MR. */
    fun wrapUpdateTitle(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        title: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(module, "updateTitle", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty(), title.toKStringOrEmpty())
        }

    /** Update the description of an MR. */
    fun wrapUpdateDescription(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        description: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(
                module,
                "updateDescription",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                description.toKStringOrEmpty()
            )
        }

    /** Approve an MR. */
    fun wrapApprove(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
    ): Int = cabiInt { GuestOpTable.run(module, "approve", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty()) }

    /** Unapprove an MR. */
    fun wrapUnapprove(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
    ): Int = cabiInt { GuestOpTable.run(module, "unapprove", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty()) }

    /** Post a comment to an MR: the comment ID. */
    fun wrapPostComment(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        body: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        cabiStr {
            GuestOpTable.run(module, "postComment", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty(), body.toKStringOrEmpty())
        }

    /** Update a comment on an MR. */
    fun wrapUpdateComment(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        commentId: CPointer<ByteVar>?,
        body: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(
                module,
                "updateComment",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                commentId.toKStringOrEmpty(),
                body.toKStringOrEmpty()
            )
        }

    /** Find an existing comment on an MR by marker: its ID, or null. */
    fun wrapFindExistingComment(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        marker: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        cabiStr {
            GuestOpTable.run(
                module,
                "findExistingComment",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                marker.toKStringOrEmpty()
            )
        }

    /** Post an inline comment on an MR diff: the comment ID. */
    fun wrapPostInlineComment(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        body: CPointer<ByteVar>?,
        filePath: CPointer<ByteVar>?,
        line: Int,
        diffRefsJson: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        cabiStr {
            GuestOpTable.run(
                module,
                "postInlineComment",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                body.toKStringOrEmpty(),
                filePath.toKStringOrEmpty(),
                line.toString(),
                diffRefsJson.toKStringOrEmpty()
            )
        }

    /** Delete marked inline comments: how many were deleted (0 on failure). */
    fun deleteMarkedInlineComments(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        marker: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(
                module,
                "deleteMarkedInlineComments",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                marker.toKStringOrEmpty()
            )
        }

    /** Resolve marked inline comments: how many were resolved (0 on failure). */
    fun resolveMarkedInlineComments(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        marker: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(
                module,
                "resolveMarkedInlineComments",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                marker.toKStringOrEmpty()
            )
        }

    /** IDs of comments matching the marker as a JSON array. */
    fun findAllMatchingComments(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        marker: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        cabiStr {
            GuestOpTable.run(
                module,
                "findAllMatchingComments",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                marker.toKStringOrEmpty()
            )
        }

    /** Open MR ids targeting the branch as a JSON array; null when the module gives no answer or fails. */
    fun listOpenMergeRequestIds(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        targetBranch: CPointer<ByteVar>?,
        limit: Int,
    ): CPointer<ByteVar>? =
        cabiStr {
            GuestOpTable.run(
                module,
                "listOpenMergeRequestIds",
                projectId.toKStringOrEmpty(),
                targetBranch.toKStringOrEmpty(),
                limit.toString()
            )
        }

    /** Bodies of dismissed marked inline comments as a JSON array; null when the module gives no signal or fails. */
    fun listDismissedInlineComments(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        marker: CPointer<ByteVar>?,
    ): CPointer<ByteVar>? =
        cabiStr {
            GuestOpTable.run(
                module,
                "listDismissedInlineComments",
                projectId.toKStringOrEmpty(),
                mrId.toKStringOrEmpty(),
                marker.toKStringOrEmpty()
            )
        }

    /** Delete a comment on an MR. */
    fun wrapDeleteComment(
        module: PlatformServiceModule,
        projectId: CPointer<ByteVar>?,
        mrId: CPointer<ByteVar>?,
        commentId: CPointer<ByteVar>?,
    ): Int =
        cabiInt {
            GuestOpTable.run(module, "deleteComment", projectId.toKStringOrEmpty(), mrId.toKStringOrEmpty(), commentId.toKStringOrEmpty())
        }
}
