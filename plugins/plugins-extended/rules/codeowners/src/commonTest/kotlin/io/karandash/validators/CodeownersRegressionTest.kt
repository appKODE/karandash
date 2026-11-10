package io.karandash.validators

import io.karandash.api.ModuleRegistryImpl
import io.karandash.api.exception.ProviderApiException
import io.karandash.api.run.RepositoryFileReader
import io.karandash.api.run.RunServiceKeys
import io.karandash.models.Approval
import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.User
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.io.IOException
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CodeownersRegressionTest {
    private fun mr(
        vararg paths: String,
        approvers: List<String> = emptyList(),
    ) = createTestMergeRequest(
        changes = paths.map { FileChange(path = it, changeType = ChangeType.MODIFIED) },
        approvals = approvers.map { Approval(user = User(username = it, name = it)) },
    )

    private fun validator(content: String) = CodeownersValidator(CodeownersConfig()) { content }

    @Test
    fun `any one owner of the matching rule is enough`() {
        val findings = validator("* @alice @bob").validate(mr("a.kt", approvers = listOf("bob")))
        assertTrue(findings.isEmpty(), findings.toString())
    }

    @Test
    fun `each changed file needs its own owner`() {
        val findings = validator("*.kt @alice\n*.md @bob").validate(mr("a.kt", "b.md", approvers = listOf("alice")))
        val message = findings.single().message
        assertTrue(message.contains("@bob") && !message.contains("@alice"), message)
    }

    @Test
    fun `escaped hash is part of the pattern and is not a comment`() {
        val findings = validator("docs/a\\#b.md @alice # real comment").validate(mr("docs/a#b.md"))
        assertTrue(findings.single().message.contains("@alice"), findings.toString())
    }

    @Test
    fun `codeowners is read from the target branch when the platform can`() =
        runTest {
            val asked = mutableListOf<Pair<String, String>>()
            val session = ModuleRegistryImpl().forRun()
            session.provide(
                RunServiceKeys.REPOSITORY_FILE,
                RepositoryFileReader { ref, path ->
                    asked += ref to path
                    if (path == "CODEOWNERS") "* @alice" else null
                },
            )
            val module = CodeownersRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val request = mr("a.kt").copy(targetBranch = "release")
            val findings = withContext(session.services) {
                module.validate(CodeownersRuleModule.RULE_ID, request, JsonObject(emptyMap()))
            }
            session.close()
            assertTrue(asked.all { it.first == "release" }, asked.toString())
            assertEquals(1, findings.size, findings.toString())
        }

    private suspend fun runWithReader(
        config: JsonObject,
        reader: RepositoryFileReader,
    ): List<io.karandash.models.finding.Finding> {
        val session = ModuleRegistryImpl().forRun()
        session.provide(RunServiceKeys.REPOSITORY_FILE, reader)
        val module = CodeownersRuleModule()
        assertTrue(module.initialize(TestPluginContext()))
        val findings = withContext(session.services) {
            module.validate(CodeownersRuleModule.RULE_ID, mr("a.kt"), config)
        }
        session.close()
        return findings
    }

    @Test
    fun `owner approval off does not fetch codeowners from the platform`() =
        runTest {
            var calls = 0
            val config = JsonObject(mapOf("requireOwnerApproval" to JsonPrimitive(false)))
            val findings = runWithReader(config) { _, _ ->
                calls++
                "* @alice"
            }
            assertTrue(findings.isEmpty(), findings.toString())
            assertEquals(0, calls)
        }

    @Test
    fun `a failing platform read gives an unchecked warning instead of throwing`() =
        runTest {
            val findings = runWithReader(JsonObject(emptyMap())) { _, _ ->
                throw ProviderApiException(500, "Server Error", "GitLab", "GET files", "boom")
            }
            assertEquals(Severity.WARNING, findings.single().severity)
            assertTrue(findings.single().message.startsWith("Code owner approval was not checked"), findings.toString())
        }

    @Test
    fun `an IO failure reading codeowners gives an unchecked warning instead of throwing`() =
        runTest {
            val findings = runWithReader(JsonObject(emptyMap())) { _, _ -> throw IOException("disk gone") }
            assertTrue(findings.single().message.contains("disk gone"), findings.toString())
        }

    @Test
    fun `validation without a run reads the local codeowners file through the typed path`() =
        runTest {
            val module = CodeownersRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val findings = module.validate(
                CodeownersRuleModule.RULE_ID,
                mr("a.kt"),
                JsonObject(mapOf("path" to JsonPrimitive("/no/such/CODEOWNERS")))
            )
            assertTrue(findings.single().message.contains("/no/such/CODEOWNERS"), findings.toString())
        }

    @Test
    fun `rule explanation is served for its own rule id only`() =
        runTest {
            val module = CodeownersRuleModule()
            assertTrue(module.ruleExplanation(CodeownersRuleModule.RULE_ID).orEmpty().contains("CODEOWNERS"))
            assertNull(module.ruleExplanation("other"))
        }
}
