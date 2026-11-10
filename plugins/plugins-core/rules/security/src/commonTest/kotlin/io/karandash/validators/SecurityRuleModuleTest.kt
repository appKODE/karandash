package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecurityRuleModuleTest {
    @Test
    fun `module reads patterns scan limit and severities from json config`() =
        runTest {
            val module = SecurityRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val config =
                JsonObject(
                    mapOf(
                        "scanForSecrets" to JsonPrimitive(true),
                        "secretPatterns" to JsonArray(listOf(JsonPrimitive("TOKEN-[0-9]{4}"))),
                        "sensitiveFiles" to JsonArray(listOf(JsonPrimitive("**/vault.txt"))),
                        "scan" to JsonObject(mapOf("maxDiffSize" to JsonPrimitive("64kb"))),
                        "severities" to
                            JsonObject(mapOf("secretFound" to JsonPrimitive("info"), "largeDiffSkipped" to JsonPrimitive("error"))),
                    ),
                )
            val huge = FileChange(path = "big.txt", changeType = ChangeType.ADDED, diffContent = "+" + "a".repeat(70_000))
            val vault =
                FileChange(path = "ops/vault.txt", changeType = ChangeType.MODIFIED, diffContent = "@@ -1 +1,2 @@\n ctx\n+key TOKEN-1234")
            val mr = createTestMergeRequest(title = "add TOKEN-9999", changes = listOf(huge, vault))

            val findings = module.validate(SecurityRuleModule.RULE_ID, mr, config).map { it.severity to it.message }

            assertEquals(
                listOf(
                    Severity.ERROR to "Diff too large to scan (70001 bytes > 65536): big.txt",
                    Severity.INFO to "Potential secret in an added line",
                    Severity.INFO to "Potential secret detected in MR title",
                    Severity.WARNING to "Sensitive file change detected: ops/vault.txt",
                ),
                findings,
            )
        }

    @Test
    fun `secret scan can be turned off while sensitive files are still reported`() {
        val config = SecurityConfig(scanForSecrets = false, sensitiveFiles = listOf("**/*.pem"))
        val mr =
            createTestMergeRequest(
                title = "password=hunter2hunter2",
                changes = listOf(FileChange(path = "c/a.pem", changeType = ChangeType.ADDED))
            )

        assertEquals(listOf("Sensitive file change detected: c/a.pem"), SecurityValidator(config).validate(mr).map { it.message })
    }
}
