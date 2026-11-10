package io.karandash.plugins.ai.core

import io.karandash.logger.Logger
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.MergeRequest
import io.karandash.models.MergeRequestState
import io.karandash.models.User
import io.karandash.models.finding.Finding
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.plugins.ai.core.diagnostics.ContextQualityDiagnostics
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FindingLocationValidatorTest {
    private fun mr(vararg changes: FileChange): MergeRequest =
        MergeRequest(
            id = "1",
            iid = 1,
            title = "test",
            description = null,
            author = User(username = "u", name = "U"),
            sourceBranch = "feat",
            targetBranch = "main",
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            changes = changes.toList(),
            commits = emptyList(),
            approvals = emptyList(),
            reviewers = emptyList(),
        )

    private fun change(
        path: String,
        diff: String? = null
    ): FileChange = FileChange(path = path, changeType = ChangeType.MODIFIED, diffContent = diff)

    private fun finding(
        path: String?,
        line: Int? = null
    ): Finding =
        Finding(
            severity = Severity.WARNING,
            message = "AI says",
            rule = "review-comments",
            location = if (path == null) null else Location.FileRef(path = path, line = line),
        )

    private val basicDiff = """
        @@ -1,3 +1,3 @@
         ctx1
        -old
        +new
    """.trimIndent()

    @Test
    fun `mr-level finding passes unchanged`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val result = FindingLocationValidator.validate(
            listOf(finding(path = null)),
            mrInstance,
            ruleId = "r",
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `commit-level finding passes unchanged`() {
        val commit = Finding(
            severity = Severity.WARNING,
            message = "m",
            rule = "r",
            location = Location.CommitRef(sha = "abc123"),
        )
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val result = FindingLocationValidator.validate(listOf(commit), mrInstance, ruleId = "r")
        assertEquals(1, result.size)
    }

    @Test
    fun `finding referencing unknown file is dropped in strict mode`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val result = FindingLocationValidator.validate(
            listOf(finding(path = "src/Ghost.kt", line = 1)),
            mrInstance,
            ruleId = "r",
            strict = true,
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `finding referencing unknown file is kept in non-strict mode`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val result = FindingLocationValidator.validate(
            listOf(finding(path = "src/Ghost.kt", line = 1)),
            mrInstance,
            ruleId = "r",
            strict = false,
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `finding on added line passes`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        // basicDiff adds line 2.
        val result = FindingLocationValidator.validate(
            listOf(finding("src/Foo.kt", line = 2)),
            mrInstance,
            ruleId = "r",
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `finding on unchanged line is dropped while a finding on the added line survives`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val result = FindingLocationValidator.validate(
            listOf(finding("src/Foo.kt", line = 99), finding("src/Foo.kt", line = 2)),
            mrInstance,
            ruleId = "r",
        )
        assertEquals(listOf(Location.FileRef(path = "src/Foo.kt", line = 2)), result.map { it.location })
    }

    @Test
    fun `finding anchored on a closing brace snaps to the code line above`() {
        val diff = "@@ -1,1 +1,4 @@\n ctx\n+if (x) {\n+    run()\n+}"
        val result = FindingLocationValidator.validate(
            listOf(finding("src/Foo.kt", line = 4)),
            mr(change("src/Foo.kt", diff)),
            ruleId = "r"
        )

        assertEquals(listOf(Location.FileRef(path = "src/Foo.kt", line = 3)), result.map { it.location })
    }

    @Test
    fun `finding on a deleted file is dropped`() {
        val deleted = FileChange(path = "src/Old.kt", changeType = ChangeType.DELETED, diffContent = "@@ -1,1 +0,0 @@\n-gone")
        val result = FindingLocationValidator.validate(listOf(finding("src/Old.kt", line = 1)), mr(deleted), ruleId = "r")

        assertTrue(result.isEmpty())
    }

    @Test
    fun `code suggestion on unchanged line is still dropped in strict mode`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val suggestion = finding("src/Foo.kt", line = 99)
            .copy(metadata = mapOf(AiFindingMetadataKeys.EXISTING_CODE to "old"))
        assertTrue(FindingLocationValidator.validate(listOf(suggestion), mrInstance, ruleId = "r").isEmpty())
    }

    @Test
    fun `a dropped code-suggestion twin is logged under its own rule`() {
        val warnings = mutableListOf<String>()
        val logger = object : Logger {
            override fun debug(message: String) = Unit

            override fun info(message: String) = Unit

            override fun warn(message: String) {
                warnings += message
            }

            override fun error(message: String) = Unit
        }
        val twin = finding("src/Foo.kt", line = 99).copy(rule = AiRuleIds.CODE_SUGGESTIONS)

        FindingLocationValidator.validate(
            listOf(twin),
            mr(change("src/Foo.kt", basicDiff)),
            ruleId = AiRuleIds.REVIEW_COMMENTS,
            diagnostics = ContextQualityDiagnostics(logger),
        )

        assertEquals(1, warnings.size, "$warnings")
        assertTrue("rule=${AiRuleIds.CODE_SUGGESTIONS} " in warnings.single(), warnings.single())
    }

    @Test
    fun `finding on null line passes when file matches`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val result = FindingLocationValidator.validate(
            listOf(finding("src/Foo.kt", line = null)),
            mrInstance,
            ruleId = "r",
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `finding on file with no diff passes - cannot validate`() {
        val mrInstance = mr(change("src/Foo.kt", diff = null))
        val result = FindingLocationValidator.validate(
            listOf(finding("src/Foo.kt", line = 42)),
            mrInstance,
            ruleId = "r",
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `empty findings list returns empty`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        val result = FindingLocationValidator.validate(emptyList(), mrInstance, ruleId = "r")
        assertTrue(result.isEmpty())
    }

    @Test
    fun `multiple hunks - line in second hunk accepted`() {
        val multi = """
            @@ -1,1 +1,1 @@
            +first
            @@ -10,1 +10,1 @@
            +second
        """.trimIndent()
        val mrInstance = mr(change("src/Foo.kt", multi))
        val result = FindingLocationValidator.validate(
            listOf(finding("src/Foo.kt", line = 10)),
            mrInstance,
            ruleId = "r",
        )
        assertEquals(1, result.size)
    }

    @Test
    fun `hunk header itself not treated as added line`() {
        val mrInstance = mr(change("src/Foo.kt", basicDiff))
        // Line 1 is the first added line in basicDiff (no, it's context - +new is line 2)
        // But line 99 definitely is not - verifying hunk-header line number isn't polluted.
        val result = FindingLocationValidator.validate(
            listOf(finding("src/Foo.kt", line = 1)),
            mrInstance,
            ruleId = "r",
        )
        assertTrue(result.isEmpty())
    }

    @Test
    fun `line finding on a renamed file with no hunks is kept`() {
        val renamed = FileChange(
            path = "src/New.kt",
            changeType = ChangeType.RENAMED,
            diffContent = "diff --git a/src/Old.kt b/src/New.kt\nsimilarity index 100%\nrename from src/Old.kt\nrename to src/New.kt",
        )
        val onRenamed = finding(path = "src/New.kt", line = 7)
        val onChanged = finding(path = "src/Foo.kt", line = 40)

        val result = FindingLocationValidator.validate(
            listOf(onRenamed, onChanged),
            mr(renamed, change("src/Foo.kt", basicDiff)),
            ruleId = "r"
        )

        assertEquals(listOf(onRenamed), result)
    }
}
