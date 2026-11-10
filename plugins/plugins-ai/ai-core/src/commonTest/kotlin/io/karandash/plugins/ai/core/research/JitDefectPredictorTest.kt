package io.karandash.plugins.ai.core.research

import io.karandash.models.ChangeType
import io.karandash.models.Commit
import io.karandash.models.CommitAuthor
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import kotlin.test.Test
import kotlin.test.assertTrue

class JitDefectPredictorTest {
    private fun makeMr(
        changes: List<FileChange> = emptyList(),
        commits: List<Commit> = emptyList(),
        description: String? = null,
    ) = MergeRequest(
        id = "1",
        iid = 1,
        title = "Test",
        description = description,
        sourceBranch = "feature",
        targetBranch = "main",
        author = User(username = "dev", name = "Dev"),
        state = MergeRequestState.OPENED,
        draft = false,
        labels = emptyList(),
        commits = commits,
        changes = changes,
    )

    private fun makeChange(
        path: String,
        additions: Int = 5,
        deletions: Int = 2
    ) = FileChange(
        path = path,
        changeType = ChangeType.MODIFIED,
        additions = additions,
        deletions = deletions,
    )

    private fun makeCommit(message: String = "fix: some meaningful commit message for the change") =
        Commit(
            sha = "abc123",
            message = message,
            author = CommitAuthor("Dev", "dev@test.com"),
            timestamp = "2024-01-01T00:00:00Z",
        )

    @Test
    fun `small clean MR produces LOW risk`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/Foo.kt", additions = 10, deletions = 2)),
            commits = listOf(makeCommit(), makeCommit()),
            description = "This MR fixes a small issue in the Foo class.",
        )
        val result = JitDefectPredictor.predict(mr)
        assertTrue(
            result.riskLevel == DefectRiskLevel.LOW || result.probability < 0.5,
            "Small MR should be LOW risk, got ${result.riskLevel} (${result.probability})",
        )
    }

    @Test
    fun `large MR produces higher risk score than small MR`() {
        val largeMr = makeMr(
            changes = (1..25).map { makeChange("dir$it/File$it.kt", additions = 50, deletions = 40) },
            commits = listOf(makeCommit()),
        )
        val smallMr = makeMr(
            changes = listOf(makeChange("src/Foo.kt", additions = 5, deletions = 1)),
            commits = listOf(makeCommit(), makeCommit()),
            description = "Small targeted fix.",
        )
        val largeResult = JitDefectPredictor.predict(largeMr)
        val smallResult = JitDefectPredictor.predict(smallMr)
        assertTrue(
            largeResult.probability > smallResult.probability,
            "Large MR should score higher: ${largeResult.probability} vs ${smallResult.probability}",
        )
    }

    @Test
    fun `missing description increases risk compared to MR with description`() {
        val withDesc = makeMr(
            changes = listOf(makeChange("src/A.kt")),
            commits = listOf(makeCommit()),
            description = "Detailed description of what changed and why.",
        )
        val noDesc = makeMr(
            changes = listOf(makeChange("src/A.kt")),
            commits = listOf(makeCommit()),
            description = null,
        )
        val withDescResult = JitDefectPredictor.predict(withDesc)
        val noDescResult = JitDefectPredictor.predict(noDesc)
        assertTrue(
            noDescResult.probability >= withDescResult.probability,
            "Missing description should not decrease risk: ${noDescResult.probability} vs ${withDescResult.probability}",
        )
    }

    @Test
    fun `short commit messages increase risk compared to descriptive messages`() {
        val descriptive = makeMr(
            changes = listOf(makeChange("src/A.kt")),
            commits = listOf(makeCommit("fix: correct null pointer in UserService.getById")),
            description = "Fix",
        )
        val vague = makeMr(
            changes = listOf(makeChange("src/A.kt")),
            commits = listOf(makeCommit("fix")),
            description = "Fix",
        )
        val descriptiveResult = JitDefectPredictor.predict(descriptive)
        val vagueResult = JitDefectPredictor.predict(vague)
        assertTrue(
            vagueResult.probability >= descriptiveResult.probability,
            "Short message should produce >= risk: ${vagueResult.probability} vs ${descriptiveResult.probability}",
        )
    }

    @Test
    fun `probability is bounded between 0 and 1`() {
        val extremeMr = makeMr(
            changes = (1..30).map { makeChange("dir$it/F$it.kt", additions = 200, deletions = 200) },
            commits = emptyList(),
            description = null,
        )
        val emptyMr = makeMr()
        val extremeResult = JitDefectPredictor.predict(extremeMr)
        val emptyResult = JitDefectPredictor.predict(emptyMr)
        assertTrue(extremeResult.probability in 0.0..1.0, "Extreme probability out of range: ${extremeResult.probability}")
        assertTrue(emptyResult.probability in 0.0..1.0, "Empty MR probability out of range: ${emptyResult.probability}")
    }

    @Test
    fun `result contains all expected factor keys`() {
        val mr = makeMr(
            changes = listOf(makeChange("src/A.kt")),
            commits = listOf(makeCommit()),
        )
        val result = JitDefectPredictor.predict(mr)
        val expectedKeys = setOf("size", "diffusion", "history", "purpose", "review")
        assertTrue(
            result.factors.keys.containsAll(expectedKeys),
            "Missing factor keys: ${expectedKeys - result.factors.keys}",
        )
    }
}
