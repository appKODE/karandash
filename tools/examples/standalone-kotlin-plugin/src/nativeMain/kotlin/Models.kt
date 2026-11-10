import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class MergeRequest(
    val id: String = "",
    val iid: Int = 0,
    val title: String,
    val description: String? = null,
    @SerialName("source_branch") val sourceBranch: String = "",
    @SerialName("target_branch") val targetBranch: String = "",
    val author: User = User("", ""),
    val state: String = "",
    val draft: Boolean = false,
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
