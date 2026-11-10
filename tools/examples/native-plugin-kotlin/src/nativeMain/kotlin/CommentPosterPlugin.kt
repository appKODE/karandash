@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.serialization.Serializable
import kotlinx.cinterop.*
import platform.posix.strdup
import kotlin.concurrent.AtomicInt
import kotlin.concurrent.AtomicReference

private fun String.toCCommentString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/** A stored comment and the MR (`project/mrId`) it belongs to. */
@Serializable
private data class StoredComment(val mrKey: String, val body: String)

/**
 * Simulated comment store for this example, keyed by comment id. A real plugin would use the platform API.
 * Atomics because the host may call the exports from several threads.
 */
private val commentStore = AtomicReference<Map<String, StoredComment>>(emptyMap())
private val nextId = AtomicInt(1)

private fun mrKey(project: String, mrId: String): String = "$project/$mrId"

private fun storeComment(commentId: String, comment: StoredComment) {
    while (true) {
        val current = commentStore.value
        if (commentStore.compareAndSet(current, current + (commentId to comment))) return
    }
}

/**
 * Post a new comment on a merge request.
 *
 * Returns a null-terminated UTF-8 string containing the comment ID. The host
 * is responsible for calling karandash_free_string on the returned pointer.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param body Null-terminated UTF-8 string containing the comment body.
 * @return Null-terminated UTF-8 string with the new comment ID.
 */
@CName("karandash_post_comment")
fun postComment(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    body: CPointer<ByteVar>
): CPointer<ByteVar> {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val bodyStr = body.toKStringFromUtf8()

    val commentId = nextId.getAndIncrement().toString()
    storeComment(commentId, StoredComment(mrKey(project, id), bodyStr))
    logToStderr("KotlinNativeCommentPoster: posted comment $commentId on MR $id in project $project")
    return commentId.toCCommentString()
}

/**
 * Update an existing comment on a merge request.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param commentId Null-terminated UTF-8 string with the comment identifier to update.
 * @param body Null-terminated UTF-8 string containing the new comment body.
 * @return 1 on success, 0 on failure.
 */
@CName("karandash_update_comment")
fun updateComment(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    commentId: CPointer<ByteVar>,
    body: CPointer<ByteVar>
): Int {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val cid = commentId.toKStringFromUtf8()
    val bodyStr = body.toKStringFromUtf8()

    storeComment(cid, StoredComment(mrKey(project, id), bodyStr))
    logToStderr("KotlinNativeCommentPoster: updated comment $cid on MR $id in project $project")
    return 1
}

/**
 * Find an existing comment containing a marker string.
 *
 * Returns a null-terminated UTF-8 string with the comment ID if found, or null if
 * no comment contains the marker. The host is responsible for calling
 * karandash_free_string on a non-null returned pointer.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param marker Null-terminated UTF-8 string to search for within comment bodies.
 * @return Null-terminated comment ID string, or null if not found.
 */
@CName("karandash_find_existing_comment")
fun findExistingComment(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    marker: CPointer<ByteVar>
): CPointer<ByteVar>? {
    val mrKey = mrKey(projectId.toKStringFromUtf8(), mrId.toKStringFromUtf8())
    val markerStr = marker.toKStringFromUtf8()
    val found = commentStore.value.entries.firstOrNull { (_, c) -> c.mrKey == mrKey && c.body.contains(markerStr) }
    return found?.key?.toCCommentString()
}

/**
 * Post an inline comment on a specific file and line within a merge request diff.
 *
 * Returns a null-terminated UTF-8 string containing the new comment ID. The host
 * is responsible for calling karandash_free_string on the returned pointer.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @param body Null-terminated UTF-8 string containing the comment body.
 * @param filePath Null-terminated UTF-8 string with the file path relative to the repo root.
 * @param line Line number in the new version of the file.
 * @param diffRefsJson Null-terminated UTF-8 JSON object with baseSha/headSha for diff positioning.
 * @return Null-terminated UTF-8 string with the new comment ID.
 */
@CName("karandash_post_inline_comment")
fun postInlineComment(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>,
    body: CPointer<ByteVar>,
    filePath: CPointer<ByteVar>,
    line: Int,
    diffRefsJson: CPointer<ByteVar>
): CPointer<ByteVar> {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()
    val bodyStr = body.toKStringFromUtf8()
    val file = filePath.toKStringFromUtf8()
    val diffRefs = diffRefsJson.toKStringFromUtf8()

    val commentId = nextId.getAndIncrement().toString()
    storeComment(commentId, StoredComment(mrKey(project, id), bodyStr))
    logToStderr("KotlinNativeCommentPoster: posted inline comment $commentId on $file:$line in MR $id in project $project (diff refs: $diffRefs)")
    return commentId.toCCommentString()
}
