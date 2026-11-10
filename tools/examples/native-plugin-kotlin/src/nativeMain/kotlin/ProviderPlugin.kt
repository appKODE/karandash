@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class, kotlin.experimental.ExperimentalNativeApi::class)

import kotlinx.cinterop.*
import kotlinx.serialization.encodeToString
import platform.posix.strdup

private fun String.toCProviderString(): CPointer<ByteVar> = checkNotNull(strdup(this)) { "strdup failed" }.reinterpret()

/**
 * Fetch a merge request by project ID and MR ID.
 *
 * Returns a JSON-encoded MergeRequest object. The host is responsible for
 * calling karandash_free_string on the returned pointer.
 *
 * @param projectId Null-terminated UTF-8 string with the project identifier.
 * @param mrId Null-terminated UTF-8 string with the merge request identifier.
 * @return JSON-encoded MergeRequest, or a JSON error object on failure.
 */
@CName("karandash_fetch_merge_request")
fun fetchMergeRequest(
    projectId: CPointer<ByteVar>,
    mrId: CPointer<ByteVar>
): CPointer<ByteVar> {
    val project = projectId.toKStringFromUtf8()
    val id = mrId.toKStringFromUtf8()

    val mr = MergeRequest(
        id = id,
        iid = id.toIntOrNull() ?: 1,
        title = "feat: example MR from $project",
        description = "Example merge request fetched by the Kotlin/Native provider plugin.",
        sourceBranch = "feature/example",
        targetBranch = "main",
        author = User(username = "example-user", name = "Example User"),
        state = "opened",
        draft = false,
        labels = listOf("kotlin", "example"),
        commits = emptyList(),
        changes = emptyList(),
        approvals = emptyList(),
        reviewers = emptyList()
    )

    return pluginJson.encodeToString(mr).toCProviderString()
}

/**
 * Fetch a merge request by its full web URL.
 *
 * Returns a JSON-encoded MergeRequest object. The host is responsible for
 * calling karandash_free_string on the returned pointer.
 *
 * @param url Null-terminated UTF-8 string with the full MR URL.
 * @return JSON-encoded MergeRequest, or a JSON error object on failure.
 */
@CName("karandash_fetch_merge_request_by_url")
fun fetchMergeRequestByUrl(url: CPointer<ByteVar>): CPointer<ByteVar> {
    val urlStr = url.toKStringFromUtf8()
    val parts = urlStr.trimEnd('/').split('/')
    val mrId = parts.lastOrNull() ?: "1"
    val projectId = if (parts.size >= 2) "${parts[parts.size - 3]}/${parts[parts.size - 2]}" else "example/project"

    val mr = MergeRequest(
        id = mrId,
        iid = mrId.toIntOrNull() ?: 1,
        title = "feat: example MR from URL",
        description = "Example MR fetched from URL: $urlStr",
        sourceBranch = "feature/example",
        targetBranch = "main",
        author = User(username = "example-user", name = "Example User"),
        state = "opened",
        draft = false,
        labels = listOf("kotlin", "example"),
        commits = emptyList(),
        changes = emptyList(),
        approvals = emptyList(),
        reviewers = emptyList()
    )

    return pluginJson.encodeToString(mr).toCProviderString()
}
