package io.karandash.models

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.CodeChange
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsRoundTripTest {
    private val json = KarandashJson.Default
    private val user = User("alice", "Alice", "a@x.io")
    private val author = CommitAuthor("Alice", "a@x.io")

    @Test
    fun `merge request with every field survives a JSON round trip`() {
        val blame = BlameEntry(1, 3, BlameCommit("abc", author, "msg", "2024-01-01T00:00:00Z"))
        val mr = MergeRequest(
            id = "1",
            iid = 7,
            title = "t",
            description = null,
            sourceBranch = "feat",
            targetBranch = "main",
            author = user,
            state = MergeRequestState.OPENED,
            draft = true,
            labels = listOf("a"),
            commits = listOf(Commit("abc", "m", author, "2024-01-01T00:00:00Z")),
            changes = listOf(FileChange("a.kt", ChangeType.RENAMED, 1, 2, "d", "n", listOf(blame), "old.kt")),
            approvals = listOf(Approval(user, "2024", ReviewState.APPROVED, ApprovalMethod.EMOJI_REACTION)),
            reviewers = listOf(user),
            mergeStatus = "can_be_merged",
            hasConflicts = false,
            ciStatus = "success",
            ciJobs = listOf(CiJobStatus("build", "passed")),
            diffRefs = DiffRefs("b", "h", "s"),
        )
        val text = json.encodeToString(MergeRequest.serializer(), mr)
        assertEquals(mr, json.decodeFromString(MergeRequest.serializer(), text))
        assertTrue(text.contains("\"source_branch\""))
        assertTrue(text.contains("\"base_sha\""))
    }

    @Test
    fun `active policy label falls back to the condition`() {
        val config = buildJsonObject { put("k", 1) }
        val unnamed = ActivePolicy(null, "branch == 'main'", true, listOf("m"), true, "ERROR", config, mapOf("autoApprove" to false))
        assertEquals("branch == 'main'", unnamed.displayLabel)
        assertEquals("p", unnamed.copy(name = "p").displayLabel)
        assertEquals(unnamed, json.decodeFromString(ActivePolicy.serializer(), json.encodeToString(ActivePolicy.serializer(), unnamed)))
        assertNull(ActivePolicy("c", "c", false, null, null, null, null).configOverrides)
    }

    @Test
    fun `approval signals match keywords and emojis loosely and ignore code review`() {
        fun signal(
            method: ApprovalMethod,
            text: String,
        ) = ApprovalSignal(user, method, text, "2024")
        assertEquals(ApprovalMethod.COMMENT_KEYWORD, signal(ApprovalMethod.COMMENT_KEYWORD, " lgtm! ").toApproval()?.method)
        assertEquals("2024", signal(ApprovalMethod.COMMENT_KEYWORD, "Approved.").toApproval()?.approvedAt)
        assertNull(signal(ApprovalMethod.COMMENT_KEYWORD, "looks bad").toApproval())
        assertEquals(ApprovalMethod.EMOJI_REACTION, signal(ApprovalMethod.EMOJI_REACTION, ":thumbsup:").toApproval()?.method)
        assertNull(signal(ApprovalMethod.EMOJI_REACTION, "eyes").toApproval())
        assertNull(signal(ApprovalMethod.CODE_REVIEW, "LGTM").toApproval())
        assertEquals(
            ApprovalMethod.EMOJI_REACTION,
            signal(ApprovalMethod.EMOJI_REACTION, "rocket").toApproval(emojis = listOf("ROCKET"))?.method,
        )
        val sent = signal(ApprovalMethod.COMMENT_KEYWORD, "x")
        assertEquals(sent, json.decodeFromString(ApprovalSignal.serializer(), json.encodeToString(ApprovalSignal.serializer(), sent)))
    }

    @Test
    fun `finding with every part and every fix suggestion round trips`() {
        val finding = Finding(
            severity = Severity.WARNING,
            message = "m",
            rule = "r",
            location = Location.FileRef("a.kt", 1, 2),
            explanation = "e",
            suggestion = "s",
            metadata = mapOf("k" to "v"),
            fixSuggestions = listOf(
                FixSuggestion.AddLabel("l"),
                FixSuggestion.RemoveLabel("l"),
                FixSuggestion.SetDraft(true),
                FixSuggestion.SetTitle("t"),
                FixSuggestion.SetDescription("d"),
                FixSuggestion.Custom("upd", JsonPrimitive(1), "desc"),
            ),
            attachments = listOf(Attachment("a.md", "c")),
            fingerprint = "fp",
            confidence = 0.5f,
            relatedFindings = listOf("x"),
            errorCode = "E1",
        )
        assertEquals(finding, json.decodeFromString(Finding.serializer(), json.encodeToString(Finding.serializer(), finding)))
        val commit = finding.copy(location = Location.CommitRef("abc"))
        assertEquals(commit, json.decodeFromString(Finding.serializer(), json.encodeToString(Finding.serializer(), commit)))
        assertEquals("text/markdown", Attachment("f", "c").contentType)
        val change = CodeChange("a", 1, 2, "x", "y")
        assertEquals(change, json.decodeFromString(CodeChange.serializer(), json.encodeToString(CodeChange.serializer(), change)))
    }

    @Test
    fun `file change copy keeps the old path`() {
        val change = FileChange("n.kt", ChangeType.RENAMED, oldPath = "o.kt")
        assertEquals("o.kt", change.copy(additions = 3).oldPath)
        assertEquals(3, change.copy(additions = 3).additions)
    }
}
