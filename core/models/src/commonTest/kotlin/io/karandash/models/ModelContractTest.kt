package io.karandash.models

import io.karandash.models.finding.Attachment
import io.karandash.models.finding.Finding
import io.karandash.models.finding.FindingCategory
import io.karandash.models.finding.FixSuggestion
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.models.support.KarandashJson
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Value contract for the wire models: every variant survives a JSON round trip with and
 * without encoded defaults, and differs from the base in equality.
 */
class ModelContractTest {
    private fun <T : Any> assertContract(
        serializer: KSerializer<T>,
        base: T,
        variants: List<T>,
    ) {
        (listOf(base) + variants).forEach { value ->
            listOf(KarandashJson.Plain, KarandashJson.Default, KarandashJson.Strict).forEach { json ->
                assertEquals(value, json.decodeFromString(serializer, json.encodeToString(serializer, value)))
            }
            assertEquals(value, value)
            assertNotEquals<Any?>(value, "other")
            assertNotEquals<Any?>(value, null)
            assertTrue(value.toString().startsWith(value::class.simpleName.orEmpty()), value.toString())
        }
        variants.forEach { variant ->
            assertNotEquals(base, variant)
            assertNotEquals(variant, base)
        }
        assertEquals(variants.size, variants.toSet().size, "variants must be distinct")
    }

    private val author = CommitAuthor("dev", "dev@x.io")
    private val user = User("dev", "Dev")

    @Test
    fun `finding fields each take part in equality and serialization`() {
        val base = Finding(Severity.ERROR, "m", "r")
        assertContract(
            Finding.serializer(),
            base,
            listOf(
                base.copy(severity = Severity.INFO),
                base.copy(message = "m2"),
                base.copy(rule = "r2"),
                base.copy(location = Location.FileRef("a.kt", 1, 2)),
                base.copy(explanation = "e"),
                base.copy(suggestion = "s"),
                base.copy(metadata = mapOf("k" to "v")),
                base.copy(fixSuggestions = listOf(FixSuggestion.AddLabel("l"))),
                base.copy(attachments = listOf(Attachment("f.md", "c"))),
                base.copy(fingerprint = "fp"),
                base.copy(priority = 1),
                base.copy(confidence = 0.5f),
                base.copy(relatedFindings = listOf("x")),
                base.copy(category = FindingCategory.SECURITY),
                base.copy(errorCode = "E1"),
            ),
        )
    }

    @Test
    fun `fix suggestions and locations round trip polymorphically`() {
        val suggestions = listOf(
            FixSuggestion.AddLabel("a"),
            FixSuggestion.RemoveLabel("a"),
            FixSuggestion.SetDraft(true),
            FixSuggestion.SetTitle("t"),
            FixSuggestion.SetDescription("d"),
            FixSuggestion.Custom("type"),
            FixSuggestion.Custom("type", JsonObject(mapOf("k" to JsonPrimitive(1))), "desc"),
        )
        assertContract(FixSuggestion.serializer(), FixSuggestion.SetDraft(false), suggestions)
        assertContract(
            Location.serializer(),
            Location.FileRef("a.kt"),
            listOf(Location.FileRef("b.kt"), Location.FileRef("a.kt", 1), Location.FileRef("a.kt", 1, 3), Location.CommitRef("abc")),
        )
        val attachment = Attachment("f", "c")
        assertContract(
            Attachment.serializer(),
            attachment,
            listOf(attachment.copy(filename = "g"), attachment.copy(content = "d"), attachment.copy(contentType = "text/plain")),
        )
    }

