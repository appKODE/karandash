package io.karandash.validators

import io.karandash.config.keys.ModuleIds
import io.karandash.helpers.RuleIds
import kotlin.test.Test
import kotlin.test.assertEquals

/** The CLI copy in [ModuleIds] and the cross-tier [RuleIds] constant name the same rule as the module. */
class ApprovalRuleIdTest {
    @Test
    fun `cli and cross-tier constants match the approval module id`() {
        assertEquals(ApprovalRuleModule.RULE_ID, ModuleIds.APPROVAL)
        assertEquals(ApprovalRuleModule.RULE_ID, RuleIds.APPROVAL)
    }
}
