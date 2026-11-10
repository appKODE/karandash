package com.example.karandash

import io.karandash.api.ModuleType
import io.karandash.api.PluginContext
import io.karandash.api.module.PlatformServiceModule
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.DiffRefs
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User

/**
 * Standalone example Karandash provider plugin.
 *
 * Demonstrates implementing [PlatformServiceModule] with provider capabilities to fetch
 * merge request data from a custom or internal platform. This stub returns a hardcoded
 * [MergeRequest] for illustration purposes; a real implementation would make HTTP calls
 * to the target platform's API.
 *
 * Mutator and comment-poster methods throw [UnsupportedOperationException] to indicate
 * this implementation only supports the provider role.
 *
 * Build: ./gradlew build
 * Install: cp build/libs/standalone-jvm-plugin.jar ~/.karandash/plugins/
 */
class ExampleProviderPlugin : PlatformServiceModule {
    override val id: String = "example-jvm-provider"
    override val name: String = "Example JVM Provider Plugin"
    override val version: String = "1.0.0"
    override val type: ModuleType get() = ModuleType.PLATFORM_SERVICE

    override suspend fun initialize(context: PluginContext): Boolean = true

    override suspend fun fetchMergeRequest(projectId: String, mrId: String): MergeRequest {
        return buildExampleMergeRequest(projectId, mrId)
    }

    override suspend fun fetchMergeRequestByUrl(url: String): MergeRequest {
        val parts = url.trimEnd('/').split('/')
        val mrId = parts.lastOrNull() ?: "1"
        val projectId = if (parts.size >= 2) "${parts[parts.size - 3]}/${parts[parts.size - 2]}" else "example/project"
        return buildExampleMergeRequest(projectId, mrId)
    }

    private fun buildExampleMergeRequest(projectId: String, mrId: String): MergeRequest {
        val author = User(username = "example-user", name = "Example User", email = "user@example.com")
        return MergeRequest(
            id = mrId,
            iid = mrId.toIntOrNull() ?: 1,
            title = "feat: example merge request from $projectId",
            description = "This is an example MR fetched by the standalone JVM provider plugin.",
            sourceBranch = "feature/example-branch",
            targetBranch = "main",
            author = author,
            state = MergeRequestState.OPENED,
            draft = false,
            labels = listOf("example", "plugin"),
            commits = listOf(
                Commit(
                    sha = "abc1234",
                    message = "feat: add example implementation",
                    author = CommitAuthor(name = author.name, email = author.email ?: ""),
                    timestamp = "2024-01-01T00:00:00Z"
                )
            ),
            changes = listOf(
                FileChange(
                    path = "src/Example.kt",
                    changeType = ChangeType.ADDED,
                    additions = 42,
                    deletions = 0
                )
            ),
            approvals = emptyList<Approval>(),
            reviewers = emptyList()
        )
    }

    // --- Mutator methods (not supported by this provider-only implementation) ---

    override suspend fun addLabels(projectId: String, mrId: String, labels: List<String>): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support addLabels")

    override suspend fun removeLabels(projectId: String, mrId: String, labels: List<String>): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support removeLabels")

    override suspend fun setDraft(projectId: String, mrId: String, draft: Boolean): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support setDraft")

    override suspend fun updateTitle(projectId: String, mrId: String, title: String): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support updateTitle")

    override suspend fun updateDescription(projectId: String, mrId: String, description: String): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support updateDescription")

    override suspend fun approve(projectId: String, mrId: String): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support approve")

    override suspend fun unapprove(projectId: String, mrId: String): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support unapprove")

    // --- Comment poster methods (not supported by this provider-only implementation) ---

    override suspend fun postComment(projectId: String, mrId: String, body: String): String =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support postComment")

    override suspend fun updateComment(projectId: String, mrId: String, commentId: String, body: String): Unit =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support updateComment")

    override suspend fun findExistingComment(projectId: String, mrId: String, marker: String): String? =
        throw UnsupportedOperationException("ExampleProviderPlugin does not support findExistingComment")

    override suspend fun postInlineComment(
        projectId: String,
        mrId: String,
        body: String,
        filePath: String,
        line: Int,
        diffRefs: DiffRefs,
        startLine: Int?,
    ): String = throw UnsupportedOperationException("ExampleProviderPlugin does not support postInlineComment")
}
