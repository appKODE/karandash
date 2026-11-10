@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.User
import io.karandash.models.finding.Location
import io.karandash.models.finding.Severity
import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** fileChanges blockedPaths, requiredPaths, coverage. */
class FileChangesRegressionTest {
    private fun change(path: String) = FileChange(path = path, changeType = ChangeType.MODIFIED, additions = 1)

    private fun approval(username: String) = Approval(user = User(username = username, name = username))

    @Test
    fun `requireApproval is silent when a listed approver approved`() {
        val config = FileChangesConfig(requireApproval = listOf("alice", "bob"))
        val mr = createTestMergeRequest(changes = listOf(change("a.kt")), approvals = listOf(approval("Bob")))
        assertTrue(FileChangesValidator(config).validate(mr).isEmpty())
    }

    @Test
    fun `requireApproval warns when no listed approver approved`() {
        val config = FileChangesConfig(requireApproval = listOf("alice"))
        val mr = createTestMergeRequest(changes = listOf(change("a.kt")), approvals = listOf(approval("carol")))
        assertEquals(1, FileChangesValidator(config).validate(mr).size)
    }

    @Test
    fun `pattern requireApproval honours approvals`() {
        val config = FileChangesConfig(patterns = listOf(FilePatternRule(pattern = "*.kt", requireApproval = listOf("alice"))))
        val mr = createTestMergeRequest(changes = listOf(change("a.kt")), approvals = listOf(approval("alice")))
        assertTrue(FileChangesValidator(config).validate(mr).isEmpty())
    }

    @Test
    fun `module declares approvals and labels only for the keys that read them`() {
        val module = FileChangesRuleModule()

        fun required(json: String) = module.requiredMergeRequestFields(FileChangesRuleModule.RULE_ID, Json.parseToJsonElement(json))

        assertEquals(emptySet(), required("""{"blockedPaths":["secrets/**"]}"""))
        assertEquals(setOf(MergeRequestField.APPROVALS), required("""{"requireApproval":["alice"]}"""))
        assertEquals(setOf(MergeRequestField.APPROVALS), required("""{"patterns":[{"pattern":"*.kt","requireNonAuthorApproval":true}]}"""))
        assertEquals(setOf(MergeRequestField.LABELS), required("""{"patterns":[{"pattern":"*.kt","requireLabels":["qa"]}]}"""))
    }

    @Test
    fun `global and pattern blockOnDraft do not double report`() {
        val config = FileChangesConfig(
            blockOnDraft = BlockMode.FAIL,
            patterns = listOf(FilePatternRule(pattern = "*.kt", blockOnDraft = BlockMode.FAIL)),
        )
        val findings = FileChangesValidator(config).validate(createTestMergeRequest(draft = true, changes = listOf(change("a.kt"))))
        assertEquals(1, findings.size, findings.toString())
    }

    @Test
    fun `blockedPaths flags every matching changed file`() {
        val config = FileChangesConfig(blockedPaths = listOf("**/*.jks", ".env"))
        val mr = createTestMergeRequest(changes = listOf(change("app/key.jks"), change(".env"), change("ok.kt")))
        val findings = FileChangesValidator(config).validate(mr)
        assertEquals(listOf("app/key.jks", ".env"), findings.map { (it.location as? Location.FileRef)?.path })
        assertTrue(findings.all { it.severity == Severity.ERROR })
    }

    @Test
    fun `requiredPaths errors when no changed file matches`() {
        val config = FileChangesConfig(requiredPaths = listOf("CHANGELOG.md"))
        assertEquals(1, FileChangesValidator(config).validate(createTestMergeRequest(changes = listOf(change("a.kt")))).size)
        assertTrue(FileChangesValidator(config).validate(createTestMergeRequest(changes = listOf(change("CHANGELOG.md")))).isEmpty())
    }

    @Test
    fun `requireCoverage warns on uncovered ranges of changed files`() {
        val gaps = JsonObject(
            mapOf(
                "a.kt" to JsonArray(listOf(JsonObject(mapOf("startLine" to JsonPrimitive(3), "endLine" to JsonPrimitive(5))))),
                "other.kt" to JsonArray(listOf(JsonObject(mapOf("startLine" to JsonPrimitive(1), "endLine" to JsonPrimitive(1))))),
            )
        )
        val config = FileChangesConfig(requireCoverage = true)
        val mr = createTestMergeRequest(changes = listOf(change("a.kt")))
        val findings = FileChangesValidator(config).validate(mr, gaps)
        assertEquals(1, findings.size, findings.toString())
        assertEquals(Severity.WARNING, findings[0].severity)
        assertTrue(findings[0].message.contains("3-5"), findings[0].message)
        assertTrue(FileChangesValidator(config).validate(mr, null).isEmpty())
    }
}
