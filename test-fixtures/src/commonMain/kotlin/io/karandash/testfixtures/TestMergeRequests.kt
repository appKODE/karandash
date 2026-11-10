package io.karandash.testfixtures

import io.karandash.models.Approval
import io.karandash.models.Commit
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User

/**
 * Shared test factory for [MergeRequest] instances.
 *
 * All parameters have sensible defaults. Override only what is relevant to the test; set `id`, `iid`,
 * `author` or `state` with [MergeRequest.copy].
 *
 * For testing plugin modules that require a [io.karandash.api.PluginContext], construct
 * a test double inline using an anonymous object or a Mokkery mock - PluginContext is an
 * interface with no concrete test implementation provided here because the required
 * `env()`, `hostVersion`, and `workingDirectory` members are environment-specific.
 */
fun createTestMergeRequest(
    title: String = "Default title",
    description: String? = null,
    sourceBranch: String = "feature/test",
    targetBranch: String = "main",
    draft: Boolean = false,
    labels: List<String> = emptyList(),
    commits: List<Commit> = emptyList(),
    changes: List<FileChange> = emptyList(),
    approvals: List<Approval> = emptyList(),
    reviewers: List<User> = emptyList(),
): MergeRequest =
    MergeRequest(
        id = "1",
        iid = 1,
        title = title,
        description = description,
        sourceBranch = sourceBranch,
        targetBranch = targetBranch,
        author = User(username = "testuser", name = "Test User"),
        state = MergeRequestState.OPENED,
        draft = draft,
        labels = labels,
        commits = commits,
        changes = changes,
        approvals = approvals,
        reviewers = reviewers,
    )
