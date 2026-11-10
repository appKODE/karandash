@file:OptIn(kotlinx.cinterop.ExperimentalForeignApi::class)

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import platform.posix.fprintf
import platform.posix.stderr

/**
 * The one Json instance shared by every C-ABI export in this plugin. A C-ABI plugin links no karandash
 * library, so `KarandashJson` is not available; keep a single configured instance instead of one per file.
 */
// standalone-plugin: links no karandash library, so KarandashJson is unavailable
internal val pluginJson = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
}

/**
 * Writes a diagnostic line to stderr. A C-ABI plugin has no Logger channel, and stdout may carry the
 * host's report (`--format json`), so diagnostics never go to stdout.
 */
internal fun logToStderr(message: String) {
    fprintf(stderr, "%s\n", message)
}

@Serializable
data class MergeRequest(
    val id: String,
    val iid: Int,
    val title: String,
    val description: String? = null,
    @SerialName("source_branch") val sourceBranch: String,
    @SerialName("target_branch") val targetBranch: String,
    val author: User,
    val state: String,
    val draft: Boolean,
    val labels: List<String> = emptyList(),
    val commits: List<Commit> = emptyList(),
    val changes: List<FileChange> = emptyList(),
    val approvals: List<Approval> = emptyList(),
    val reviewers: List<User> = emptyList()
)

@Serializable
data class User(
    val username: String,
    val name: String,
    val email: String? = null
)

@Serializable
data class Commit(
    val sha: String,
    val message: String,
    val author: CommitAuthor,
    val timestamp: String
)

@Serializable
data class CommitAuthor(
    val name: String,
    val email: String
)

@Serializable
data class FileChange(
    val path: String,
    val changeType: String,
    val additions: Int = 0,
    val deletions: Int = 0
)

@Serializable
data class Approval(
    val user: User,
    @SerialName("approved_at") val approvedAt: String? = null
)

@Serializable
data class Finding(
    val severity: String,
    val message: String,
    val rule: String,
    val location: Location? = null,
    val explanation: String? = null,
    val suggestion: String? = null,
    val fixSuggestions: List<Map<String, String>> = emptyList(),
)

@Serializable
data class Location(
    val file: String? = null,
    val line: Int? = null
)
