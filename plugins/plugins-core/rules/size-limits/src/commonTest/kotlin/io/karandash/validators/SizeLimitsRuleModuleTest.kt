package io.karandash.validators

import io.karandash.models.ChangeType
import io.karandash.models.FileChange
import io.karandash.models.finding.Severity
import io.karandash.testfixtures.TestPluginContext
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SizeLimitsRuleModuleTest {
    private val mr =
        createTestMergeRequest(
            changes =
                listOf(
                    FileChange(path = "a.kt", changeType = ChangeType.MODIFIED, additions = 5, deletions = 4),
                    FileChange(path = "b.kt", changeType = ChangeType.ADDED, additions = 1),
                    FileChange(path = "c.kt", changeType = ChangeType.DELETED, deletions = 1),
                ),
        )

    @Test
    fun `module reads limits and severity from json config`() =
        runTest {
            val module = SizeLimitsRuleModule()
            assertTrue(module.initialize(TestPluginContext()))
            val config =
                JsonObject(
                    mapOf(
                        "maxFiles" to JsonPrimitive(2),
                        "maxLines" to JsonPrimitive(12),
                        "severities" to
                            JsonObject(mapOf("onViolation" to JsonPrimitive("warning"), "warnThreshold" to JsonPrimitive("80%"))),
                    ),
                )

            val findings = module.validate(SizeLimitsRuleModule.RULE_ID, mr, config)

            assertEquals(
                listOf(
                    Severity.WARNING to "Too many files changed: 3 (max: 2)",
                    Severity.WARNING to "Approaching lines changed limit: 11 (warn at 9, max: 12)",
                ),
                findings.map { it.severity to it.message },
            )
        }

    @Test
    fun `a value equal to the limit passes and excluded deletions are not counted`() {
        val config = SizeLimitsConfig(maxFiles = 2, maxLines = 10, excludeDeleted = true)

        assertTrue(SizeLimitsValidator(config).validate(mr).isEmpty())
    }
}
