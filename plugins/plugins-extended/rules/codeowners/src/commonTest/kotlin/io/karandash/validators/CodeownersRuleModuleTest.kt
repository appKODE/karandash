package io.karandash.validators

import io.karandash.api.ExperimentalKarandashApi
import io.karandash.api.platform.MergeRequestField
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.ReviewState
import io.karandash.models.User
import io.karandash.testfixtures.TestFiles
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.writeString
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CodeownersRuleModuleTest {
    private fun tempFile(content: String): Path {
        val file = Path(TestFiles.createTestTempDirectory("codeowners"), "CODEOWNERS")
        SystemFileSystem.sink(file).buffered().use { it.writeString(content) }
        return file
    }

    private fun mr(
        vararg paths: String,
        approvers: List<String> = emptyList(),
    ) = createTestMergeRequest(
        changes = paths.map { FileChange(path = it, changeType = ChangeType.MODIFIED) },
        approvals = approvers.map { Approval(user = User(username = it, name = it), state = ReviewState.APPROVED) },
    )

    private fun config(path: String): JsonElement = JsonObject(mapOf("path" to JsonPrimitive(path)))

    @Test
    fun `module reads an absolute codeowners file and reports missing owners`() =
        runTest {
            val file =
                tempFile(
                    """
                    # comment line
                    *.kt @kotlin-team  # trailing comment
                    lonely-pattern
                    docs/ @org/docs-team
                    """.trimIndent(),
                )
            val module = CodeownersRuleModule()
            assertTrue(module.initialize(TestPluginContext()))

            val missing = module.validate(CodeownersRuleModule.RULE_ID, mr("src/A.kt", "docs/x.md"), config(file.toString())).single()
            assertEquals("Missing approval from code owners: @kotlin-team, @org/docs-team", missing.message)

            val approved = mr("src/A.kt", "docs/x.md", approvers = listOf("Kotlin-Team", "docs-team"))
            assertTrue(module.validate(CodeownersRuleModule.RULE_ID, approved, config(file.toString())).isEmpty())
            assertTrue(module.validate(CodeownersRuleModule.RULE_ID, mr("README"), config(file.toString())).isEmpty())
            SystemFileSystem.delete(file)
        }

    @OptIn(ExperimentalKarandashApi::class)
    @Test
    fun `module declares approvals as a required field so platforms without them skip it`() {
        assertEquals(
            setOf(MergeRequestField.APPROVALS),
            CodeownersRuleModule().requiredMergeRequestFields(CodeownersRuleModule.RULE_ID, JsonObject(emptyMap())),
        )
    }

    @Test
    fun `missing codeowners file gives an unchecked warning finding`() =
        runTest {
            val context = TestPluginContext()
            val module = CodeownersRuleModule()
            module.initialize(context)
            val path = Path(TestFiles.createTestTempDirectory("codeowners-missing"), "absent").toString()

            val finding = module.validate(CodeownersRuleModule.RULE_ID, mr("a.kt"), config(path)).single()
            assertEquals("Code owner approval was not checked: no CODEOWNERS file found (looked in $path).", finding.message)
        }

    @Test
    fun `relative path that does not exist gives an unchecked warning`() {
        val validator = CodeownersValidator(CodeownersConfig(path = "no/such/CODEOWNERS-${Random.nextLong().toULong()}"))

        assertEquals(1, validator.validate(mr("a.kt")).size)
    }
}