    @Test
    fun `custom fix suggestion keeps its own type next to the discriminator`() {
        val finding = Finding(Severity.INFO, "m", "r", fixSuggestions = listOf(FixSuggestion.Custom("request-review")))

        val json = KarandashJson.Default.encodeToString(Finding.serializer(), finding)

        assertTrue(""""type":"custom"""" in json, json)
        assertTrue(""""fixType":"request-review"""" in json, json)
        assertEquals(finding, KarandashJson.Default.decodeFromString(Finding.serializer(), json))
    }

    @Test
    fun `merge request fields each take part in equality and serialization`() {
        val base = MergeRequest(
            id = "1",
            iid = 1,
            title = "t",
            description = null,
            sourceBranch = "f",
            targetBranch = "main",
            author = user,
            state = MergeRequestState.OPENED,
            draft = false,
            labels = emptyList(),
            commits = emptyList(),
            changes = emptyList(),
        )
        assertContract(
            MergeRequest.serializer(),
            base,
            listOf(
                base.copy(id = "2"),
                base.copy(iid = 2),
                base.copy(title = "t2"),
                base.copy(description = "d"),
                base.copy(sourceBranch = "g"),
                base.copy(targetBranch = "dev"),
                base.copy(author = User("x", "X", "x@x.io")),
                base.copy(state = MergeRequestState.MERGED),
                base.copy(draft = true),
                base.copy(labels = listOf("l")),
                base.copy(commits = listOf(Commit("sha", "msg", author, "2026-01-01"))),
                base.copy(changes = listOf(FileChange("a.kt", ChangeType.ADDED))),
                base.copy(approvals = listOf(Approval(user))),
                base.copy(reviewers = listOf(user)),
                base.copy(mergeStatus = "can_be_merged"),
                base.copy(hasConflicts = true),
                base.copy(ciStatus = "success"),
                base.copy(ciJobs = listOf(CiJobStatus("build", "success"))),
                base.copy(diffRefs = DiffRefs("b", "h")),
            ),
        )
    }

    @Test
    fun `nested merge request parts take part in equality and serialization`() {
        val approval = Approval(user)
        assertContract(
            Approval.serializer(),
            approval,
            listOf(
                approval.copy(user = User("o", "O")),
                approval.copy(approvedAt = "now"),
                approval.copy(state = ReviewState.COMMENTED),
                approval.copy(method = ApprovalMethod.EMOJI_REACTION),
            ),
        )
        val refs = DiffRefs("b", "h")
        assertContract(DiffRefs.serializer(), refs, listOf(refs.copy(baseSha = "c"), refs.copy(headSha = "i"), refs.copy(startSha = "s")))
        val job = CiJobStatus("build", "ok")
        assertContract(CiJobStatus.serializer(), job, listOf(job.copy(name = "test"), job.copy(status = "failed")))
        assertContract(User.serializer(), user, listOf(user.copy(username = "u"), user.copy(name = "N"), user.copy(email = "e")))
        val commit = Commit("sha", "msg", author, "ts")
        assertContract(
            Commit.serializer(),
            commit,
            listOf(
                commit.copy(sha = "s2"),
                commit.copy(message = "m2"),
                commit.copy(author = CommitAuthor("o", "o@x")),
                commit.copy(timestamp = "t2"),
            ),
        )
        assertContract(CommitAuthor.serializer(), author, listOf(author.copy(name = "n"), author.copy(email = "e")))
    }

    @Test
    fun `file changes and blame take part in equality and serialization`() {
        val blameCommit = BlameCommit("sha", author, "msg", "ts")
        val blame = BlameEntry(1, 2, blameCommit)
        val base = FileChange("a.kt", ChangeType.MODIFIED)
        assertContract(
            FileChange.serializer(),
            base,
            listOf(
                base.copy(path = "b.kt"),
                base.copy(changeType = ChangeType.RENAMED),
                base.copy(additions = 1),
                base.copy(deletions = 1),
                base.copy(diffContent = "@@"),
                base.copy(newContent = "x"),
                base.copy(blameData = listOf(blame)),
                base.copy(oldPath = "old.kt"),
            ),
        )
        assertContract(BlameEntry.serializer(), blame, listOf(blame.copy(startLine = 0), blame.copy(endLine = 9)))
    }

    @Test
    fun `active policy label falls back to the condition`() {
        val base = ActivePolicy(
            condition = "labels contains x",
            matched = true,
            affectedModules = null,
            forcedEnabled = null,
            forcedSeverity = null,
            configOverrides = null,
        )

        assertEquals("labels contains x", base.displayLabel)
        assertEquals("strict", base.copy(name = "strict").displayLabel)
        assertContract(
            ActivePolicy.serializer(),
            base,
            listOf(
                base.copy(name = "n"),
                base.copy(condition = "c"),
                base.copy(matched = false),
                base.copy(affectedModules = listOf("m")),
                base.copy(forcedEnabled = true),
                base.copy(forcedSeverity = "error"),
                base.copy(configOverrides = JsonObject(emptyMap())),
                base.copy(stageOverrides = mapOf("ai" to false)),
            ),
        )
    }

    @Test
    fun `merge request state normalizes platform statuses`() {
        assertEquals(MergeRequestState.OPENED, MergeRequestState.fromString("OPEN"))
        assertEquals(MergeRequestState.LOCKED, MergeRequestState.fromString("Locked"))
        assertEquals(MergeRequestState.UNKNOWN, MergeRequestState.fromString("weird"))
        val cases = mapOf(
            ("GitLab" to "locked") to MergeRequestState.LOCKED,
            ("bitbucket" to "DECLINED") to MergeRequestState.CLOSED,
            ("azure" to "completed") to MergeRequestState.MERGED,
            ("azure-devops" to "completed") to MergeRequestState.MERGED, // the real platform id
            ("azure-devops" to "abandoned") to MergeRequestState.CLOSED,
            ("gerrit" to "new") to MergeRequestState.OPENED,
            ("github" to "locked") to MergeRequestState.UNKNOWN,
            ("custom" to "merged") to MergeRequestState.MERGED,
            ("custom" to "open") to MergeRequestState.OPENED,
        )
        cases.forEach { (input, expected) ->
            assertEquals(expected, MergeRequestState.fromPlatformStatus(input.first, input.second), input.toString())
        }
    }
}
