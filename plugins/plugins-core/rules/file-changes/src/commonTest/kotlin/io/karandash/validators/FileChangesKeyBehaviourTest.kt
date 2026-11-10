package io.karandash.validators

import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.User
import io.karandash.sdk.config.BlockMode
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FileChangesKeyBehaviourTest {
    private fun change(path: String) = FileChange(path, ChangeType.MODIFIED)

    private fun messages(
        config: FileChangesConfig,
        paths: List<String> = listOf("src/App.kt"),
        draft: Boolean = false,
        labels: List<String> = emptyList(),
        approvers: List<String> = emptyList(),
        gaps: JsonObject? = null,
    ): List<String> =
        FileChangesValidator(config)
            .validate(
                createTestMergeRequest(
                    draft = draft,
                    labels = labels,
                    changes = paths.map(::change),
                    approvals = approvers.map { Approval(User(username = it, name = it)) },
                ),
                gaps,
            ).map { it.message }

    @Test
    fun `patterns flags only files that match the pattern`() {
        val config = FileChangesConfig(patterns = listOf(FilePatternRule("**/*.pem", requireLabels = listOf("sec"))))
        assertEquals(emptyList(), messages(config, paths = listOf("src/App.kt")))
        assertEquals(1, messages(config, paths = listOf("keys/a.pem")).size)
    }

    @Test
    fun `patterns pattern selects the files a rule applies to`() {
        val config = FileChangesConfig(patterns = listOf(FilePatternRule("docs/**", requireLabels = listOf("docs"))))
        assertTrue(messages(config, paths = listOf("docs/a.md")).single().contains("docs/a.md"))
    }

    @Test
    fun `patterns requireLabels flags a matching file without the label`() {
        val config = FileChangesConfig(patterns = listOf(FilePatternRule("**/*.pem", requireLabels = listOf("sec"))))
        assertEquals(1, messages(config, paths = listOf("a.pem")).size)
        assertEquals(emptyList(), messages(config, paths = listOf("a.pem"), labels = listOf("sec")))
    }

    @Test
    fun `patterns requireApproval flags a matching file until a listed user approves`() {
        val config = FileChangesConfig(patterns = listOf(FilePatternRule("**/*.pem", requireApproval = listOf("alice"))))
        assertEquals(1, messages(config, paths = listOf("a.pem")).size)
        assertEquals(emptyList(), messages(config, paths = listOf("a.pem"), approvers = listOf("alice")))
    }

    @Test
    fun `patterns requireNonAuthorApproval flags a matching file without a second pair of eyes`() {
        val config = FileChangesConfig(patterns = listOf(FilePatternRule("**/*.pem", requireNonAuthorApproval = true)))
        assertEquals(1, messages(config, paths = listOf("a.pem")).size)
        assertEquals(emptyList(), messages(config, paths = listOf("a.pem"), approvers = listOf("alice")))
    }

    @Test
    fun `patterns blockOnDraft blocks a matching file in a draft MR`() {
        val config = FileChangesConfig(patterns = listOf(FilePatternRule("**/*.pem", blockOnDraft = BlockMode.FAIL)))
        assertEquals(1, messages(config, paths = listOf("a.pem"), draft = true).size)
        assertEquals(emptyList(), messages(config, paths = listOf("a.pem"), draft = false))
    }

    @Test
    fun `blockOnDraft blocks every draft MR`() {
        assertEquals(1, messages(FileChangesConfig(blockOnDraft = BlockMode.FAIL), draft = true).size)
        assertEquals(emptyList(), messages(FileChangesConfig(blockOnDraft = BlockMode.OFF), draft = true))
    }

    @Test
    fun `requireLabels flags an MR without the label`() {
        val config = FileChangesConfig(requireLabels = listOf("reviewed"))
        assertTrue(messages(config).single().contains("requires labels"))
        assertEquals(emptyList(), messages(config, labels = listOf("reviewed")))
    }

    @Test
    fun `requireApproval flags an MR until a listed user approves`() {
        val config = FileChangesConfig(requireApproval = listOf("alice"))
        assertEquals(1, messages(config).size)
        assertEquals(emptyList(), messages(config, approvers = listOf("alice")))
    }

    @Test
    fun `blockedPaths flags a changed file that matches`() {
        val config = FileChangesConfig(blockedPaths = listOf("**/*.env"))
        assertTrue(messages(config, paths = listOf("a.env")).single().contains("blocked"))
        assertEquals(emptyList(), messages(config))
    }

    @Test
    fun `requiredPaths flags an MR that changes no matching file`() {
        val config = FileChangesConfig(requiredPaths = listOf("CHANGELOG.md"))
        assertTrue(messages(config).single().contains("CHANGELOG.md"))
        assertEquals(emptyList(), messages(config, paths = listOf("CHANGELOG.md")))
    }

    @Test
    fun `requireCoverage warns about uncovered changed lines`() {
        val gaps = buildJsonObject {
            put(
                "src/App.kt",
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put("startLine", 3)
                            put("endLine", 5)
                        },
                    )
                },
            )
        }
        assertTrue(messages(FileChangesConfig(requireCoverage = true), gaps = gaps).single().contains("3-5"))
        assertEquals(emptyList(), messages(FileChangesConfig(requireCoverage = false), gaps = gaps))
    }
}
