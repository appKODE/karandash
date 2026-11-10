@file:OptIn(io.karandash.api.ExperimentalKarandashApi::class)

package io.karandash.validators

import io.karandash.api.platform.MergeRequestField
import io.karandash.testfixtures.createTestMergeRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ConflictRuleModuleTest {
    private val module = ConflictRuleModule()

    @Test
    fun `module needs conflict data and blocks a conflicted MR by default`() =
        runTest {
            assertEquals(setOf(MergeRequestField.CONFLICTS), module.requiredMergeRequestFields(ConflictRuleModule.RULE_ID))
            val conflicted = createTestMergeRequest().copy(mergeStatus = " CANNOT_BE_MERGED ")

            val finding = module.validate(ConflictRuleModule.RULE_ID, conflicted, JsonObject(emptyMap())).single()

            assertEquals("MR has merge conflicts that must be resolved before merging", finding.message)
            assertEquals("Rebase or merge the target branch to resolve conflicts.", finding.suggestion)
        }

    @Test
    fun `block on conflicts false turns the rule off`() =
        runTest {
            val conflicted = createTestMergeRequest().copy(hasConflicts = true)
            val off = JsonObject(mapOf("blockOnConflicts" to JsonPrimitive(false)))

            assertTrue(module.validate(ConflictRuleModule.RULE_ID, conflicted, off).isEmpty())
            assertTrue(module.validate(ConflictRuleModule.RULE_ID, createTestMergeRequest(), JsonObject(emptyMap())).isEmpty())
        }
}
